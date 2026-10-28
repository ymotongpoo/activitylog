import Toybox.Lang;
import Toybox.Time;

(:test)
class HealthTestSample {
    var data;
    var when;
    function initialize(value, at) {
        data = value;
        when = new Time.Moment(at);
    }
}

(:test)
class HealthTestHistory {
    var _samples as Array;
    var calls = 0;
    var _repeat;
    function initialize(samples, repeat) {
        _samples = samples as Array;
        _repeat = repeat;
    }
    function next() {
        var index = calls;
        calls += 1;
        if (_repeat) { return _samples[0]; }
        return index < _samples.size() ? _samples[index] : null;
    }
}

(:test)
function readingSkipsRecentGaps(logger) as Boolean {
    var now = 1800000000;
    var history = new HealthTestHistory([
        new HealthTestSample(-1, now),
        new HealthTestSample(null, now - 30),
        new HealthTestSample(-2, now - 60),
        new HealthTestSample(68, now - 120),
        new HealthTestSample(17, now - 180)
    ], false);
    var result = HealthProtocol.reading(history, now);
    return result != null && result["value"] == 68 && result["at"] == now - 120;
}

(:test)
function readingKeepsZeroAndOriginalTime(logger) as Boolean {
    var now = 1800000000;
    var history = new HealthTestHistory([
        new HealthTestSample(72, now + 120),
        new HealthTestSample(101, now),
        new HealthTestSample("unknown", now - 10),
        new HealthTestSample(0.0, now - HealthProtocol.HISTORY_LOOKBACK_SECONDS)
    ], false);
    var result = HealthProtocol.reading(history, now);
    return result != null && result["value"] == 0 &&
        result["at"] == now - HealthProtocol.HISTORY_LOOKBACK_SECONDS;
}

(:test)
function readingRejectsStaleOrMissingMeasurements(logger) as Boolean {
    var now = 1800000000;
    var stale = new HealthTestHistory([
        new HealthTestSample(50, now - HealthProtocol.HISTORY_LOOKBACK_SECONDS - 1)
    ], false);
    var empty = new HealthTestHistory([], false);
    return HealthProtocol.reading(stale, now) == null &&
        HealthProtocol.reading(empty, now) == null && HealthProtocol.reading(null, now) == null;
}

(:test)
function readingBoundsInvalidHistoryScan(logger) as Boolean {
    var now = 1800000000;
    var history = new HealthTestHistory([new HealthTestSample(-1, now)], true);
    return HealthProtocol.reading(history, now) == null &&
        history.calls == HealthProtocol.MAX_HISTORY_SAMPLES;
}

(:test)
function addedReadingsPreserveRangesAndDecimals(logger) as Boolean {
    var now = 1800000000;
    var history = new HealthTestHistory([
        new HealthTestSample(255, now),
        new HealthTestSample(0, now - 15),
        new HealthTestSample(138.5, now - 30)
    ], false);
    var heart = HealthProtocol.readingInRange(history, now, 1, 254, false);
    var battery = HealthProtocol.current(89.125, now, 0, 100, false);
    return heart != null && heart["value"] == 138.5 && heart["at"] == now - 30 &&
        battery != null && battery["value"] == 89.125 &&
        HealthProtocol.current(-1, now, 0, 100, true) == null &&
        HealthProtocol.current(1.5, now, 0, 100, true) == null &&
        HealthProtocol.current(0, now, 0, 2147483647, true)["value"] == 0;
}
