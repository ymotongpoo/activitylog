# Enduro 3のヘルスデータ送信

Enduro 3のヘルスデータ、活動データ、時計の状態を5分ごとに読み、ActivitylogのAndroidアプリへ送ります。
Body Batteryとストレスに加え、時計アプリv0.3.0とAndroid v0.6.9から心拍数、SpO₂、呼吸数、当日歩数、時計の電池残量、ソーラー強度にも対応しています。
時計にはGarminのアプリ開発環境である[Connect IQ SDK](https://developer.garmin.com/connect-iq/sdk/)で作った専用アプリを入れます。
Android側の通信の中継は、[Connect IQ Mobile SDK](https://github.com/garmin/connectiq-android-sdk)とインストール済みのGarmin Connectです。

受信機能は通常のActivitylogに統合しています。
受信値をAndroid内に保存した後、Activitylogに設定済みのOTLP接続先へGaugeメトリクスとして送ります。
「Garmin health」画面から受信を有効にすると、スマホの再起動やアプリ更新後も受信サービスが再開する仕組みです。

## 開発環境

- Enduro 3と、Garmin Connectでペアリング済みのAndroid端末
- JDK 21と、既存のAndroidアプリをビルドできるAndroid SDK
- Connect IQ SDK 9.3.0と、Enduro 3の端末定義
- 時計アプリを署名するRSA 4096ビットの秘密鍵

端末定義の取得にはGarminアカウントでのログインが必要です。
デスクトップ環境では、SDK Managerの「Devices」にEnduro 3を追加します。
SSH接続だけで使うサーバーでは、次節のCLI手順を使います。
ビルド時に「Invalid device id specified: enduro3」と表示される場合は、端末定義の配置を確認してください。
端末を指定しないコンパイルに成功しても、実機用のビルドを検証したことにはなりません。

### SSH経由で端末定義を取得する場合

Garmin公式とは別の[connect-iq-sdk-manager CLI](https://github.com/lindell/connect-iq-sdk-manager-cli)には、ブラウザーでGarminにログインする機能があります。
CLIのソースはed5d91fbacc05ad561aa448640a539a73b6c6aceで確認しました。
この手順は認証完了後の端末定義ダウンロードまで未検証です。

CLIを導入し、SSH先で次のコマンドを実行します。
利用規約を確認して同意する場合に、agreement acceptへ進んでください。
loginはブラウザーでの認証が完了するまで待機します。

```sh
connect-iq-sdk-manager agreement view
connect-iq-sdk-manager agreement accept
connect-iq-sdk-manager login
```

loginが表示するURLは、SSH先のlocalhostを指しています。
たとえばhttp://localhost:52123/loginと表示された場合は、手元のPCの別ターミナルで次のようにポート転送します。
52123は実際に表示されたポートへ、ssh-hostは同じ作業環境へのSSH接続先へ置き換えてください。
このコマンドも、認証中は終了せずに待機します。

```sh
ssh -N -o ExitOnForwardFailure=yes -L 52123:localhost:52123 ssh-host
```

手元のPCのブラウザーで、CLIに表示されたURLを開いてGarminにログインしてください。
パスワードはGarminのログインページに入力します。
CLIにSuccessfully logged inと表示されたら、SSH先で端末定義を取得します。

```sh
connect-iq-sdk-manager device download --device enduro3
```

確認したCLIは、macOSでも$HOME/.Garmin/ConnectIQ/Devices/enduro3へ保存する実装です。
SDKが$HOME/Library/Application Support/Garmin/ConnectIQ/Devicesを参照する場合は、取得したenduro3ディレクトリをそこへ配置してください。
Linuxでは$HOME/.Garmin/ConnectIQ/Devicesを使います。
認証と取得が終わったら、手元でポート転送をCtrl-Cで終了します。
続く実機用ビルドが成功することを確認してください。

### 署名鍵の準備

署名鍵をまだ持っていない場合は、リポジトリの外で生成します。
鍵はGitに追加しないでください。

```sh
mkdir -p "$HOME/.local/share/activitylog/garmin"
chmod 700 "$HOME/.local/share/activitylog/garmin"
(
  umask 077
  openssl genrsa -out "$HOME/.local/share/activitylog/garmin/developer.pem" 4096
  openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt \
    -in "$HOME/.local/share/activitylog/garmin/developer.pem" \
    -out "$HOME/.local/share/activitylog/garmin/developer.der"
)
```

## 時計アプリのビルド

CONNECTIQ_SDKには、bin/monkeybrains.jarがあるSDKのディレクトリを指定します。
SDK Managerがインストールしたディレクトリを確認し、次のパスを書き換えてください。
コマンドはリポジトリのルートで実行します。

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
export CONNECTIQ_SDK="/absolute/path/to/connectiq-sdk"
export GARMIN_DEVELOPER_KEY="$HOME/.local/share/activitylog/garmin/developer.der"
./garmin/build.sh
```

garmin/build/activitylog-health.prgが生成されます。
時計をUSB接続し、このファイルを時計のGARMIN/APPSへコピーしてください。
転送後にUSB接続を解除すると、「Activitylog Health」がアプリ一覧に追加されます。
Macで時計のストレージがFinderに表示されない場合は、MTP対応のファイル転送ソフトを使います。
詳しくはGarmin公式の[実機へのインストール手順](https://developer.garmin.com/connect-iq/connect-iq-basics/your-first-app/)を参照してください。

## Androidアプリの導入

通常のActivitylogのAPKをビルドし、既存のアプリへ更新インストールします。
署名鍵には、現在インストールしているアプリの署名に使った鍵を指定してください。
ビルドと署名の設定は[Androidの導入手順](../android/README.md)を参照してください。
設定画面の「Garmin health」から、受信の有効化と直近の値の確認ができます。

通信だけを検証する場合は、次の専用APKも使えます。
別パッケージの「Activitylog Garmin」であり、OTLPへの送信とスマホ利用状況の収集は行いません。
通常のActivitylogで受信するときは、専用APKの受信を停止してください。

```sh
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleGarminPrototype
adb install -r app/build/outputs/apk/garminPrototype/app-garminPrototype.apk
```

## 送受信の確認

1. スマホのGarmin Connectにサインインし、「Garminデバイス」にEnduro 3が接続済みで表示されることを確認します。MacのSDK Managerへのログインとは別です。
2. AndroidのActivitylogを開き、「Garmin health」→「Start receiver」を押します。求められた「付近のデバイス」と通知の権限を許可します。
3. 時計で「Activitylog Health」を一度開きます。5分ごとの処理を登録し、その場でも自動送信します。
4. 時計のBACKボタンでアプリを閉じ、通常の時計画面へ戻します。「Send now」の操作は不要です。
5. Androidの画面でBody Battery、Stress、測定時刻、収集時刻、受信時刻を確認します。
6. 時計アプリを閉じ、Androidの画面も消した状態で15分から20分待ちます。画面を開き直し、受信時刻が約5分ごとに増えているかを確認します。
7. 一時的に時計とスマホを離して接続を切り、1時間以内に再接続します。未送信分が届き、同じ収集時刻の行が重複しないことを確認します。

Androidの通知「Receiving Garmin health data」が、受信サービスの実行中に表示されます。
スマホの再起動後やアプリ更新後は、受信を自動で再開します。
Androidの設定から強制停止した場合は、Activitylogを開き直すと再開する仕組みです。
停止するときはAndroidの「Stop receiver」を押します。
時計側の収集も止める場合は、時計のメニューで「Pause」を選びます。

直近30分のセンサー履歴を新しい順に読み、最新の有効な測定値を送ります。
先頭が欠測でも、それより前の履歴に有効な値があれば採用する仕組みです。
値が見つからない場合は、Androidでunavailableと表示します。
測定不能値をストレス0として扱いません。
履歴を使う指標のageは、測定からの経過秒数です。
以前の測定値を採用した場合も、元の測定時刻を送るため古さを確認できます。

## OTLPへの送信

受信ごとに送信ジョブを登録し、スマホ利用状況の15分周期とは独立して送ります。
ネットワーク未接続時は保存して待ち、接続が戻ると再送します。
設定が未入力の場合も本体の退避領域に保持するため、設定後に送信できる構成です。

| メトリクス名 | 種類 | 値 |
| --- | --- | --- |
| garmin.body_battery | Gauge | Body Batteryの0〜100のスコア |
| garmin.stress | Gauge | ストレスの0〜100のスコア |
| garmin.heart_rate | Gauge | 心拍数、単位は{beat}/min |
| garmin.oxygen_saturation | Gauge | SpO₂、単位は% |
| garmin.respiration_rate | Gauge | 呼吸数、単位は{breath}/min |
| garmin.steps_today | Gauge | 時計の現地時刻で当日の累計歩数、単位は{step} |
| garmin.watch_battery | Gauge | 時計の電池残量、単位は% |
| garmin.solar_intensity | Gauge | ソーラー充電効率の0〜100のスコア |

Body Battery、ストレス、ソーラー強度の単位は{score}です。
属性garmin.device.idとgarmin.device.nameで時計を識別します。
Body Battery、ストレス、心拍数、SpO₂には、直近30分の履歴から有効な最新値と元の測定時刻を使います。
呼吸数、当日歩数、時計の電池残量、ソーラー強度は現在値を参照するAPIのため、取得時刻を記録する仕組みです。
Androidの受信画面では、前者をmeasured、後者をobservedとして区別します。
時計と指標と測定時刻が一致する値は、繰り返し出力しない仕組みです。
新しい測定を出力した後に受信した古い測定も、出力対象にはなりません。
欠測は0に変換せず、その指標の点を出力しません。
SpO₂は既存の[酸素飽和度の履歴](https://developer.garmin.com/connect-iq/api-docs/Toybox/SensorHistory.html#getOxygenSaturationHistory-instance_function)だけを読み、パルスオキシメーターの測定設定を変更したり、新たに測定を開始したりはしません。
時計側に最近30分以内の記録がなければ、欠測になります。
[ソーラー強度](https://developer.garmin.com/connect-iq/api-docs/Toybox/System/Stats.html#solarIntensity-var)の負の値はソーラー充電が非アクティブであることを示すため、欠測として扱う構成です。
照度をlux単位で測った値とは異なります。
当日歩数は午前0時にリセットされる現在値であり、累積カウンターとしては送信しません。
電池残量などの小数は丸めずに保存して送ります。
Android側の受信確認は端末内への保存完了を意味し、クラウドへの到達確認とは別です。
送信状況は「Garmin health」画面のActivitylog uploadで確認できます。

## 保存と再送

時計はAndroidから受信確認が返るまで送信データを保持し、次の送信時に再送します。
Androidからの受信確認は、保存が完了したことを表すものです。
時計側では最大12回分を保持し、上限を超えると古い回から破棄します。
5分周期なら約1時間分ですが、手動送信も1回として数えます。
Android側の保存件数は、端末IDと収集時刻で重複を除いた最新288件です。
画面にはそのうち最新20件を表示します。
OTLP用の未処理データは別途最大2016件保持し、上限時は時計へ受信確認を返しません。
本体の退避領域への書き込みが完了した時点が、未処理データを削除するタイミングです。
その後は本体と共通の32 MiBの容量上限と再送処理に従います。

時計側とAndroid側のアプリIDは、manifest.xmlとGarminProtocol.ktで共通です。
送信形式はschemaが1、typeがhealth、samplesが最大12件の辞書です。
各件にはcapturedAtと、各指標の値と時刻の組を含めます。
心拍数以降の6指標は省略可能なフィールドとして追加したため、新しいAndroidアプリは旧時計アプリのデータも受信できます。
旧Androidアプリは追加フィールドを保存しないため、Android版を先にFirebase App Distributionから更新してください。
時刻の単位はUnix秒です。
Androidからはschemaと、保存済みの最後のcapturedAtをackとして返します。

## 検証範囲

Connect IQ SDK 9.3.0で、enduro3を指定した時計アプリのビルドに成功しています。
Android本体のAPKもビルドでき、JVM単体テスト66件とLintを通過しています。
Android v0.6.9（609）はFirebase App Distributionのownerグループへ配布済みです。
Enduro 3からAndroid実機への受信と保存は、Body Batteryとストレスの数値を含むデータで確認済みです。
収集時刻が5分間隔の記録も確認済みです。
時計側の履歴選択については、従来の欠測を飛ばす処理など4件のシミュレーターテストがPASSしました。
追加した範囲検査と小数値のテストはコンパイル済みです。
時計アプリv0.3.0とAndroid v0.6.9の組み合わせで、追加6項目すべての実機受信とGrafana Cloudへの到着を確認済みです。
2026-10-28の検証では、時計を通常画面へ戻した後、15:21:18の収集データを15:21:21に自動受信しました。
受信確認による連続送信は発生せず、OTLP送信成功と送信待ち0件も確認しています。
呼吸数は初回には欠測でしたが、次の定期受信で有効な値が届きました。
2026-10-28の本体統合版の実機検証では、時計を通常画面に戻し、Androidもロックした状態で14:23:13と14:28:13の収集データを自動受信しました。
収集間隔は300秒で、後者の受信時刻は14:28:16です。
Body Batteryとストレスの受信、OTLP送信成功、送信待ち0件まで確認しています。
Androidアプリの更新後に、受信サービスが操作なしで再開することも確認済みです。
この検証中はAndroidをUSB接続しており、長時間の省電力運用と端末再起動後の実測は確認対象として残っています。

実機では、受信間隔と再接続後の動作を確認してください。
スマホの省電力時に受信を続けられるかも確認対象です。
5分はバックグラウンド処理の設定間隔であり、正確な時刻での実行を保証しません。
Garminの[バックグラウンド処理](https://developer.garmin.com/connect-iq/articles/core-topics/Backgrounding.html)は最長30秒です。
メモリ不足でも停止する場合があります。
また、[センサー履歴の測定間隔](https://developer.garmin.com/connect-iq/api-docs/Toybox/SensorHistory.html)は機種に依存します。

Androidの画面ロック中でも、USBデバッグ接続済みのPCから受信状態を確認できます。
認証情報は表示せず、直近4件の受信値、未処理件数、送信結果を表示する診断機能です。

```sh
adb shell dumpsys activity service net.ymotongpoo.activitylog/.garmin.GarminReceiverService
```

Android側にはJVM単体テストを用意しています。
メッセージ検証、欠測、重複除外、保存件数の上限を検査します。
実機テストの代わりにはならないため、導入後は前述の送受信手順で確認してください。

```sh
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew \
  assembleGarminPrototype testGarminPrototypeUnitTest lintGarminPrototype
```
