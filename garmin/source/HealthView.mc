import Toybox.Application.Storage;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

class HealthView extends WatchUi.View {
    var _timer;
    function initialize() {
        View.initialize();
    }

    function onShow() {
        _timer = new Timer.Timer();
        _timer.start(method(:refresh), 1000, true);
    }

    function onHide() {
        if (_timer != null) { _timer.stop(); }
    }

    function refresh() as Void { WatchUi.requestUpdate(); }

    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var mid = dc.getWidth() / 2;
        dc.drawText(mid, 30, Graphics.FONT_SMALL, "Activitylog Health", Graphics.TEXT_JUSTIFY_CENTER);
        var sample = Storage.getValue("lastSample") as Lang.Dictionary or Null;
        var battery = (sample == null ? null : sample["bodyBattery"]);
        var stress = (sample == null ? null : sample["stress"]);
        dc.drawText(mid, 80, Graphics.FONT_SMALL, "Battery: " + (battery == null ? "--" : battery.toString()), Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(mid, 115, Graphics.FONT_SMALL, "Stress: " + (stress == null ? "--" : stress.toString()), Graphics.TEXT_JUSTIFY_CENTER);
        var enabled = Storage.getValue("enabled") == true;
        dc.drawText(mid, 155, Graphics.FONT_XTINY, enabled ? "Sending every 5 min" : "Paused", Graphics.TEXT_JUSTIFY_CENTER);
        var dropped = Storage.getValue("dropped");
        var queueText = "Pending: " + HealthProtocol.pending().size().toString() +
            " / lost: " + (dropped == null ? "0" : dropped.toString());
        var automatic = Storage.getValue("lastAutomaticAt");
        var autoText = automatic == null ? "Auto: waiting" : "Auto age: " + (Time.now().value() - automatic).toString() + "s";
        dc.drawText(mid, 175, Graphics.FONT_XTINY, autoText, Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(mid, 195, Graphics.FONT_XTINY, queueText, Graphics.TEXT_JUSTIFY_CENTER);
        var ack = Storage.getValue("lastAckAt");
        var ackText = (ack == null ? "Android ACK: none" : "ACK age: " + (Time.now().value() - ack).toString() + "s");
        dc.drawText(mid, 215, Graphics.FONT_XTINY, ackText, Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(mid, 240, Graphics.FONT_XTINY, "START: menu / BACK: close", Graphics.TEXT_JUSTIFY_CENTER);
    }
}

class HealthDelegate extends WatchUi.BehaviorDelegate {
    function initialize() {
        BehaviorDelegate.initialize();
    }

    function onSelect() {
        var menu = new WatchUi.Menu2({:title => "Activitylog Health"});
        menu.addItem(new WatchUi.MenuItem("Send now", "Optional; auto every 5 min", :send, {}));
        var enabled = Storage.getValue("enabled") == true;
        menu.addItem(new WatchUi.MenuItem(enabled ? "Pause" : "Resume", null, :toggle, {}));
        WatchUi.pushView(menu, new HealthMenuDelegate(), WatchUi.SLIDE_UP);
        return true;
    }
}

class HealthMenuDelegate extends WatchUi.Menu2InputDelegate {
    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item) {
        if (item.getId() == :toggle) {
            Storage.setValue("enabled", Storage.getValue("enabled") != true);
            HealthProtocol.configure();
            HealthProtocol.sendAutomatic(false);
        } else {
            try {
                HealthProtocol.capture();
                HealthProtocol.send(new HealthSendListener(false));
            } catch (error) {
                Storage.setValue("lastStatus", "Send failed; queued");
            }
        }
        WatchUi.popView(WatchUi.SLIDE_DOWN);
        WatchUi.requestUpdate();
    }
}
