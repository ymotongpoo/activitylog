# activitylog 設計

ActivityWatch と同種のアクティビティ（フォアグラウンドアプリ、ウィンドウタイトル、AFK、ブラウザのタブ、エディタのファイル、ターミナルのコマンド）を収集し、OTLP で Grafana Cloud に直接送る。
GUI は持たない。可視化は Grafana で行う。

## 構成

```
                         ┌──────────────── macOS / Linux ────────────────┐
 Chrome拡張 ──HTTP──┐    │                                               │
 VS Code拡張 ─HTTP──┤    │  activitylog-agent (Go)                        │
 Neovimプラグイン ──┤───▶│   ├─ ingest  (127.0.0.1:5610)                  │
 shell hook ─emit───┘    │   ├─ watcher (前面アプリ/タイトル, AFK, ロック)    │──OTLP/HTTP──▶ Grafana Cloud
                         │   │    macOS: AX API + NSWorkspace + AppleScript │   (traces→Tempo,
 GNOME Shell拡張 ─D-Bus─▶│   │    Linux: GNOME Shell拡張 + Mutter IdleMonitor │    metrics→Mimir,
                         │   ├─ tracker (区間→スパン/メトリクス/ログ)        │    logs→Loki)
                         │   └─ exporter (オフライン時はディスクに退避)      │
                         └───────────────────────────────────────────────┘
 Android app (Kotlin): UsageStatsManager + AccessibilityService ──OTLP/HTTP(JSON)──▶ Grafana Cloud
```

| ディレクトリ | 内容 |
|---|---|
| `agent/` | macOS/Linux 用エージェント（Go 単一コードベース） |
| `extensions/chrome/` | Chromium 系ブラウザ拡張（MV3） |
| `extensions/vscode/` | VS Code 系エディタ拡張 |
| `extensions/nvim/` | Neovim プラグイン |
| `extensions/shell/` | zsh / bash フック |
| `extensions/gnome-shell/` | GNOME Shell 拡張（前面ウィンドウを D-Bus で公開） |
| `android/` | Android アプリ |
| `dashboards/` | Grafana ダッシュボード |

## テレメトリーモデル

### Resource

| 属性 | 値 |
|---|---|
| `service.namespace` | `activitylog` |
| `service.name` | `activitylog-agent`（デスクトップ） / `activitylog-android` |
| `service.version` | ビルドバージョン |
| `service.instance.id` | デバイス名。既定はホスト名（Android は端末名）。設定で上書きできる。Mimir では `instance` ラベルになる |
| `host.name` | ホスト名 |
| `os.type` | `darwin` / `linux` |
| `os.name`, `os.version` | OS 名とバージョン |
| `device.model.identifier`, `device.manufacturer` | Android のみ |

### Traces

区間を階層化したスパンとして送る。スパンは区間が閉じた時点で送信される。

```
trace (1 セッション)
└─ root:   "active" | "afk"                         activity.state
   ├─ app: <アプリ名>  例 "Google Chrome"             activity.app.*
   │  └─ context: "browser.tab" | "editor.file" | "terminal" | "window"
   └─ "terminal.command"（root 直下。フォーカスと無関係に走るため）
```

- root `active` は AFK でない連続区間。`afk` は AFK 区間で、`activity.afk.reason` に `idle` / `locked` / `sleep` を入れる。
- root は `max_session_span`（既定 1h）を超えると分割し、新しいトレースを始める。新しい root には前の root へのスパンリンクを付ける。分割時は app / context スパンも同時に閉じて開き直す。
- app スパンは前面アプリが変わるたびに切る。context スパンはアプリ内の文脈（タイトル、URL、ファイル、cwd）が変わるたびに切る。
- スパン名は低カーディナリティに保つ。タイトルや URL は属性に入れる。
- スパンの kind は `INTERNAL`。

#### 属性

共通

| 属性 | 型 | 説明 |
|---|---|---|
| `activity.state` | string | `active` / `afk`（root） |
| `activity.afk.reason` | string | `idle` / `locked` / `sleep` / `logged_out`（afk root） |
| `activity.app.switches` | int | root の間に前面アプリが切り替わった回数（root の終了時に付与） |
| `activity.app.name` | string | アプリの表示名 |
| `activity.app.id` | string | macOS は bundle ID、Linux は desktop ID（`.desktop` を除く）または WM_CLASS、Android はパッケージ名 |
| `process.pid` | int | 前面アプリの PID（デスクトップ） |
| `activity.window.title` | string | ウィンドウタイトル（プライバシールールで伏せ字になることがある） |
| `activity.context.kind` | string | `browser.tab` / `editor.file` / `terminal` / `window` |
| `activity.context.source` | string | 文脈情報の取得元。`extension` / `applescript` / `accessibility` / `shell` / `proc` / `title` |
| `activity.category` | string | 分類ルールで決まったカテゴリ。例 `Work/Programming`。未分類は `Uncategorized` |

`browser.tab`

| 属性 | 説明 |
|---|---|
| `activity.browser.name` | `chrome` / `edge` / `brave` / `arc` / `vivaldi` / `chromium` |
| `activity.browser.tab.title` | タブのタイトル |
| `url.full` | URL（設定によりパスやクエリを落とす） |
| `url.scheme`, `url.domain`, `url.path` | URL の構成要素 |
| `activity.browser.incognito` | bool |
| `activity.browser.audible` | bool |

`editor.file`

| 属性 | 説明 |
|---|---|
| `activity.editor.name` | `vscode` / `cursor` / `neovim` など |
| `activity.editor.project` | ワークスペース名（リポジトリ名） |
| `activity.editor.project.path` | ワークスペースの絶対パス |
| `code.file.path` | ファイルパス |
| `activity.editor.language` | 言語 ID（`go`, `typescript` など） |
| `vcs.ref.head.name` | Git ブランチ |
| `vcs.repository.url.full` | リモート URL（取れたとき） |

`terminal`（ターミナルアプリが前面のときの context）と `terminal.command`

| 属性 | 説明 |
|---|---|
| `activity.terminal.shell` | `zsh` / `bash` |
| `activity.terminal.cwd` | カレントディレクトリ |
| `activity.terminal.program` | `$TERM_PROGRAM` |
| `activity.terminal.tty` | 端末名（端末モード）。例 `pts/3` |
| `process.parent_pid` | シェルの PID（`terminal.command`） |
| `process.command` | コマンド。既定はコマンド名のみ（`terminal.command`） |
| `activity.terminal.command.name` | コマンドの先頭語（`terminal.command`） |
| `process.exit.code` | 終了コード（`terminal.command`）。0 以外ならスパンの status を Error にする |

### Metrics

すべて cumulative の Counter（単位 `s`、コマンド回数のみ単位なし）。60 秒ごとに送る。
時間は確定した分だけ加算する。AFK かどうか確定していない時間（最後の入力から現在まで）は、確定した時点でどちらかに加算する。

| OTel 名 | Prometheus 名 | 属性 |
|---|---|---|
| `activity.time` | `activity_time_seconds_total` | `activity.state` |
| `activity.app.time` | `activity_app_time_seconds_total` | `activity.app.name`, `activity.app.id` |
| `activity.category.time` | `activity_category_time_seconds_total` | `activity.category` |
| `activity.browser.domain.time` | `activity_browser_domain_time_seconds_total` | `url.domain`（設定で無効化できる） |
| `activity.editor.project.time` | `activity_editor_project_time_seconds_total` | `activity.editor.project`, `activity.editor.language` |
| `activity.terminal.commands` | `activity_terminal_commands_total` | `activity.terminal.command.name`, `activity.terminal.command.status`（`ok` / `error`） |
| `activity.device.unlocks` | `activity_device_unlocks_total` | なし（Android のみ） |

Mimir 側では `job="activitylog/activitylog-agent"`、`instance=<service.instance.id>` になる。

### Logs

状態遷移とエージェント自身の診断を送る。活動中のログは、その時点の root スパンの trace_id / span_id を持つ。
ログ本文は人が読める短文にし、機械処理用の値は属性に入れる。`event.name` 属性でイベントを識別する。

| `event.name` | 内容 |
|---|---|
| `activity.afk.start` | AFK 開始。`activity.afk.reason`、`activity.idle.seconds` |
| `activity.afk.end` | AFK 終了。`activity.afk.duration.seconds` |
| `activity.app.switch` | 前面アプリの切り替え。`activity.app.from`、`activity.app.name`、`activity.app.id` |
| `system.sleep` | スリープ検出（ポーリングの空白で判定）。`system.sleep.duration.seconds` |
| `activity.device.screen` | Android の画面オン/オフ・ロック解除。`activity.device.screen.state` |
| `device.location` | Android の位置（設定でオンにしたときだけ）。`geo.location.lat`、`geo.location.lon`、`activity.location.accuracy`（m）、`activity.location.altitude`、`activity.location.speed`、`activity.location.bearing`、`activity.location.provider`、`activity.location.source`（`periodic` / `passive`）、`activity.location.precision`。ログの時刻は測位時刻 |
| `agent.start` / `agent.stop` | エージェントの起動・停止 |
| `agent.permission` | 権限の状態。`agent.permission.name`、`agent.permission.granted` |
| `agent.diagnostic` | Warn 以上の内部ログ（送信失敗、AppleScript エラーなど） |

## プライバシールール

設定ファイルの `privacy.rules` を上から順にすべて評価し、マッチしたルールのアクションを重ねて適用する。
エージェントの中で適用するので、ローカルの拡張機能やフックは生のデータを送ってよい。

```yaml
privacy:
  rules:
    - match: { app: "1Password*" }        # app 名または app.id への glob
      drop: true                           # app/context スパンを作らない。active 時間には含める
    - match: { domain: "*.example-bank.co.jp" }
      drop: true
    - match: { app: "Slack" }
      mask_title: true                     # タイトルを "[redacted]" に置き換える
    - match: { title: "(?i)password|パスワード" }  # 正規表現
      mask_title: true
    - match: {}                            # 空の match はすべてにマッチ
      url: path                            # full | path（クエリとフラグメントを落とす）| domain | none
  terminal_command: name                   # full | name | none
```

`match` のキー: `app`（glob）、`title`（正規表現）、`domain`（glob）、`url`（正規表現）、`project`（glob）、`file`（正規表現）。複数キーは AND。

## ローカル ingest API

エージェントは `127.0.0.1:5610` で HTTP を受ける（設定 `ingest.listen`）。
すべて `POST`、`Content-Type: application/json`、応答は `204 No Content`。
任意で `time`（RFC 3339、ミリ秒以上の精度）を付けられる。省略時は受信時刻を使う。

不正な呼び出しを防ぐため、次の条件を満たさないリクエストは `403` にする。

- `Host` が `127.0.0.1:<port>` / `localhost:<port>` / `[::1]:<port>` のいずれか（DNS rebinding 対策）。
- `Origin` ヘッダーがある場合は `chrome-extension://` で始まる（Web ページからの送信を拒否する）。Node.js（VS Code）や curl は `Origin` を送らないので通る。

`GET /v1/status` は `{"name":"activitylog-agent","version":"..."}` を返す。拡張機能の疎通確認に使う。

### `POST /v1/browser`

タブの切り替え、URL やタイトルの変化、ウィンドウのフォーカス変化のたびに送る。
さらに、ブラウザがフォーカスを持っている間は 30 秒ごとに送る（ハートビート）。

```json
{
  "browser": "chrome",
  "instance": "b6a0f7c2-...",
  "focused": true,
  "url": "https://github.com/ymotongpoo/activitylog",
  "title": "ymotongpoo/activitylog",
  "incognito": false,
  "audible": false,
  "tab_id": 123,
  "window_id": 1
}
```

- `browser`: `chrome` / `edge` / `brave` / `arc` / `vivaldi` / `chromium` / `opera` / `other`。
- `instance`: 拡張機能のインストールごとのランダム ID（`chrome.storage.local` に保存）。
- `focused`: ブラウザのいずれかのウィンドウが OS のフォーカスを持っているか。`false` のときは `url` 以下を省略してよい。

エージェントは前面アプリがブラウザで、`focused: true` の報告が 90 秒以内にあればそれを採用する。
macOS では、拡張機能の報告がなければ AppleScript で取得する。

### `POST /v1/editor`

```json
{
  "editor": "vscode",
  "app_name": "Visual Studio Code",
  "instance": "a1b2c3...",
  "event": "heartbeat",
  "focused": true,
  "project": "activitylog",
  "project_path": "/Users/me/repos/activitylog",
  "file": "/Users/me/repos/activitylog/agent/main.go",
  "language": "go",
  "branch": "main",
  "repository": "https://github.com/ymotongpoo/activitylog.git",
  "pid": 12345
}
```

- `editor`: `vscode` / `vscode-insiders` / `cursor` / `windsurf` / `vscodium` / `neovim`。
- `instance`: VS Code は `vscode.env.sessionId`、Neovim は PID を文字列にしたもの。
- `event`: `focus` / `blur` / `open`（アクティブなファイルの変更）/ `edit` / `save` / `heartbeat`。
- `focused` が `true` の間は、変化がなくても 30 秒ごとに `heartbeat` を送る。`edit` は 2 秒に 1 回までに間引く。
- `pid` は Neovim のみ。

エージェントは前面アプリがエディタまたはターミナルで、`focused: true` の報告が 90 秒以内にあればそれを採用する。
同じエディタ種別の報告が複数あれば、最も新しいものを使う。

### `POST /v1/terminal`

シェルフックは HTTP を直接叩かず、エージェントのバイナリの `emit` サブコマンドを使う（JSON のエスケープをシェルで扱わないため）。

```sh
activitylog-agent emit terminal --event start --shell zsh --pid $$ --tty "$TTY" --cwd "$PWD" --command "$1"
activitylog-agent emit terminal --event end   --shell zsh --pid $$ --tty "$TTY" --cwd "$PWD" --exit-code $status
activitylog-agent emit terminal --event cwd   --shell zsh --pid $$ --tty "$TTY" --cwd "$PWD"
```

`emit` は呼び出し時刻を `time` に入れて次の JSON を POST する。タイムアウトは 1 秒で、失敗しても何も出力せず終了コード 0 で終わる。
接続先は設定の `ingest.listen`、または環境変数 `ACTIVITYLOG_ADDR`。

```json
{
  "event": "start",
  "shell": "zsh",
  "pid": 4321,
  "tty": "/dev/ttys003",
  "cwd": "/Users/me/repos/activitylog",
  "command": "go test ./...",
  "exit_code": 0,
  "term_program": "ghostty",
  "time": "2026-10-08T12:34:56.789+09:00"
}
```

`start` から同じ `pid` の `end` までを `terminal.command` スパンにする。
`end` が来ないまま同じ `pid` の `start` が来たら、前のコマンドをその時刻で閉じる（終了コード不明）。
フックは `emit` を切り離して起動するので、短いコマンドでは `end` が `start` より先に届くことがある。対応する `start` がない `end` は 5 秒間保持し、その間に届いた `start` と組にする。
ターミナルアプリが前面のときの context には、直近に報告のあったシェルの `cwd` を使う。

## GNOME Shell 拡張の D-Bus インターフェース

GNOME Wayland では外部プロセスから前面ウィンドウを取れないため、Shell 拡張が Shell の D-Bus 接続上にオブジェクトを公開する。

- 宛先: `org.gnome.Shell`
- オブジェクトパス: `/net/ymotongpoo/ActivityLog`
- インターフェース: `net.ymotongpoo.ActivityLog1`

```xml
<node>
  <interface name="net.ymotongpoo.ActivityLog1">
    <method name="GetFocusedWindow">
      <arg type="s" direction="out" name="json"/>
    </method>
    <property name="Version" type="u" access="read"/>
  </interface>
</node>
```

`GetFocusedWindow` は次の JSON 文字列を返す。フォーカスされたウィンドウがない（アクティビティ画面など）ときは `{}` を返す。

```json
{
  "title": "ymotongpoo/activitylog - Google Chrome",
  "wm_class": "Google-chrome",
  "wm_class_instance": "google-chrome",
  "pid": 2345,
  "app_id": "google-chrome",
  "app_name": "Google Chrome",
  "sandboxed_app_id": "",
  "fullscreen": false
}
```

`app_id` は `Shell.WindowTracker` が返す desktop ID から `.desktop` を除いたもの。取れなければ空文字。

アイドル時間は `org.gnome.Mutter.IdleMonitor`（`/org/gnome/Mutter/IdleMonitor/Core` の `GetIdletime`、ミリ秒）、
画面ロックは `org.gnome.ScreenSaver`（`/org/gnome/ScreenSaver` の `GetActive`）から、エージェントが直接取得する。

## Linux の端末モード

ウィンドウシステムのないマシン（SSH で使うサーバーなど）では、ウィンドウの代わりに端末を観測する。
GNOME Shell 拡張が応答しないとき（設定 `platform: auto` のデフォルト）、または `platform: terminal` のときにこのモードになる。

- ユーザーが所有する `/dev/pts/*` と `/dev/tty*` を列挙し、最終アクセス時刻（入力があると更新される。粒度は 8 秒程度）が最も新しいものを作業中の端末とする。ほぼ同時に入力がある端末が複数あれば、前面プロセスが tmux や screen でないもの（ペイン）を選ぶ。
- アイドル時間は、端末の最終アクセス時刻のうち最も新しいものからの経過時間。
- その端末の前面プロセスグループのリーダー（`/proc/<pid>/stat` の `tpgid`）をアプリとする。`activity.app.name` と `activity.app.id` はコマンド名、`activity.window.title` は「コマンド名 (端末名)」。
- context は `terminal`（source `proc`）で、作業ディレクトリ（`/proc/<pid>/cwd`）と端末名を `activity.terminal.cwd`、`activity.terminal.tty` に入れる。同じ端末のシェルフックの報告があればシェル名も入れる。前面プロセスが Neovim で、同じ PID の報告があれば `editor.file` にする。
- 端末が一つもなければ AFK とし、reason は `logged_out`。

## AFK とスリープの判定

- 1 秒ごとにアイドル時間（最後の入力からの経過）とロック状態を取る。
- アイドル時間が `afk_timeout`（既定 3 分）を超えたら、最後の入力時刻にさかのぼって active を閉じ、afk を始める。
- ロック中は即座に afk にする（reason `locked`）。
- アイドル時間が読めないサンプルでは、開いている区間を直前のサンプル時刻で閉じ、読めるようになるまでスパンもメトリクスも作らない。
- ポーリングの間隔が 30 秒を超えて空いたらスリープとみなし、直前のポーリング時刻ですべての区間を閉じ、空白を afk（reason `sleep`）にする。
- `afk.respect_idle_inhibitors`（既定 false）を有効にすると、動画再生や会議アプリがアイドル抑止を掛けている間はアイドル時間を 0 とみなす。macOS は IOKit の `PreventUserIdleDisplaySleep` アサーション、GNOME は `org.gnome.SessionManager.IsInhibited(8)` を見る。

## 送信とオフライン耐性

- デスクトップの送信先のデフォルトは、ローカルの Grafana Alloy や OpenTelemetry Collector の OTLP/HTTP レシーバー `http://localhost:4318`。`/v1/traces`、`/v1/metrics`、`/v1/logs` を付けて送る。Grafana Cloud への転送と認証はレシーバーが受け持つ。
- Grafana Cloud の OTLP ゲートウェイ（例 `https://otlp-gateway-prod-ap-northeast-0.grafana.net/otlp`）に直接送ることもできる。認証は `Authorization: Basic base64(<instance_id>:<token>)`。トークンはスコープ `traces:write`、`metrics:write`、`logs:write` を持つ Cloud Access Policy トークン。Android はこの直接送信だけを使う。
- デスクトップのトレースとログは protobuf を gzip して送る。送信に失敗（ネットワークエラー、429、5xx）したらリクエスト本体をディスクに退避し、30 秒ごとに古い順に再送する。退避領域の上限は既定 256 MiB で、超えたら古いものから捨てる。
- メトリクスは cumulative なので、送信に失敗しても次の送信で回復する。退避はしない。
- 開いている区間は 30 秒ごとにディスクにチェックポイントする。クラッシュや強制終了の後に起動したら、チェックポイント時刻で閉じて送る。
- Android は OTLP/HTTP JSON を gzip して送る。WorkManager の定期ジョブ（15 分）でイベントを集めて送り、失敗したら退避して次回に再送する。

## Android

- `UsageStatsManager.queryEvents` で前回のチェックポイント以降のイベントを読む。
  - `KEYGUARD_HIDDEN`（ロック解除）から `SCREEN_NON_INTERACTIVE` または `KEYGUARD_SHOWN` までを root `active` にする。
  - `ACTIVITY_RESUMED` のパッケージを前面アプリとし、別パッケージの `ACTIVITY_RESUMED` か active の終了までを app スパンにする。
- `AccessibilityService` はブラウザ（Chrome、Chrome Beta、Brave、Edge など）のアドレスバーのテキストを読み、時刻と一緒にアプリ内のキューに追記する。定期ジョブは app スパンの区間に入る観測を `browser.tab` スパンにする。
- カテゴリは `ApplicationInfo.category`（`game` / `audio` / `video` / `image` / `social` / `news` / `maps` / `productivity` / `accessibility`）から `Android/<category>` とする。
- プライバシー設定は、除外パッケージ、除外ドメイン、URL の扱い（`full` / `path` / `domain` / `none`）。
- 位置はデフォルトでは記録しない。設定でオンにすると、定期ジョブが毎回 LocationManager で現在地を 1 回取得し（Android 12 以降は fused プロバイダー）、加えてほかのアプリが要求した位置を passive プロバイダーで受け取る。どちらも `device.location` ログにする。定期ジョブで読むので「常に許可」の位置の権限が必要。座標は設定で小数点以下 3 桁（約 100 m）か 2 桁（約 1 km）に丸められる。スパンやメトリクスには位置を付けない。
- Play ストアには出さず、サイドロードで使う前提。
