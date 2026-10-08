// activitylog VS Code extension.
//
// Reports the active file, project, language and Git branch to the local
// activitylog agent via POST /v1/editor. Works in VS Code and its forks
// (Cursor, Windsurf, VSCodium). The agent applies privacy rules, so raw values
// are sent as-is. Network failures are swallowed silently.

import * as vscode from "vscode";

const DEFAULT_AGENT_URL = "http://127.0.0.1:5610";
const HEARTBEAT_MS = 30_000;
const EDIT_THROTTLE_MS = 2_000;
const POST_TIMEOUT_MS = 2_000;
const DEACTIVATE_TIMEOUT_MS = 1_000;

type EditorEvent = "focus" | "blur" | "open" | "edit" | "save" | "heartbeat";

interface EditorPayload {
  editor: string;
  app_name: string;
  instance: string;
  event: EditorEvent;
  focused: boolean;
  time: string;
  project?: string;
  project_path?: string;
  file?: string;
  language?: string;
  branch?: string;
  repository?: string;
}

// Minimal subset of the built-in Git extension API (vscode.git, API v1).
interface GitRemote {
  readonly name: string;
  readonly fetchUrl?: string;
  readonly pushUrl?: string;
}
interface GitRepository {
  readonly rootUri: vscode.Uri;
  readonly state: {
    readonly HEAD?: { readonly name?: string };
    readonly remotes: readonly GitRemote[];
  };
}
interface GitAPI {
  readonly repositories: readonly GitRepository[];
  getRepository(uri: vscode.Uri): GitRepository | null;
}
interface GitExtension {
  readonly enabled: boolean;
  getAPI(version: 1): GitAPI;
}

const APP_NAME_TO_EDITOR: Record<string, string> = {
  "Visual Studio Code": "vscode",
  "Visual Studio Code - Insiders": "vscode-insiders",
  Cursor: "cursor",
  Windsurf: "windsurf",
  VSCodium: "vscodium",
};

function editorId(appName: string): string {
  return APP_NAME_TO_EDITOR[appName] ?? appName.toLowerCase();
}

class Reporter implements vscode.Disposable {
  private readonly disposables: vscode.Disposable[] = [];
  private heartbeat: NodeJS.Timeout | undefined;
  private focused: boolean;
  private lastEditAt = 0;
  private git: GitAPI | undefined;

  private agentUrl = DEFAULT_AGENT_URL;
  private enabled = true;

  private readonly editor = editorId(vscode.env.appName);
  private readonly appName = vscode.env.appName;
  private readonly instance = vscode.env.sessionId;

  constructor() {
    this.focused = vscode.window.state.focused;
    this.loadConfig();

    this.disposables.push(
      vscode.workspace.onDidChangeConfiguration((e) => {
        if (!e.affectsConfiguration("activitylog")) return;
        if (this.enabled && this.focused) {
          // Close the current interval on the agent side before switching
          // URL or disabling.
          void this.send("blur", undefined, false, true);
        }
        this.loadConfig();
        this.updateHeartbeat();
        if (this.enabled && this.focused) void this.send("focus");
      }),

      vscode.window.onDidChangeWindowState((state) => {
        if (state.focused === this.focused) return;
        this.focused = state.focused;
        this.updateHeartbeat();
        void this.send(state.focused ? "focus" : "blur");
      }),

      vscode.window.onDidChangeActiveTextEditor((editor) => {
        if (!editor) return;
        void this.send("open", editor.document);
      }),

      vscode.workspace.onDidChangeTextDocument((e) => {
        if (e.contentChanges.length === 0) return;
        if (e.document !== vscode.window.activeTextEditor?.document) return;
        const now = Date.now();
        if (now - this.lastEditAt < EDIT_THROTTLE_MS) return;
        this.lastEditAt = now;
        void this.send("edit", e.document);
      }),

      vscode.workspace.onDidSaveTextDocument((doc) => {
        void this.send("save", doc);
      }),
    );

    void this.initGit();
    this.updateHeartbeat();
    if (this.focused) void this.send("focus");
  }

  private loadConfig(): void {
    const cfg = vscode.workspace.getConfiguration("activitylog");
    this.enabled = cfg.get<boolean>("enabled", true);
    const url = (cfg.get<string>("agentUrl", DEFAULT_AGENT_URL) || DEFAULT_AGENT_URL).trim();
    this.agentUrl = url.replace(/\/+$/, "");
  }

  private async initGit(): Promise<void> {
    try {
      const ext = vscode.extensions.getExtension<GitExtension>("vscode.git");
      if (!ext) return;
      const exports = ext.isActive ? ext.exports : await ext.activate();
      if (!exports || !exports.enabled) return;
      this.git = exports.getAPI(1);
    } catch {
      this.git = undefined;
    }
  }

  private updateHeartbeat(): void {
    const shouldRun = this.enabled && this.focused;
    if (shouldRun && !this.heartbeat) {
      this.heartbeat = setInterval(() => {
        if (this.enabled && this.focused) void this.send("heartbeat");
      }, HEARTBEAT_MS);
    } else if (!shouldRun && this.heartbeat) {
      clearInterval(this.heartbeat);
      this.heartbeat = undefined;
    }
  }

  private buildPayload(event: EditorEvent, doc?: vscode.TextDocument, focused?: boolean): EditorPayload {
    const payload: EditorPayload = {
      editor: this.editor,
      app_name: this.appName,
      instance: this.instance,
      event,
      focused: focused ?? (event === "blur" ? false : this.focused),
      time: new Date().toISOString(),
    };

    doc ??= vscode.window.activeTextEditor?.document;

    // Workspace folder containing the active file. Without an active editor,
    // use the folder of a single-folder workspace.
    let folder: vscode.WorkspaceFolder | undefined;
    if (doc) {
      folder = vscode.workspace.getWorkspaceFolder(doc.uri);
    } else if (vscode.workspace.workspaceFolders?.length === 1) {
      folder = vscode.workspace.workspaceFolders[0];
    }

    const project = folder?.name ?? vscode.workspace.name;
    if (project) payload.project = project;
    if (folder && folder.uri.scheme === "file") payload.project_path = folder.uri.fsPath;

    if (doc) {
      if (doc.uri.scheme === "file") payload.file = doc.uri.fsPath;
      payload.language = doc.languageId;
    }

    try {
      const target = doc?.uri ?? folder?.uri;
      const repo = target && this.git ? this.git.getRepository(target) : null;
      if (repo) {
        const branch = repo.state.HEAD?.name;
        if (branch) payload.branch = branch;
        const remotes = repo.state.remotes ?? [];
        const remote = remotes.find((r) => r.name === "origin") ?? remotes[0];
        const url = remote?.fetchUrl ?? remote?.pushUrl;
        if (url) payload.repository = url;
      }
    } catch {
      // Git extension unavailable or in a bad state: omit VCS fields.
    }

    return payload;
  }

  async send(
    event: EditorEvent,
    doc?: vscode.TextDocument,
    focused?: boolean,
    force = false,
    timeoutMs = POST_TIMEOUT_MS,
  ): Promise<void> {
    if (!this.enabled && !force) return;
    try {
      const payload = this.buildPayload(event, doc, focused);
      await fetch(`${this.agentUrl}/v1/editor`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch {
      // Agent not running or timed out: ignore.
    }
  }

  async checkConnection(): Promise<void> {
    const url = `${this.agentUrl}/v1/status`;
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(POST_TIMEOUT_MS) });
      if (!res.ok) {
        void vscode.window.showWarningMessage(`activitylog: ${url} returned HTTP ${res.status}`);
        return;
      }
      const info = (await res.json().catch(() => ({}))) as { name?: string; version?: string };
      void vscode.window.showInformationMessage(
        `activitylog: connected to ${info.name ?? "agent"} ${info.version ?? ""}`.trim(),
      );
    } catch (e) {
      void vscode.window.showWarningMessage(`activitylog: cannot reach ${url} (${(e as Error).message})`);
    }
  }

  // Best-effort final blur; resolves within DEACTIVATE_TIMEOUT_MS.
  async shutdown(): Promise<void> {
    this.dispose();
    await this.send("blur", undefined, false, false, DEACTIVATE_TIMEOUT_MS);
  }

  dispose(): void {
    if (this.heartbeat) {
      clearInterval(this.heartbeat);
      this.heartbeat = undefined;
    }
    for (const d of this.disposables.splice(0)) {
      try {
        d.dispose();
      } catch {
        // ignore
      }
    }
  }
}

let reporter: Reporter | undefined;

export function activate(context: vscode.ExtensionContext): void {
  reporter = new Reporter();
  context.subscriptions.push(
    reporter,
    vscode.commands.registerCommand("activitylog.checkConnection", () => reporter?.checkConnection()),
  );
}

export function deactivate(): Promise<void> | undefined {
  const r = reporter;
  reporter = undefined;
  return r?.shutdown();
}
