import {
  DEFAULT_AGENT_URL,
  detectBrowserFromNavigator,
  fetchWithTimeout,
  loadSettings,
  normalizeAgentUrl,
} from "./config.js";

const $ = (id) => document.getElementById(id);

function setStatus(text, cls = "") {
  const el = $("status");
  el.textContent = text;
  el.className = cls;
}

async function init() {
  const s = await loadSettings();
  $("agentUrl").value = s.agentUrl;
  $("browserOverride").value = s.browserOverride;

  let detected = detectBrowserFromNavigator();
  try {
    const tab = await chrome.tabs.getCurrent();
    if (tab && "vivExtData" in tab) detected = "vivaldi";
  } catch {
    // ignore
  }
  $("detected").textContent = detected;
}

async function save() {
  const url = normalizeAgentUrl($("agentUrl").value);
  if (!url) {
    setStatus("URL が不正です。http://127.0.0.1:<port> または http://localhost:<port> を指定してください。", "err");
    return null;
  }
  $("agentUrl").value = url;
  await chrome.storage.local.set({ agentUrl: url, browserOverride: $("browserOverride").value });
  setStatus("保存しました。", "ok");
  return url;
}

async function test() {
  const url = normalizeAgentUrl($("agentUrl").value) ?? DEFAULT_AGENT_URL;
  setStatus(`${url}/v1/status に接続中...`);
  try {
    const res = await fetchWithTimeout(`${url}/v1/status`, { method: "GET" }, 2000);
    if (!res.ok) {
      setStatus(`接続できましたが HTTP ${res.status} が返りました。`, "err");
      return;
    }
    let info = {};
    try {
      info = await res.json();
    } catch {
      // ignore
    }
    setStatus(`接続 OK: ${info.name ?? "?"} ${info.version ?? ""}`.trim(), "ok");
  } catch (e) {
    const reason = e?.name === "AbortError" ? "タイムアウト" : String(e?.message ?? e);
    setStatus(`接続できません（${reason}）。エージェントが起動しているか確認してください。`, "err");
  }
}

$("save").addEventListener("click", () => {
  save().catch((e) => setStatus(String(e), "err"));
});
$("test").addEventListener("click", () => {
  test().catch((e) => setStatus(String(e), "err"));
});

init().catch(() => {});
