# activitylog-agent

macOS と Linux（GNOME）で動く常駐エージェントです。
前面のアプリ、ウィンドウタイトル、アイドル時間、画面ロックを 1 秒ごとに読み、拡張機能やシェルフックからの報告と組み合わせて、OTLP/HTTP で送ります。
インストール手順はリポジトリ直下の [README](../README.md) にあります。

## コマンド

| コマンド | 内容 |
|---|---|
| `activitylog-agent run [-config path] [-v]` | エージェントを動かす（引数を省略したときもこれ） |
| `activitylog-agent doctor [-send-test] [-prompt]` | 設定、権限、現在のサンプル、起動状態、退避中のリクエスト数を表示する |
| `activitylog-agent service install\|uninstall\|restart\|status` | LaunchAgent（macOS）または systemd のユーザーユニット（Linux）を管理する。Homebrew で入れた場合は `brew services` を使う |
| `activitylog-agent emit terminal --event start\|end\|cwd ...` | シェルフックから呼ぶ。動いているエージェントにイベントを渡す |
| `activitylog-agent example-config` | 設定ファイルの例を出力する |
| `activitylog-agent version` | バージョンを表示する |

`doctor -send-test` はテスト用のログを 1 件送り、エンドポイントとトークンが正しいかを確かめます。
`doctor -prompt` は、未許可の権限について OS の許可ダイアログを出させます。
`service install` は設定を検査し、問題があれば登録しません（`-force` で無視できます）。
Homebrew で入れたバイナリでは `service install` は何もせず、`brew services start activitylog-agent` を案内します（二重に登録しないため）。

## 設定

設定ファイルの場所は、macOS では `~/Library/Application Support/activitylog/config.yaml`、Linux では `~/.config/activitylog/config.yaml` です。
項目の説明は [config.example.yaml](cmd/activitylog-agent/config.example.yaml) に書いてあります。
設定ファイルがなければデフォルトで動き、ローカルの Alloy や OpenTelemetry Collector の OTLP/HTTP レシーバー（`http://localhost:4318`）に送ります。

トークンは設定ファイルに直接書かず、`otlp.token_file` に置くか環境変数 `ACTIVITYLOG_OTLP_TOKEN` で渡せます。
`otlp.instance_id` とトークンの両方があれば `Authorization: Basic` を、トークンだけなら `Authorization: Bearer` を付けます。
認証が必要なレシーバーに送るなら、`instance_id` を空にして `otlp.headers` で認証ヘッダーを指定してください。
送信先は、環境変数 `ACTIVITYLOG_OTLP_ENDPOINT`、設定ファイルの `otlp.endpoint`、環境変数 `OTEL_EXPORTER_OTLP_ENDPOINT`、デフォルトの順で、最初に見つかったものを使います。

## 必要な権限

| OS | 権限 | 用途 | ないとき |
|---|---|---|---|
| macOS | アクセシビリティ | ウィンドウタイトル | アプリ名だけを記録する |
| macOS | オートメーション（ブラウザごと） | 拡張がないときのタブの URL | ウィンドウタイトルだけを記録する |
| Linux | GNOME Shell 拡張 `activitylog@ymotongpoo.net` | 前面ウィンドウ | アプリを記録できない |

macOS の許可は `activitylog-agent` バイナリに対して与えます。
バイナリには Info.plist（バンドル ID `net.ymotongpoo.activitylog.agent` とオートメーションの利用目的）を埋め込んであり、`make` は署名し直してそれを署名に結び付けます。
launchd から起動したときは許可がバイナリに対して求められますが、ターミナルから直接動かすとターミナルアプリに対して求められます。

## データの保存場所

macOS では `~/Library/Application Support/activitylog/`、Linux では `~/.local/state/activitylog/` に次のものを置きます。

- `spool/` には、送信に失敗したトレースとログのリクエストを退避します。30 秒ごとに古い順に再送し、合計が `spool.max_mib`（デフォルト 256 MiB）を超えたら古いものから捨てます。
- `checkpoint.json` には、開いている区間を 30 秒ごとに書き出します。クラッシュや強制終了の後に起動すると、書き出した時刻で区間を閉じて送ります（属性 `activity.recovered=true`）。

ログは macOS では `brew services` なら `$(brew --prefix)/var/log/activitylog-agent.log`、`service install` なら `~/Library/Logs/activitylog-agent.log`、Linux では `journalctl --user -u activitylog-agent` で読めます。
Warn 以上のログは `agent.diagnostic` イベントとして Loki にも送ります。

## 実装上の注意

AFK の判定では、最後の入力から `afk.timeout` が経った時点で、最後の入力時刻までさかのぼって active を閉じます。
最後の入力から現在までの時間は、戻ってくるか AFK と決まるまで、どちらのメトリクスにも加算しません。

ポーリングの間隔がスリープなどで 30 秒以上空いたら、直前のポーリング時刻ですべての区間を閉じ、空白を `afk`（reason `sleep`）のスパンにします。
スリープの時間はメトリクスの AFK 時間には含めません。
Go のモノトニック時計はスリープ中に進まないので、この判定には壁時計を使っています。

新しいラベルの組（初めて開いたアプリやドメイン）のメトリクスは、最初に 0 を送り、実際の値は次の送信まで保留します。
Prometheus の `increase()` は新しい系列の最初のサンプルを数えないため、こうしないと短い初回の訪問がダッシュボードに出ません。

## 開発

```sh
make test     # go test ./...
make vet      # macOS と Linux の両方で go vet
make build    # build/activitylog-agent
make install  # ~/.local/bin に置いて service install する
make release-macos VERSION=x.y.z   # darwin-arm64（Apple シリコン）の tar.gz
make release-linux VERSION=x.y.z   # linux-amd64 と linux-arm64 の tar.gz
```

`v*` のタグを push すると、GitHub Actions がリリースを作ります。
リポジトリのシークレット `HOMEBREW_TAP_TOKEN`（`ymotongpoo/homebrew-macos` への contents と pull requests の書き込み権限を持つ fine-grained token）があれば、Formula を更新するプルリクエストも作ります。

Linux 版は cgo を使わないので、macOS から `GOOS=linux go build ./cmd/activitylog-agent` でクロスビルドできます。
