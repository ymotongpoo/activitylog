# activitylog Android アプリ

Android 端末の利用状況（ロック解除から画面オフまでの活動区間、前面アプリ、Chromium 系ブラウザで開いている URL）を集め、OTLP/HTTP（JSON + gzip）で Grafana Cloud に直接送るアプリ。
Play ストアには出さず、サイドロードで使う。
テレメトリーのモデル（スパンの階層、属性名、メトリクス名、ログのイベント名）は [`docs/design.md`](../docs/design.md) に従う。

## 仕組み

- WorkManager の定期ジョブが 15 分ごとに動き、前回のチェックポイントから現在までの `UsageStatsManager` のイベントを読む。
  - root スパン `active`: `KEYGUARD_HIDDEN`（ロック解除）から `SCREEN_NON_INTERACTIVE` または `KEYGUARD_SHOWN` まで。ロック画面のない端末では `SCREEN_INTERACTIVE` から始める。1 時間を超えると分割し、新しいトレースを始めて前の root へのスパンリンクを付ける。
  - app スパン: `ACTIVITY_RESUMED` のパッケージごと。別パッケージの `ACTIVITY_RESUMED` か root の終了まで。
  - `browser.tab` スパン: ユーザー補助サービスが記録したアドレスバーの文字列のうち、ブラウザの app スパンに入るもの。
  - 実行の終わりにまだ閉じていない区間は、トレース ID・スパン ID・開始時刻ごと端末に保存して次回に引き継ぐ。途中で送ることはしない。
- メトリクス（`activity.time`、`activity.app.time`、`activity.category.time`、`activity.browser.domain.time`、`activity.device.unlocks`）は cumulative の Sum。合計値と開始時刻を端末に保存するので、プロセスが殺されても値は増え続ける。開いている区間は「どこまで加算したか」を覚えておき、二重に数えない。
- ログは `activity.device.screen`（画面オン/オフ、ロック、ロック解除）、`activity.app.switch`、`agent.start`、`agent.permission`、`agent.diagnostic`。
- 送るデータはいったんアプリ内の退避領域（上限 32 MiB、超えたら古いものから捨てる）に書き、古い順に送る。ネットワークエラー、429、5xx のときは残して次回に再送する。ネットワークが戻った時点で送るよう、接続を条件にした再送ジョブも登録する。それ以外の 4xx は捨て、ステータスと応答本文を設定画面の診断欄に出す。
- 収集自体はネットワークを必要としない。オフラインの間も 15 分ごとに収集して退避領域にためる。

## ビルド

JDK 21 と Android SDK（platform 36、build-tools 35 以上）が必要。

```sh
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # macOS + Homebrew の例
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # ANDROID_HOME を設定していれば不要
./gradlew assembleDebug testDebugUnitTest
```

APK は `app/build/outputs/apk/debug/app-debug.apk` にできる。
release ビルドには署名設定を入れていないので、普段使いでも debug ビルドでよい。

## インストール

端末の開発者向けオプションで USB デバッグを有効にし、PC につないで次を実行する。

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 権限の付与

アプリを開き、設定画面のボタンからそれぞれの設定画面に移動する。画面の「Permissions」欄に現在の状態が出る。

1. 使用状況へのアクセス（Usage Access）: 「Open Usage Access settings」→ activitylog → 「使用状況へのアクセスを許可」をオンにする。これがないと何も収集しない。
2. ユーザー補助（Accessibility）: 「Open Accessibility settings」→「activitylog browser URL capture」をオンにする。ブラウザの URL を取らないなら不要。
   - Android 13 以降は、インストール方法によってはサイドロードしたアプリのユーザー補助が「制限付き設定」としてオンにできない。その場合は「Open app info」→ 右上のメニュー →「制限付き設定を許可」を選んでから、もう一度オンにする。
3. バッテリー: 「Open app info」→ バッテリー →「制限なし」にしておくと、定期ジョブが遅れにくい。

## Grafana Cloud の設定

1. [Grafana Cloud Portal](https://grafana.com/profile/org) でスタックを選び、「OpenTelemetry」の「Configure」を開く（Grafana の「Connections」→「Add new connection」→「OpenTelemetry (OTLP)」からも開ける）。
2. そこに表示される値をアプリに入れる。
   - **OTLP endpoint**: `https://otlp-gateway-prod-<リージョン>.grafana.net/otlp` の形の URL（例 `https://otlp-gateway-prod-ap-northeast-0.grafana.net/otlp`）。アプリが `/v1/traces`、`/v1/metrics`、`/v1/logs` を付けて送る。
   - **Instance ID**: 同じ画面に出る数字の ID。
3. **Access policy token**: 同じ画面でトークンを作るか、「Administration」→「Users and access」→「Cloud access policies」でアクセスポリシーを作り、トークンを追加する。スコープは `traces:write`、`metrics:write`、`logs:write` の 3 つが必要。
4. 「Save」を押し、「Send now」で収集と送信を即時に実行する。結果は「Status」欄に出る。

トークンは AndroidKeyStore に置いた AES-GCM の鍵で暗号化して保存する。保存後はトークン欄が空に戻り、空のまま保存すれば前のトークンを使い続ける。
アプリのデータはバックアップや端末間の移行の対象外にしてある。

デバイス名（`service.instance.id` と `host.name`）の既定値は端末名（設定 → デバイス情報 → デバイス名）で、取れなければ機種名になる。設定画面で上書きできる。

## プライバシー設定

- **Excluded apps**: パッケージ名またはアプリ名への glob（`*` と `?`、大文字小文字は区別しない）。1 行に 1 つ。マッチしたアプリは app スパンと `browser.tab` スパンを作らず、アプリ別・カテゴリ別の時間にも入れない。`activity.time` には含める。
- **Excluded domains**: ドメインへの glob（例 `*.example-bank.co.jp`。この例は `example-bank.co.jp` そのものにはマッチしない）。マッチした URL は `browser.tab` スパンもドメイン別の時間も作らない。
- **URL in url.full**: `full`（そのまま）、`path`（クエリとフラグメントを落とす。既定）、`domain`（スキームとホストだけ）、`none`（`url.full` と `url.path` を付けない）。`url.domain` と `url.scheme` はどのモードでも付ける。

## 注意点

- URL の取得は Chrome 系ブラウザのアドレスバーのビュー ID（`<パッケージ名>:id/url_bar`、予備として `location_bar_status`）に頼っている。ブラウザの更新で ID が変わると取れなくなる。対象は Chrome（Stable / Beta / Dev）、Brave、Edge、Vivaldi、Chromium。
- 最近の Chrome はアドレスバーにドメインしか表示しないことがあり、その場合は記録される URL もドメインだけになる（パスは `/`）。タブのタイトル、シークレットモード、音声再生の有無は取れない。
- アドレスバーを編集している間（フォーカスがある間）の文字列は記録しない。
- Android はメモリが足りないときやメーカー独自の省電力機能でユーザー補助サービスを止めることがある。止まると設定画面の Accessibility が「NOT granted」になり、`agent.permission` ログが送られるので、オンにし直す。
- 定期ジョブは Doze などで遅れることがある。イベントはチェックポイントからまとめて読むので遅れてもデータは欠けないが、端末が保持するイベントの履歴（数日程度）より長く止まると、その分は失われる。
- 初回の実行では 1 時間前までさかのぼってイベントを読む。
- デスクトップ版と違い、AFK（`afk` root）は作らない。画面オフとロックの間は単に `active` の外になる。
- 退避した古いデータは、Grafana Cloud 側の受け入れ期限（古すぎるサンプルやログは拒否される）を過ぎると 4xx で捨てられる。
- 送信先は HTTPS のみ（Android の既定で平文 HTTP は使えない）。
