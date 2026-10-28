import Toybox.Application.Storage;
import Toybox.Background;
import Toybox.Communications;
import Toybox.System;

(:background)
class HealthService extends System.ServiceDelegate {
    function initialize() {
        ServiceDelegate.initialize();
    }

    function onTemporalEvent() {
        if (!HealthProtocol.sendAutomatic(true)) {
            Background.exit(null);
        }
    }

    function onPhoneAppMessage(message) {
        HealthProtocol.acknowledge(message.data);
        Background.exit(null);
    }
}

(:background)
class HealthSendListener extends Communications.ConnectionListener {
    var _background;
    function initialize(background) {
        ConnectionListener.initialize();
        _background = background;
    }

    function onComplete() {
        // BLE delivery is not proof of Android persistence. Only ACK removes rows.
        Storage.setValue("lastStatus", "Sent; waiting for ACK");
        if (_background) { Background.exit(null); }
    }

    function onError() {
        Storage.setValue("lastStatus", "Send failed; queued");
        if (_background) { Background.exit(null); }
    }
}
