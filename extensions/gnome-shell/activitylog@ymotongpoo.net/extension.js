// activitylog GNOME Shell extension.
//
// Exposes the currently focused window on the Shell's own session bus
// connection, so that activitylog-agent can read it on Wayland, where external
// processes cannot query the focused window.
//
//   destination: org.gnome.Shell
//   object path: /net/ymotongpoo/ActivityLog
//   interface:   net.ymotongpoo.ActivityLog1
//
// See docs/design.md ("GNOME Shell 拡張の D-Bus インターフェース").

import Gio from 'gi://Gio';
import Shell from 'gi://Shell';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

const OBJECT_PATH = '/net/ymotongpoo/ActivityLog';

// Version of the D-Bus interface contract (not the extension release).
const INTERFACE_VERSION = 1;

const IFACE_XML = `
<node>
  <interface name="net.ymotongpoo.ActivityLog1">
    <method name="GetFocusedWindow">
      <arg type="s" direction="out" name="json"/>
    </method>
    <property name="Version" type="u" access="read"/>
  </interface>
</node>`;

// Calls fn and returns its result, or fallback if it throws or returns
// null/undefined. Used to make every Meta/Shell getter null-safe, since the
// window may be unmanaged between get_focus_window() and the getter call.
function safe(fn, fallback) {
    try {
        const v = fn();
        return v === null || v === undefined ? fallback : v;
    } catch (_e) {
        return fallback;
    }
}

function asString(v) {
    return typeof v === 'string' ? v : '';
}

function asPid(v) {
    // Mutter returns 0 (or -1 on older versions) when the PID is unknown.
    return Number.isInteger(v) && v > 0 ? v : 0;
}

function isFullscreen(win) {
    // Meta.Window has both an is_fullscreen() method and a read-only
    // "fullscreen" GObject property; prefer the method and fall back to the
    // property in case either is missing in a given Mutter version.
    if (typeof win.is_fullscreen === 'function')
        return safe(() => win.is_fullscreen() === true, false);
    return safe(() => win.fullscreen === true, false);
}

function stripDesktopSuffix(id) {
    return id.endsWith('.desktop') ? id.slice(0, -'.desktop'.length) : id;
}

function describeApp(win) {
    const app = safe(() => Shell.WindowTracker.get_default().get_window_app(win), null);
    if (!app)
        return {appId: '', appName: ''};

    const appName = asString(safe(() => app.get_name(), ''));
    // Window-backed apps (no matching .desktop file) get a synthetic id such
    // as "window:42", which is not a desktop ID. Report it as unknown so the
    // agent falls back to WM_CLASS.
    const windowBacked = safe(() => app.is_window_backed(), false) === true;
    const rawId = windowBacked ? '' : asString(safe(() => app.get_id(), ''));
    return {appId: stripDesktopSuffix(rawId), appName};
}

class ActivityLogService {
    get Version() {
        return INTERFACE_VERSION;
    }

    GetFocusedWindow() {
        try {
            const win = global.display.get_focus_window();
            if (!win)
                return '{}';

            const {appId, appName} = describeApp(win);
            return JSON.stringify({
                title: asString(safe(() => win.get_title(), '')),
                wm_class: asString(safe(() => win.get_wm_class(), '')),
                wm_class_instance: asString(safe(() => win.get_wm_class_instance(), '')),
                pid: asPid(safe(() => win.get_pid(), 0)),
                app_id: appId,
                app_name: appName,
                sandboxed_app_id: asString(safe(() => win.get_sandboxed_app_id(), '')),
                fullscreen: isFullscreen(win),
            });
        } catch (e) {
            // Never let an exception escape to the D-Bus caller.
            console.warn(`activitylog: GetFocusedWindow failed: ${e}`);
            return '{}';
        }
    }
}

export default class ActivityLogExtension extends Extension {
    enable() {
        this._dbusImpl = Gio.DBusExportedObject.wrapJSObject(
            IFACE_XML, new ActivityLogService());
        this._dbusImpl.export(Gio.DBus.session, OBJECT_PATH);
    }

    disable() {
        if (this._dbusImpl) {
            this._dbusImpl.unexport();
            this._dbusImpl = null;
        }
    }
}
