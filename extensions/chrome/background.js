// activitylog service worker (Manifest V3, ES module).
//
// Reports the active tab of the focused browser window to the local
// activitylog agent via POST /v1/browser. The agent applies privacy rules,
// so raw values are sent as-is. Failures (agent not running, timeouts) are
// swallowed silently.

import {
  BROWSER_IDS,
  DEFAULT_AGENT_URL,
  detectBrowserFromNavigator,
  fetchWithTimeout,
  loadSettings,
} from "./config.js";

const HEARTBEAT_ALARM = "activitylog-heartbeat";
const HEARTBEAT_PERIOD_MIN = 0.5; // 30s; the minimum allowed for alarms in Chrome 120+.
const DEDUPE_WINDOW_MS = 1000;
const FOCUS_DEBOUNCE_MS = 150;
const POST_TIMEOUT_MS = 2000;

// All window types, so that e.g. a focused DevTools window still counts as
// "the browser has OS focus".
const ALL_WINDOW_TYPES = ["normal", "popup", "panel", "app", "devtools"];

// ---------------------------------------------------------------------------
// Settings, instance id and browser detection (cached per service worker life)

let settingsPromise = null;
function settings() {
  if (!settingsPromise) {
    settingsPromise = loadSettings().catch(() => ({ agentUrl: DEFAULT_AGENT_URL, browserOverride: "" }));
  }
  return settingsPromise;
}

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && ("agentUrl" in changes || "browserOverride" in changes)) {
    settingsPromise = null;
  }
});

let instancePromise = null;
function instanceId() {
  if (!instancePromise) {
    instancePromise = (async () => {
      const { instance } = await chrome.storage.local.get("instance");
      if (typeof instance === "string" && instance !== "") return instance;
      const id = crypto.randomUUID();
      await chrome.storage.local.set({ instance: id });
      return id;
    })().catch(() => {
      instancePromise = null;
      return "unknown";
    });
  }
  return instancePromise;
}

// Detected from UA client hints; refined to "vivaldi" when Vivaldi-specific
// properties show up on tab/window objects.
let detectedBrowser = detectBrowserFromNavigator();
let vivaldiChecked = false;

function refineBrowser(win, tab) {
  if (vivaldiChecked) return;
  if ((tab && "vivExtData" in tab) || (win && "vivExtData" in win)) {
    detectedBrowser = "vivaldi";
  }
  vivaldiChecked = Boolean(tab || win);
}

async function browserId() {
  const { browserOverride } = await settings();
  if (browserOverride && BROWSER_IDS.includes(browserOverride)) return browserOverride;
  return detectedBrowser;
}

// ---------------------------------------------------------------------------
// State collection

// Returns the focused window and its active tab, or null if no browser window
// has OS focus.
async function focusedWindowAndTab() {
  let win;
  try {
    win = await chrome.windows.getLastFocused({ populate: true, windowTypes: ALL_WINDOW_TYPES });
  } catch {
    return null;
  }
  if (!win || !win.focused) return null;

  let tab = win.tabs?.find((t) => t.active);
  if (!tab) {
    // e.g. DevTools window: fall back to the last focused normal window's tab.
    try {
      const normal = await chrome.windows.getLastFocused({ populate: true, windowTypes: ["normal"] });
      tab = normal?.tabs?.find((t) => t.active);
    } catch {
      // ignore
    }
  }
  refineBrowser(win, tab);
  return { win, tab };
}

async function buildPayload(time) {
  const base = {
    browser: await browserId(),
    instance: await instanceId(),
    time: time ?? new Date().toISOString(),
  };
  const state = await focusedWindowAndTab();
  if (!state) return { ...base, focused: false };

  const { win, tab } = state;
  const payload = { ...base, focused: true, window_id: win.id };
  if (tab) {
    payload.url = tab.url ?? tab.pendingUrl ?? "";
    payload.title = tab.title ?? "";
    payload.incognito = Boolean(tab.incognito);
    payload.audible = Boolean(tab.audible);
    payload.tab_id = tab.id;
    payload.window_id = tab.windowId;
  } else {
    payload.incognito = Boolean(win.incognito);
  }
  return payload;
}

// ---------------------------------------------------------------------------
// Sending

let lastKey = "";
let lastSentAt = 0;

async function post(payload, { heartbeat = false } = {}) {
  const { time, ...rest } = payload;
  const key = JSON.stringify(rest);
  const now = Date.now();
  if (!heartbeat && key === lastKey && now - lastSentAt < DEDUPE_WINDOW_MS) return;
  lastKey = key;
  lastSentAt = now;

  const { agentUrl } = await settings();
  try {
    await fetchWithTimeout(
      `${agentUrl}/v1/browser`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      },
      POST_TIMEOUT_MS,
    );
  } catch {
    // Agent not running or timed out: ignore.
  }
}

async function report(opts = {}) {
  const payload = await buildPayload(opts.time);
  if (opts.heartbeat && !payload.focused) return; // heartbeats only while focused
  await post(payload, opts);
}

function safeReport(opts) {
  report(opts).catch(() => {});
}

// ---------------------------------------------------------------------------
// Event wiring

chrome.tabs.onActivated.addListener(() => safeReport());

chrome.tabs.onUpdated.addListener((_tabId, changeInfo, tab) => {
  if (!tab.active) return;
  if ("url" in changeInfo || "title" in changeInfo || "audible" in changeInfo) {
    safeReport();
  }
});

// Window focus changes. Switching between two browser windows briefly fires
// WINDOW_ID_NONE on some platforms, so debounce and only report "unfocused"
// if no other window gained focus in the meantime.
let focusTimer = null;
chrome.windows.onFocusChanged.addListener((windowId) => {
  const time = new Date().toISOString();
  if (focusTimer) clearTimeout(focusTimer);
  focusTimer = setTimeout(() => {
    focusTimer = null;
    if (windowId === chrome.windows.WINDOW_ID_NONE) {
      (async () => {
        await post({ browser: await browserId(), instance: await instanceId(), time, focused: false });
      })().catch(() => {});
    } else {
      safeReport({ time });
    }
  }, FOCUS_DEBOUNCE_MS);
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === HEARTBEAT_ALARM) safeReport({ heartbeat: true });
});

async function ensureHeartbeatAlarm() {
  try {
    const existing = await chrome.alarms.get(HEARTBEAT_ALARM);
    if (!existing || existing.periodInMinutes !== HEARTBEAT_PERIOD_MIN) {
      await chrome.alarms.create(HEARTBEAT_ALARM, { periodInMinutes: HEARTBEAT_PERIOD_MIN });
    }
  } catch {
    // ignore
  }
}

chrome.runtime.onInstalled.addListener(() => {
  ensureHeartbeatAlarm();
  safeReport();
});

chrome.runtime.onStartup.addListener(() => {
  ensureHeartbeatAlarm();
  safeReport();
});

// The service worker may be restarted at any time; make sure the alarm exists.
ensureHeartbeatAlarm();
