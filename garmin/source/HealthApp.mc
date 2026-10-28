import Toybox.Application;
import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;

(:background)
class HealthApp extends Application.AppBase {
    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
        if (Application.Storage.getValue("enabled") == null) {
            Application.Storage.setValue("enabled", true);
        }
        HealthProtocol.configure();
        Communications.registerForPhoneAppMessages(method(:onMessage));
    }

    function onMessage(message as Communications.PhoneAppMessage) as Void {
        HealthProtocol.acknowledge(message.data);
    }

    function getInitialView() {
        // onStart also runs for background ACK events. Only a foreground view
        // should send an initial sample, otherwise each ACK starts another send.
        HealthProtocol.sendAutomatic(false);
        return [new HealthView(), new HealthDelegate()];
    }

    function getServiceDelegate() {
        return [new HealthService()];
    }
}
