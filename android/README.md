# activitylog Android アプリ

Enduro 3のBody Battery、ストレス、心拍数、SpO₂、呼吸数、当日歩数、時計の電池残量、ソーラー強度も、設定画面の「Garmin health」から受信できます。
時計アプリを一度開くと、アプリを閉じた後も5分ごとに自動送信します。
受信した値は本体のOTLP設定を使って送ります。
[時計アプリの導入手順とメトリクス名](../garmin/README.md)を参照してください。

Android 端末の利用状況（ロック解除から画面オフまでの活動区間、前面アプリ、Chromium 系ブラウザで開いている URL）を集め、OTLP/HTTP（JSON + gzip）で Grafana Cloud に直接送るアプリ。
Play ストアには出さず、サイドロードで使う。
テレメトリーのモデル（スパンの階層、属性名、メトリクス名、ログのイベント名）は [`docs/design.md`](../docs/design.md) に従う。


## Health Connectの睡眠段階

Android v0.6.10から、Garmin ConnectがHealth Connectへ書いた睡眠セッションを読み取れます。
時計アプリの更新は不要です。

1. Androidの設定で「ヘルスコネクト」を開き、Garmin Connectによる睡眠データの書き込みを許可して、時計を同期します。
2. Activitylogの「Sleep records」から「Enable sleep import / review access」を選び、睡眠の読み取りを許可します。
3. 自動取得する場合はバックグラウンドの読み取りも許可します。対応していない端末や許可しない場合は、画面を開いて「Import now」を選びます。
4. 受信済みの睡眠開始・終了、段階ごとの時間と区間、送信状態を同じ画面で確認できます。

自動取得はWorkManagerの約15分周期で、実行時刻はAndroidが調整します。
初回と各同期で直近28日分を照合し、変更のない記録は再送しません。
睡眠終了とGarminの同期後に反映されるため、リアルタイムの睡眠検出ではありません。
浅い睡眠、深い睡眠、REM、覚醒、未分類の睡眠、離床、ベッド内の覚醒、不明を区別します。
段階が欠けた区間は不明として残し、セッション外の状態は推定しません。

睡眠は`event.name=sleep.session`のOTLPログとして、Activitylogに設定済みの送信先へ送ります。
ログ時刻は取り込み時刻であり、元の睡眠時刻と全段階の区間をJSON本文へ保持する構成です。
過去の記録が訂正された場合は同じセッションIDで新しい内容を送ります。
完全な読み取りで対象期間内の記録が消えた場合も、削除通知を送る仕組みです。
これはクラウドの既存ログを物理削除する操作ではなく、ダッシュボード上の最新状態から除外するための通知です。
クラウドに送信済みの履歴は送信先の保持設定に従います。

データは端末内のアプリ専用領域へ保存します。
元のHealth Connect記録IDはハッシュ化し、睡眠のタイトルと自由記述のメモは収集しません。
「Stop sleep import」で今後の読み取りを停止できます。Health Connectへの書き込みや削除は行いません。
オフライン時は本体共通の32 MiBの送信待ち領域へ退避します。
睡眠の可視化には[`dashboards/sleep.json`](../dashboards/sleep.json)を使います。

Health Connectの[睡眠仕様](https://developer.android.com/health-and-fitness/health-connect/features/sleep-sessions)と、Garminの[データ共有仕様](https://support.garmin.com/en-GB/?faq=JToBEy0jfe6pIygark2Ui5)に対応しています。
JVMテストは本体と通信試作の各76件、両APKのビルドとLintを通過済みです。
2026-11-01にPixel 10 ProのAndroid v0.6.10で、Garmin Connect由来の睡眠記録をHealth Connectから読み取り、Grafana Cloudへ送信できることを確認しました。
浅い睡眠、深い睡眠、REM、覚醒の区間時刻と、セッションの集計表示も確認済みです。
訂正・削除時の処理はJVMテストで検証しており、実機の記録を意図的に変更・削除する操作は行っていません。


## 仕組み

- WorkManager の定期ジョブが 15 分ごとに動き、前回のチェックポイントから現在までの `UsageStatsManager` のイベントを読む。
  - root スパン `active`: `KEYGUARD_HIDDEN`（ロック解除）から `SCREEN_NON_INTERACTIVE` または `KEYGUARD_SHOWN` まで。ロック画面のない端末では `SCREEN_INTERACTIVE` から始める。1 時間を超えると分割し、新しいトレースを始めて前の root へのスパンリンクを付ける。
  - app スパン: `ACTIVITY_RESUMED` のパッケージごと。別パッケージの `ACTIVITY_RESUMED` か root の終了まで。
  - `browser.tab` スパン: ユーザー補助サービスが記録したアドレスバーの文字列のうち、ブラウザの app スパンに入るもの。ドメイン、プライバシー設定に従った URL、ブラウザー名を記録し、ページタイトルは取得しない。[android.json](../dashboards/android.json) の「Top domains」と「Recent tabs」に表示する。
  - 実行の終わりにまだ閉じていない区間は、トレース ID・スパン ID・開始時刻ごと端末に保存して次回に引き継ぐ。途中で送ることはしない。
- メトリクス（`activity.time`、`activity.app.time`、`activity.category.time`、`activity.browser.domain.time`、`activity.network.mobile.usage`、`activity.network.wifi.usage`、`activity.device.unlocks`）は cumulative の Sum。合計値と開始時刻を端末に保存するので、プロセスが殺されても値は増え続ける。開いている区間は「どこまで加算したか」を覚えておき、二重に数えない。モバイル通信量と Wi-Fi 通信量も日付をまたいで積算を続ける。
- モバイル通信量と Wi-Fi 通信量は Android の [`NetworkStatsManager`](https://developer.android.com/reference/android/app/usage/NetworkStatsManager) から回線種別ごとに取得する。端末全体と UID（通常はアプリ）ごとの受信・送信合計を前回の取得値と比較し、増分を cumulative メトリクスに加算する。収集は WorkManager の 15 分周期で、日付変更後は前回の集計開始から新しい日付の境界までの実績も読み、最後の収集後の通信量を加算する。取得値と送信用の累積値を一緒に保存するので、再起動や旧版からの更新でも二重加算しない。取得失敗時は該当する回線種別のサンプルを送らず、診断ログを残す。もう一方の回線種別は独立して収集する。
- ダッシュボード上部の「Mobile data」は選択期間の端末合計、下部の「Top apps by mobile data」は同期間のアプリ別上位 15 件を表示する。Wi-Fi も「Wi-Fi data」と「Top apps by Wi-Fi data」で同様に表示する。Wi-Fi は接続先ごとに分けず、すべての Wi-Fi 接続の合計を集計する。値は Android の履歴集計と Prometheus の増分推定による概算。共有 UID はまとめ、システムやテザリングなどの UID も表示する。端末合計と UID 別合計は一致しない場合がある。OS の履歴反映の遅れや収集停止中の通信は、後の観測に反映されるため、厳密な 15 分区間ごとの実測ではない。
- ログは `activity.device.screen`（画面オン/オフ、ロック、ロック解除）、`activity.app.switch`、`agent.start`、`agent.permission`、`agent.diagnostic`。
- 送るデータはいったんアプリ内の退避領域（上限 32 MiB、超えたら古いものから捨てる）に書き、古い順に送る。ネットワークエラー、429、5xx のときは残して次回に再送する。ネットワークが戻った時点で送るよう、接続を条件にした再送ジョブも登録する。それ以外の 4xx は別のキューへ退避して定期的な再送を試み、ステータスと応答本文を設定画面の診断欄に出す。
- 収集自体はネットワークを必要としない。オフラインの間も 15 分ごとに収集して退避領域にためる。

## アプリのカテゴリ

設定画面の「App categories」→「Manage categories」からアプリごとのカテゴリを設定できます。
「New category」で任意のカテゴリを作成し、一覧のアプリをタップして割り当てます。
たとえば「Work/Development」や「学習」のような名前を使えます。
一覧には最近使ったアプリから順に表示し、アプリ名やパッケージ名による検索と「Uncategorized only」による未分類の絞り込みに対応しています。

手動の割り当てを優先し、割り当てのないアプリは Android の `ApplicationInfo.category` を使います。
「Use Android category」で手動の割り当てを解除できます。
「Manage categories」ではカテゴリの名前変更と削除ができます。
名前変更は割り当て済みのアプリにも反映し、削除すると該当アプリは Android の分類に戻ります。

変更は次回以降の収集に適用します。
送信済みのカテゴリ別メトリクスやトレースは書き換えないため、ダッシュボードの表示期間に変更前のデータが含まれる場合は、以前のカテゴリも表示されます。
既存の Android ダッシュボードはカスタムカテゴリをそのまま表示でき、クエリの変更は不要です。

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

1. 使用状況へのアクセス（Usage Access）: 「Open Usage Access settings」→ activitylog → 「使用状況へのアクセスを許可」をオンにする。これがないと何も収集しない。アプリ別のモバイル通信量にもこの許可を使う。
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

- 「Interval」で取得の間隔を選ぶ。
  - every minute（デフォルト）は、フォアグラウンドサービスで 1 分ごとに GPS を含む高精度の位置を取る。Android はバックグラウンドのアプリに 1 時間に数回しか位置を渡さないため、通知を出し続けるフォアグラウンドサービスが必要になる。電池の消費は増える。
  - ~15 min は、15 分ごとの定期ジョブで現在地を 1 回取得する。通知は出ない。
- どちらの間隔でも、地図アプリなどほかのアプリが位置を要求したときの位置も受け取る（passive。電池をほとんど使わない）。
- 位置の権限は「常に許可」が必要。「Grant location (all the time)」を押すと、まず「アプリの使用中のみ」と通知を許可するダイアログが出て、続いて権限の設定画面が開くので「常に許可」を選ぶ。
- フォアグラウンドサービスは、設定画面で保存したとき、端末の起動後、アプリの更新後に始まる。端末が長く静止しているときは、Android の省電力機能で取得がまとめて遅れることがある。
- 位置は端末にためておき、15 分ごとの定期ジョブ（または「Send now」）でまとめて送る。
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
- Grafana Cloud 側の受け入れ期限を過ぎたデータが拒否された場合は、下記の退避キューで再送を試みる。受け入れ条件が変わらなければ、再送しても拒否される。
- 送信先は HTTPS のみ（Android の既定で平文 HTTP は使えない）。

## 未送信キューと再起動

未送信の OTLP ペイロードはアプリ内の `files/spool`、未処理の位置とブラウザー観測は JSONL ファイルに保存します。
書き込みとファイル名の変更をディスクへ同期してから保存完了とし、処理途中で停止したキューも次回の収集で読み直します。
端末起動後とアプリ起動時に、ネットワーク接続を条件とする送信ジョブを登録します。送信に失敗して再試行可能な場合は、保存した時刻と内容を変えずに再送します。
実行時刻は Android の制御を受けます。端末再起動後は初回のロック解除が必要です。強制停止した場合はアプリを開き直してください。

保存容量には上限があります。OTLP キューは 32 MiB を超えると古いペイロードを削除し、位置とブラウザー観測のキューはそれぞれ約 4 MiB に達すると新規の追加を断ります。
送信先が再試行不可の応答を返したデータは、通常キューから退避キューへ移します。送信成功後の削除前やキュー統合中に停止すると重複送信する場合があり、厳密な一度だけの送信は保証しません。

## 拒否データの退避と再送

HTTP 400、401、403 などで拒否されたペイロードは `files/rejected` に移し、元の圧縮データと測定時刻を保持します。
通常送信は次のペイロードへ進むため、拒否データが新しいデータの送信を止めることはありません。
ネットワーク接続を条件とする WorkManager の15分周期ジョブが、各ペイロードの再送時刻を確認します。初回の再送は退避から6時間後、それ以降も前回の試行開始から6時間以上空けます。
1回のジョブで最大5ペイロードを送り、次のいずれかで削除します。

- 送信が成功した。
- 退避後の再送が20回に達した。通信エラーも試行回数に含める。
- 最初の退避から7日が経過した。オフラインでも通常の収集ジョブで期限切れを削除する。
- 退避キューが32 MiBを超えた。容量を守るため古いものから削除する。

回数と次回の再送時刻は送信前に永続化します。送信途中でプロセスが停止した場合も1回として数え、再起動で回数や7日の期限をリセットしません。
実行時刻はAndroidの制御を受けるため、再送や期限切れの削除は条件を満たした後のジョブ実行時になります。
接続設定が未入力の場合は再送せず、期限切れの削除だけ行います。再送にはその時点で保存されている接続設定を使います。
画面の送信結果に退避中の件数を表示し、再送結果と上限による削除は診断ログへ記録します。

この処理は通常のOTLP再送で、Grafana Cloudの設定変更やTSDBブロックのバックフィルは行いません。
受け入れ時間幅が変わらなければ、古いデータの再送は失敗し続けます。現在時刻への書き換えは行いません。
この変更より前に削除済みのペイロードは復元できません。複数のデータポイントを含むリクエストが部分的に受理された場合は、再送時に既存のポイントも再び送られる可能性があります。
