import Toybox.Application.Storage;
import Toybox.ActivityMonitor;
import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.SensorHistory;
import Toybox.System;
import Toybox.Time;

// Wire format is decoded by android/.../garmin/GarminProtocol.kt.
(:background)
module HealthProtocol {
    const MAX_PENDING = 12;
    const HISTORY_LOOKBACK_SECONDS = 1800;
    const MAX_HISTORY_SAMPLES = 120;

    function pending() as Array<Dictionary> {
        var saved = Storage.getValue("pending");
        return (saved instanceof Lang.Array) ? (saved as Array<Dictionary>) : [];
    }

    function configure() {
        if (Storage.getValue("enabled") == true) {
            var registered = Background.getTemporalEventRegisteredTime();
            if (!(registered instanceof Time.Duration) || registered.value() != 300) {
                Background.registerForTemporalEvent(new Time.Duration(300));
            }
            Background.registerForPhoneAppMessageEvent();
        } else {
            Background.deleteTemporalEvent();
            // Keep receiving delayed ACKs for data already delivered to Android.
        }
    }

    function sendAutomatic(background as Boolean) as Boolean {
        if (Storage.getValue("enabled") != true) { return false; }
        try {
            capture();
            Storage.setValue("lastAutomaticAt", Time.now().value());
            return send(new HealthSendListener(background));
        } catch (error) {
            Storage.setValue("lastStatus", "Send failed; queued");
            return false;
        }
    }

    // The iterator is duck-typed so the same selection can be regression-tested
    // with gaps, invalid readings, and stale samples in the simulator.
    function reading(iterator, now as Number) as Dictionary or Null {
        return readingInRange(iterator, now, 0, 100, true);
    }

    function readingInRange(iterator, now as Number, minimum, maximum, integer) as Dictionary or Null {
        if (iterator == null) { return null; }
        for (var index = 0; index < MAX_HISTORY_SAMPLES; index += 1) {
            var sample = iterator.next();
            if (sample == null) { return null; }
            var at = sample.when.value();
            if (at < now - HISTORY_LOOKBACK_SECONDS) { return null; }
            if (at > now + 60) { continue; }
            var result = current(sample.data, at, minimum, maximum, integer);
            // History may end with gaps even while the native glance shows a
            // previous measurement. Keep its original timestamp, never now.
            if (result != null) { return result; }
        }
        return null;
    }

    function current(value, at as Number, minimum, maximum, integer) as Dictionary or Null {
        if (!(value instanceof Lang.Number) && !(value instanceof Lang.Float) &&
            !(value instanceof Lang.Long) && !(value instanceof Lang.Double)) { return null; }
        if (!(value >= minimum && value <= maximum)) { return null; }
        if (integer && value != value.toNumber()) { return null; }
        return {"value" => (integer ? value.toNumber() : value), "at" => at};
    }

    function putReading(row as Dictionary, key as String, value as Dictionary or Null) {
        row[key] = value == null ? null : value["value"];
        row[key + "At"] = value == null ? null : value["at"];
    }

    function capture() {
        var rows = pending();
        var now = Time.now().value();
        if (rows.size() > 0 && rows[rows.size() - 1]["capturedAt"] >= now) { return; }
        var options = {:period => new Time.Duration(HISTORY_LOOKBACK_SECONDS),
                       :order => SensorHistory.ORDER_NEWEST_FIRST};
        var battery = reading(SensorHistory.getBodyBatteryHistory(options), now);
        var stress = reading(SensorHistory.getStressHistory(options), now);
        var row = {
            "capturedAt" => now,
            "bodyBattery" => (battery == null ? null : battery["value"]),
            "bodyBatteryAt" => (battery == null ? null : battery["at"]),
            "stress" => (stress == null ? null : stress["value"]),
            "stressAt" => (stress == null ? null : stress["at"])
        };
        // Read existing watch measurements; do not start continuous sensors.
        try {
            putReading(row, "heartRate", readingInRange(SensorHistory.getHeartRateHistory(options), now, 1, 254, false));
        } catch (error) { putReading(row, "heartRate", null); }
        try {
            putReading(row, "oxygenSaturation", readingInRange(SensorHistory.getOxygenSaturationHistory(options), now, 1, 100, false));
        } catch (error) { putReading(row, "oxygenSaturation", null); }
        // These APIs have no underlying measurement timestamp. Timestamp their
        // current-state observation at collection time, separately from history.
        try {
            var activity = ActivityMonitor.getInfo();
            putReading(row, "respirationRate", current(activity.respirationRate, now, 1, 100, false));
            putReading(row, "stepsToday", current(activity.steps, now, 0, 2147483647, true));
        } catch (error) {
            putReading(row, "respirationRate", null);
            putReading(row, "stepsToday", null);
        }
        try {
            var stats = System.getSystemStats();
            putReading(row, "watchBattery", current(stats.battery, now, 0, 100, false));
            // A negative native solar value means solar charging is inactive;
            // preserve it as unavailable rather than inventing a light reading.
            putReading(row, "solarIntensity", current(stats.solarIntensity, now, 0, 100, true));
        } catch (error) {
            putReading(row, "watchBattery", null);
            putReading(row, "solarIntensity", null);
        }
        rows.add(row);
        if (rows.size() > MAX_PENDING) {
            rows.remove(rows[0]);
            var dropped = Storage.getValue("dropped");
            Storage.setValue("dropped", (dropped == null ? 1 : dropped + 1));
        }
        Storage.setValue("pending", rows);
        Storage.setValue("lastSample", rows[rows.size() - 1]);
    }

    function send(listener as Communications.ConnectionListener) as Boolean {
        var rows = pending();
        if (rows.size() == 0) { return false; }
        Storage.setValue("sentThrough", rows[rows.size() - 1]["capturedAt"]);
        Communications.transmit({"schema" => 1, "type" => "health", "samples" => rows}, null, listener);
        return true;
    }

    function acknowledge(message) {
        if (!(message instanceof Lang.Dictionary) || message["schema"] != 1) { return; }
        var ack = message["ack"];
        var sentThrough = Storage.getValue("sentThrough");
        if (!(ack instanceof Lang.Number) || !(sentThrough instanceof Lang.Number) || ack > sentThrough) { return; }
        var previous = Storage.getValue("lastAck");
        if (previous instanceof Lang.Number && ack <= previous) { return; }
        var rows = pending();
        var remaining = [];
        for (var index = 0; index < rows.size(); index += 1) {
            var row = rows[index];
            if (row["capturedAt"] > ack) { remaining.add(row); }
        }
        Storage.setValue("pending", remaining);
        Storage.setValue("lastAck", ack);
        Storage.setValue("lastAckAt", Time.now().value());
        Storage.setValue("lastStatus", "Saved on Android");
    }
}
