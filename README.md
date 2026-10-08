# activitylog

[ActivityWatch](https://activitywatch.net/) と同種のアクティビティを macOS、Linux（GNOME）、Android で収集し、OTLP で Grafana Cloud に直接送るアプリ一式です。
GUI は持たず、可視化は Grafana で行います。

収集するのは次のデータです。

- 前面のアプリとウィンドウタイトル
- AFK（離席）、画面ロック、スリープ
- Chromium 系ブラウザのアクティブなタブ（タイトル、URL、シークレットウィンドウか、音声再生中か）
- VS Code 系エディタと Neovim で開いているプロジェクト、ファイル、言語、Git ブランチ
- シェルのカレントディレクトリと実行したコマンド
- Android の前面アプリ、画面のオンとオフ、ロック解除、Chrome の URL

## テレメトリーの形

区間（いつからいつまで何をしていたか）はトレースとして送ります。
離席していない連続区間を 1 本のトレースとし、その下にアプリのスパン、さらにその下にタブやファイルのスパンを置くので、Grafana のトレースビューで一日の作業をタイムラインとして見られます。

| シグナル | 送り先 | 内容 |
|---|---|---|
| Traces | Tempo | `active` / `afk` のセッション、アプリ、タブ・ファイル・cwd、シェルのコマンド |
| Metrics | Mimir | 状態別、アプリ別、カテゴリ別、ドメイン別、プロジェクト別の累積時間と、コマンドの実行回数 |
| Logs | Loki | AFK の開始と終了、アプリの切り替え、スリープ、エージェントの起動と停止、権限の状態、エラー |

スパンは区間が閉じた時点で送られるため、今まさに続いている区間はトレースには現れません。
リアルタイムの状況と長期の集計にはメトリクスを使います（メトリクスは 60 秒ごとに送ります）。
属性名とメトリクス名の一覧は [docs/design.md](docs/design.md) にあります。

## 構成

| ディレクトリ | 内容 | 対象 |
|---|---|---|
| [`agent/`](agent/) | 常駐エージェント（Go） | macOS、Linux |
| [`extensions/chrome/`](extensions/chrome/) | ブラウザ拡張（Manifest V3） | Chrome、Edge、Brave、Arc、Vivaldi |
| [`extensions/vscode/`](extensions/vscode/) | エディタ拡張 | VS Code、Cursor、Windsurf、VSCodium |
| [`extensions/nvim/`](extensions/nvim/) | Neovim プラグイン | Neovim 0.10 以降 |
| [`extensions/shell/`](extensions/shell/) | シェルフック | zsh、bash |
| [`extensions/gnome-shell/`](extensions/gnome-shell/) | 前面ウィンドウを D-Bus で公開する Shell 拡張 | GNOME 45 以降 |
| [`android/`](android/) | Android アプリ（Kotlin） | Android 10 以降 |
| [`dashboards/`](dashboards/) | Grafana ダッシュボード | Grafana Cloud |

デスクトップでは、拡張機能やフックがローカルのエージェント（`127.0.0.1:5610`）に生のデータを送り、エージェントがプライバシールールを適用してから Grafana Cloud に送ります。
Android アプリはエージェントを介さず、端末から直接 Grafana Cloud に送ります。

## Grafana Cloud の準備

1. Grafana Cloud のポータルでスタックを開き、OpenTelemetry の Configure から OTLP エンドポイント（`https://otlp-gateway-<region>.grafana.net/otlp`）とインスタンス ID を控えます。
2. Cloud Access Policy を作り、スコープ `traces:write`、`metrics:write`、`logs:write` を付けたトークンを発行します。

デスクトップと Android で同じトークンを使えます。

## macOS

Homebrew の Cask で入れられます。

```sh
brew tap ymotongpoo/macos
brew install --cask activitylog-agent

mkdir -p ~/Library/Application\ Support/activitylog
activitylog-agent example-config > ~/Library/Application\ Support/activitylog/config.yaml
# config.yaml の otlp.endpoint と otlp.instance_id を編集し、トークンを token_file のパスに保存する
activitylog-agent service install
```

Cask は `ActivityLogAgent.app` を `/Applications` に置き、`activitylog-agent` コマンドをパスに通します。
`activitylog-agent service install` は設定を検査してから LaunchAgent を登録し、エージェントを起動します。
`brew uninstall --cask activitylog-agent` で LaunchAgent も取り除かれます。

初回起動時にアクセシビリティの許可を求められるので、システム設定のプライバシーとセキュリティで許可してください。
ウィンドウタイトルの取得に必要です。

ブラウザ拡張を入れていない場合は、AppleScript でタブの URL を取得します。
このとき、ブラウザごとにオートメーションの許可ダイアログが出ます。
拡張を入れたほうが取得の遅れがなく、音声再生中かどうかも分かります。

配布している .app は Apple の公証を受けていないアドホック署名です。
Cask はインストール時に quarantine 属性を外すので Gatekeeper の警告は出ませんが、macOS はバージョンごとに別のアプリとみなします。
そのため `brew upgrade` の後は、アクセシビリティとオートメーションの許可を付け直してください（古い項目はシステム設定から削除して構いません）。

ソースから入れる場合は次のとおりです。

```sh
cd agent
make install-macos   # ~/Applications/ActivityLogAgent.app を作り、service install まで行う
```

キーチェーンにコード署名用の証明書を作り、`make install-macos SIGN_IDENTITY="証明書名"` とすると、許可が再ビルド後も残ります。

## Linux（GNOME）

[Releases](https://github.com/ymotongpoo/activitylog/releases) から `activitylog-agent-<version>-linux-<arch>.tar.gz` と `activitylog@ymotongpoo.net.shell-extension.zip` を取得します。

```sh
gnome-extensions install activitylog@ymotongpoo.net.shell-extension.zip
gnome-extensions enable activitylog@ymotongpoo.net   # Wayland では先にログアウトしてログインし直す

tar xzf activitylog-agent-<version>-linux-amd64.tar.gz
install -Dm755 activitylog-agent-<version>-linux-amd64/activitylog-agent ~/.local/bin/activitylog-agent
mkdir -p ~/.config/activitylog
activitylog-agent example-config > ~/.config/activitylog/config.yaml
# config.yaml を編集する
activitylog-agent service install   # systemd のユーザーユニットを登録して起動する
```

ソースから入れる場合は `extensions/gnome-shell/install.sh` と `make -C agent install-linux` を使います。

GNOME の Wayland セッションでは、外部プロセスから前面ウィンドウを取得できません。
そのため Shell 拡張が前面ウィンドウを D-Bus で公開し、エージェントがそれを 1 秒ごとに読みます。
URL はウィンドウタイトルに含まれないので、Linux でタブの URL を取るにはブラウザ拡張が必要です。

## 拡張機能とフック

各ディレクトリの README を参照してください。
どれもエージェントが動いていなければ黙って何もしないので、入れる順番は問いません。
Chrome 拡張（`activitylog-chrome-extension-<version>.zip`、展開してから「パッケージ化されていない拡張機能を読み込む」で読み込む）と VS Code 拡張（`activitylog-vscode-<version>.vsix`）は Releases にも置いています。

```sh
# ~/.zshrc
source /path/to/activitylog/extensions/shell/activitylog.zsh
```

## Android

[android/README.md](android/README.md) を参照してください。
使用状況へのアクセスとユーザー補助（Chrome の URL 取得用）の許可が必要で、Play ストアには出さずサイドロードで使う前提です。
APK は署名鍵を管理していないため Releases には置いておらず、手元でビルドしてインストールします。

## 動作確認

```sh
activitylog-agent doctor -send-test
```

設定、権限、現在の前面アプリ、エージェントの起動状態、退避中のリクエスト数を表示し、テスト用のログを 1 件送ります。
Grafana の Explore で Loki に `{service_namespace="activitylog"} | event_name="agent.doctor"` と問い合わせると、届いたかどうか確認できます。

ダッシュボードは [dashboards/activitylog.json](dashboards/activitylog.json) をインポートしてください。

## プライバシー

ウィンドウタイトル、URL、ファイルパス、コマンドには機微な情報が含まれます。
エージェントの設定 `privacy.rules` で、アプリやドメインごとに記録しない、タイトルを伏せる、URL をドメインまでに削る、といった処理を指定できます。
シェルのコマンドは、デフォルトでは先頭のコマンド名だけを送ります。
Android アプリにも、除外するパッケージとドメイン、URL の扱いの設定があります。

## ライセンス

[Apache License 2.0](LICENSE)
