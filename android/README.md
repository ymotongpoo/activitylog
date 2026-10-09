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

## 署名付きの release ビルドと配布

普段使いの端末には、自分の鍵で署名した release ビルドを Firebase App Distribution で配る。
同じ鍵で署名し続けるかぎり、新しいバージョンは既存のインストールの上に更新として入る。

```sh
android/distribute.sh            # 最新のタグ（v0.5.0 なら 0.5.0）でビルドして配布する
android/distribute.sh 0.5.1 "変更点のメモ"
```

`distribute.sh` は次のものを使う。

| 項目 | デフォルト | 上書き |
|---|---|---|
| 署名鍵 | `~/.android/activitylog-release.jks`（エイリアス `activitylog`） | `ACTIVITYLOG_KEYSTORE`、`ACTIVITYLOG_KEY_ALIAS` |
| 鍵のパスワード | macOS のキーチェーン項目 `net.ymotongpoo.activitylog.keystore`（アカウント `activitylog-release`） | `ACTIVITYLOG_KEYSTORE_PASSWORD` |
| Firebase のアプリ ID と配布先グループ | `android/distribution.env`（コミットしない。`distribution.env.example` をコピーして作る） | 環境変数 `FIREBASE_APP_ID`、`FIREBASE_GROUPS` |

Firebase CLI は、プロジェクトにアクセスできるアカウントでログインしておく（`firebase login`）。
`versionCode` はバージョンから決まり、x.y.z は x×10000 + y×100 + z になる。
更新として入れるには、前回より大きいバージョンを付ける。

署名鍵をなくすと、既存のインストールを更新できず、アンインストールして入れ直すことになる（端末に溜まった未送信のデータも消える）。
`~/.android/activitylog-release.jks` とキーチェーンのパスワードは、別の場所にもバックアップしておく。
debug ビルドとは署名が違うので、debug ビルドを入れている端末では一度アンインストールしてから release ビルドを入れる。

署名鍵の環境変数がなければ、release ビルドは署名なしで作られる（CI はこの状態でビルドを確かめている）。

## インストール

端末の開発者向けオプションで USB デバッグを有効にし、PC につないで次を実行する。

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 権限の付与

アプリを開き、設定画面のボタンからそれぞれの設定画面に移動する。画面の「Permissions」欄に現在の状態が出る。

先に Grafana Cloud の接続先（後述）を入力して「Save」を押しておく。
設定画面に移動すると Android がアプリを終了させることがあるが、入力した値は画面を離れるときに保存される。

1. 使用状況へのアクセス（Usage Access）: 「Open Usage Access settings」→ activitylog → 「使用状況へのアクセスを許可」をオンにする。これがないと何も収集しない。
2. ユーザー補助（Accessibility）: 「Open Accessibility settings」→「activitylog browser URL capture」をオンにする。ブラウザの URL を取らないなら不要。

Android 13 以降では、ストア以外（Firebase App Tester や adb を含む）から入れたアプリは、使用状況へのアクセスとユーザー補助を「制限付き設定」としてオンにできない。
オンにしようとして「アプリはアクセスを拒否されました」と出たら、次の手順で許可する。

1. ダイアログを閉じ、「Open app info」でアプリ情報を開く。
2. 右上のメニュー（⋮）から「制限付き設定を許可」を選び、指紋や PIN で確認する。このメニューは、一度オンにしようとして拒否された後にだけ表示される。
3. 使用状況へのアクセスとユーザー補助の画面に戻って、もう一度オンにする。

端末の「高度な保護機能」がオンのときは、制限付き設定そのものを許可できない。

バッテリーは、「Open app info」→ バッテリー →「制限なし」にしておくと、定期ジョブが遅れにくい。

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

## 位置の記録

設定画面の「Location」で「Record location」をオンにすると、移動の記録として位置を `device.location` ログで送る。デフォルトはオフ。

- 15 分ごとの定期ジョブで現在地を 1 回取得する。加えて、地図アプリなどほかのアプリが位置を要求したときの位置も受け取る（passive。電池をほとんど使わない）。
- 定期ジョブはバックグラウンドで動くので、位置の権限は「常に許可」が必要。「Grant location (all the time)」を押すと、まず「アプリの使用中のみ」を許可するダイアログが出て、続いて権限の設定画面が開くので「常に許可」を選ぶ。
- 座標は「Coordinate precision」で丸められる（as reported、約 100 m、約 1 km）。
- 送る属性は `geo.location.lat`、`geo.location.lon`、`activity.location.accuracy`（m）などで、ログの時刻は測位した時刻。スパンやメトリクスには付けない。
- ダッシュボードの「Locations (Android)」パネルで地図に表示できる。

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
