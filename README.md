# activitylog

[ActivityWatch](https://activitywatch.net/) と同種のアクティビティを macOS、Linux（GNOME のデスクトップと、ウィンドウシステムのないサーバー）、Android で収集し、OTLP で Grafana Cloud に送るアプリ一式です。
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

デスクトップでは、拡張機能やフックがローカルのエージェント（`127.0.0.1:5610`）に生のデータを送り、エージェントがプライバシールールを適用してから、ローカルの OTLP レシーバー（Alloy や OpenTelemetry Collector）経由で Grafana Cloud に送ります。
Android アプリはエージェントを介さず、端末から直接 Grafana Cloud に送ります。

## 送信先

エージェントは、デフォルトでは同じマシンで動く Grafana Alloy や OpenTelemetry Collector の OTLP/HTTP レシーバー（`http://localhost:4318`）に送ります。
設定ファイルがなくても、このデフォルトで動きます。
Grafana Cloud などへの転送と認証はレシーバー側に任せる構成です。

Alloy から Grafana Cloud に転送する設定の例です。
OTLP エンドポイントとインスタンス ID は、Grafana Cloud のポータルでスタックの OpenTelemetry の Configure から確認できます。
トークンは、スコープ `traces:write`、`metrics:write`、`logs:write` を付けた Cloud Access Policy で発行します。

```alloy
otelcol.receiver.otlp "default" {
  http { }

  output {
    metrics = [otelcol.processor.batch.default.input]
    logs    = [otelcol.processor.batch.default.input]
    traces  = [otelcol.processor.batch.default.input]
  }
}

otelcol.processor.batch "default" {
  output {
    metrics = [otelcol.exporter.otlphttp.grafana_cloud.input]
    logs    = [otelcol.exporter.otlphttp.grafana_cloud.input]
    traces  = [otelcol.exporter.otlphttp.grafana_cloud.input]
  }
}

otelcol.auth.basic "grafana_cloud" {
  username = sys.env("GRAFANA_CLOUD_INSTANCE_ID")
  password = sys.env("GRAFANA_CLOUD_TOKEN")
}

otelcol.exporter.otlphttp "grafana_cloud" {
  client {
    endpoint = "https://otlp-gateway-prod-ap-northeast-0.grafana.net/otlp"
    auth     = otelcol.auth.basic.grafana_cloud.handler
  }
}
```

レシーバーを置かずに Grafana Cloud へ直接送ることもできます。
その場合は設定ファイルの `otlp.endpoint` に OTLP ゲートウェイを、`otlp.instance_id` と `otlp.token_file` にインスタンス ID とトークンを書きます（[config.example.yaml](agent/cmd/activitylog-agent/config.example.yaml) のコメントを参照）。
Android アプリは端末にレシーバーがない前提なので、アプリの設定画面で Grafana Cloud の OTLP ゲートウェイとトークンを指定します。

## macOS

Homebrew で入れて、`brew services` で常駐させます。
配布バイナリは Apple シリコン（arm64）向けだけです。
Intel Mac ではソースからビルドしてください。

```sh
brew install ymotongpoo/macos/activitylog-agent
activitylog-agent doctor          # 設定、権限、レシーバーへの疎通を確かめる
brew services start activitylog-agent
```

デフォルトから変えたい設定があるときは、先に設定ファイルを作ります。

```sh
mkdir -p ~/Library/Application\ Support/activitylog
activitylog-agent example-config > ~/Library/Application\ Support/activitylog/config.yaml
```

エージェントは GUI を持たない常駐プロセスで、launchd がログイン時に起動します。
ログは `$(brew --prefix)/var/log/activitylog-agent.log` に出ます。

初回起動時にアクセシビリティの許可を求められるので、システム設定のプライバシーとセキュリティ（macOS 27 では「デバイスの制御とデータへのアクセス」）で `activitylog-agent` を許可してください。
ウィンドウタイトルの取得に必要です。

ブラウザ拡張を入れていない場合は、AppleScript でタブの URL を取得します。
このとき、ブラウザごとにオートメーションの許可ダイアログが出ます。
ダイアログは、ブラウザが起動していればエージェントの起動時に、そうでなければ最初にタブを読むときに出ます。
拡張を入れたほうが取得の遅れがなく、音声再生中かどうかも分かります。

配布しているバイナリは Apple の公証を受けていないアドホック署名です。
macOS はアドホック署名のバイナリをハッシュで識別するので、`brew upgrade` の後は別のアプリとして扱われ、許可は引き継がれません。
アップグレード後にエージェントが起動すると、アクセシビリティの一覧に `activitylog-agent` の項目がオフの状態で 1 つ増えます。
その項目をオンにし、古い項目（前のバージョンのもの）は「−」で削除してください。
どちらが新しいか分からないときは、`activitylog-agent doctor` で `permission: accessibility` が ok になる方を残します。
オートメーションの許可はダイアログで付け直します。

ソースから入れる場合は、`make -C agent install` で `~/.local/bin` に置いて LaunchAgent を登録します。
キーチェーンにコード署名用の証明書を作り、`make -C agent install SIGN_IDENTITY="証明書名"` とすると、許可が再ビルド後も残ります。

## Linux

Debian と Ubuntu 向けに、apt リポジトリ（amd64、arm64）を GitHub Pages で公開しています。

```sh
sudo curl -fsSL https://ymotongpoo.github.io/activitylog/apt/activitylog.gpg \
  -o /usr/share/keyrings/activitylog.gpg
echo "deb [signed-by=/usr/share/keyrings/activitylog.gpg] https://ymotongpoo.github.io/activitylog/apt stable main" \
  | sudo tee /etc/apt/sources.list.d/activitylog.list
sudo apt update
sudo apt install activitylog-agent

systemctl --user enable --now activitylog-agent   # 記録したいユーザーごとに一度だけ
activitylog-agent doctor                          # 動作モード、取得できる項目、レシーバーへの疎通を確かめる
```

パッケージには、エージェント本体、systemd のユーザーユニット、GNOME Shell 拡張、シェルフック（`/usr/share/activitylog/shell/`）、Neovim プラグイン（`/usr/share/activitylog/nvim/`）が入っています。
ユーザーユニットは、ユーザーが自分で有効にするまで動きません。
共有のマシンに入れても、他のユーザーの操作は記録されません。

エージェントは、GNOME Shell 拡張が応答すればデスクトップのモード（`gnome`）で、応答しなければ端末のモード（`terminal`）で動きます。
どちらで動いているかは `activitylog-agent doctor` の `mode:` で確かめられます。
ログは `journalctl --user -u activitylog-agent -f` で読めます。
設定ファイルは `~/.config/activitylog/config.yaml` です（`activitylog-agent example-config` で例を出力できます）。
リポジトリの署名鍵のフィンガープリントは `E68A 9BD2 C770 AABE 0EE9 C753 9D51 85DC 1ADD 374F` です。

### GNOME のデスクトップ

GNOME の Wayland セッションでは、外部プロセスから前面ウィンドウを取得できません。
そのため Shell 拡張が前面ウィンドウを D-Bus で公開し、エージェントがそれを 1 秒ごとに読みます。
インストール後に一度ログアウトしてログインし直し、Shell 拡張を有効にしてください。

```sh
gnome-extensions enable activitylog@ymotongpoo.net
systemctl --user restart activitylog-agent
```

URL はウィンドウタイトルに含まれないので、Linux でタブの URL を取るにはブラウザ拡張が必要です。

### ウィンドウシステムのないサーバー

Ubuntu Server のように SSH や tty で使うマシンでは、ウィンドウの代わりに端末を記録します。

| 記録する内容 | 取り方 |
|---|---|
| アプリ | 直近に入力があった端末で、前面で動いているコマンド（`nvim`、`htop`、`zsh` など） |
| 文脈 | そのコマンドの作業ディレクトリと端末名（`activity.terminal.cwd`、`activity.terminal.tty`）。Neovim ではファイルとプロジェクト |
| アイドル時間 | 自分の端末すべてで、最後に入力があってからの時間（`w` コマンドの IDLE と同じ） |
| AFK | アイドル時間が `afk.timeout` を超えたとき。端末が一つもないときは reason `logged_out` |

tmux や screen の中で作業していても、入力のあったペインの端末を記録します。
ユーザーの systemd はログインしている間だけ動くので、エージェントも SSH でログインしている間だけ動きます。
ログアウト中も動かし続けるなら `loginctl enable-linger` を使います。
コマンドの開始と終了、シェルのカレントディレクトリも記録するなら、`~/.zshrc` か `~/.bashrc` で `/usr/share/activitylog/shell/` のフックを読み込みます。

### apt を使わない場合

[Releases](https://github.com/ymotongpoo/activitylog/releases) の `.deb` を `sudo apt install ./activitylog-agent_<version>_amd64.deb` で入れるか、tar.gz のバイナリを `~/.local/bin` に置いて `activitylog-agent service install` を実行します（GNOME の Shell 拡張は `activitylog@ymotongpoo.net.shell-extension.zip` を `gnome-extensions install` で入れます）。
ソースから入れる場合は `extensions/gnome-shell/install.sh` と `make -C agent install` を使います。

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
APK は Releases には置いていません。手元でビルドしてインストールするか、自分の鍵で署名して Firebase App Distribution で配ります（[android/README.md](android/README.md) の「署名付きの release ビルドと配布」）。

## 動作確認

```sh
activitylog-agent doctor -send-test
```

設定、権限、現在の前面アプリ、エージェントの起動状態、退避中のリクエスト数を表示し、テスト用のログを 1 件送ります。
ローカルのレシーバーに送る構成では、レシーバーの先（Grafana Cloud など）まで届いたかを Grafana 側で確かめてください。
Grafana の Explore で Loki に `{service_namespace="activitylog"} | event_name="agent.doctor"` と問い合わせると、届いたかどうか確認できます。

ダッシュボードは [dashboards/activitylog.json](dashboards/activitylog.json) をインポートしてください。OS ごとに見るなら [android.json](dashboards/android.json)、[linux.json](dashboards/linux.json)、[macos.json](dashboards/macos.json) も使えます。

## プライバシー

ウィンドウタイトル、URL、ファイルパス、コマンドには機微な情報が含まれます。
エージェントの設定 `privacy.rules` で、アプリやドメインごとに記録しない、タイトルを伏せる、URL をドメインまでに削る、といった処理を指定できます。
シェルのコマンドは、デフォルトでは先頭のコマンド名だけを送ります。
Android アプリにも、除外するパッケージとドメイン、URL の扱いの設定があります。

## ライセンス

[Apache License 2.0](LICENSE)
