// Shared configuration helpers used by the service worker and the options page.

export const DEFAULT_AGENT_URL = "http://127.0.0.1:5610";

// Browser identifiers accepted by POST /v1/browser.
export const BROWSER_IDS = [
  "chrome",
  "edge",
  "brave",
  "arc",
  "vivaldi",
  "chromium",
  "opera",
  "other",
];

// Best-effort browser detection from UA client hints. Vivaldi and Arc do not
// expose a distinct brand; Vivaldi is refined later from tab properties and
// Arc can only be set via the options page.
export function detectBrowserFromNavigator() {
  try {
    const brands = (navigator.userAgentData?.brands ?? []).map((b) => String(b.brand));
    const has = (name) => brands.some((b) => b.toLowerCase() === name.toLowerCase());
    if (typeof navigator.brave?.isBrave === "function" || has("Brave")) return "brave";
    if (has("Microsoft Edge")) return "edge";
    if (has("Opera") || has("Opera GX")) return "opera";
    if (has("Vivaldi")) return "vivaldi";
    if (has("Google Chrome")) return "chrome";
    if (has("Chromium")) return "chromium";
  } catch {
    // ignore
  }
  return "chrome";
}

// Normalizes a user-supplied agent base URL. Accepts a bare port ("5610"),
// "host:port", or a full URL. Returns null if the value is unusable.
// Only loopback hosts are allowed because host_permissions only cover them.
export function normalizeAgentUrl(value) {
  let s = String(value ?? "").trim();
  if (s === "") return DEFAULT_AGENT_URL;
  if (/^\d+$/.test(s)) s = `http://127.0.0.1:${s}`;
  if (!/^[a-z]+:\/\//i.test(s)) s = `http://${s}`;
  let u;
  try {
    u = new URL(s);
  } catch {
    return null;
  }
  if (u.protocol !== "http:") return null;
  if (u.hostname !== "127.0.0.1" && u.hostname !== "localhost") return null;
  return `${u.protocol}//${u.host}`;
}

// Reads the settings from chrome.storage.local, filling in defaults.
export async function loadSettings() {
  const s = await chrome.storage.local.get(["agentUrl", "browserOverride"]);
  return {
    agentUrl: normalizeAgentUrl(s.agentUrl) ?? DEFAULT_AGENT_URL,
    browserOverride: BROWSER_IDS.includes(s.browserOverride) ? s.browserOverride : "",
  };
}

// fetch() with a hard timeout. Never rejects on timeout without the caller
// being able to catch it; callers are expected to wrap in try/catch.
export async function fetchWithTimeout(url, init = {}, timeoutMs = 2000) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    return await fetch(url, { ...init, signal: ctrl.signal, cache: "no-store" });
  } finally {
    clearTimeout(timer);
  }
}
