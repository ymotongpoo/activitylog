# Grafana ダッシュボード

Grafana Cloud 用のダッシュボード。
メトリクス（Mimir）で集計を、トレース（Tempo）で区間の明細を、ログ（Loki）で状態遷移と診断を見る。

| ファイル | 内容 |
|---|---|
| `activitylog.json` | 全デバイス（macOS、Linux、Android）の概要 |
| `android.json` | Android アプリ専用。画面時間、アプリ、選択期間のモバイルデータ通信量（端末合計とアプリ別）、ブラウザーのドメイン別時間とタブ URL、ロック解除、移動経路と速度 |
| `linux.json`、`macos.json` | デスクトップエージェント専用。アクティブ時間、アプリ、ブラウザー、エディター、ターミナル。`target_info` の `os_type` でデバイスを絞る |

## インポート

1. Grafana Cloud で **Dashboards → New → Import** を開く。
2. 上の JSON のどれかをアップロードするか、内容を貼り付けて **Load** を押す。
3. 必要ならフォルダーと UID を変えて **Import** を押す。
4. ダッシュボード上部の変数で、データソースを選ぶ。

| 変数 | 種類 | Grafana Cloud で選ぶもの |
|---|---|---|
| `Metrics`（`prom`） | Prometheus | `grafanacloud-<stack>-prom` |
| `Traces`（`tempo`） | Tempo | `grafanacloud-<stack>-traces` |
| `Logs`（`loki`） | Loki | `grafanacloud-<stack>-logs` |
| `Device`（`instance`） | クエリ | 表示するデバイス（`service.instance.id`）。複数選択と All に対応 |

既定の期間は今日の 0 時から現在まで（`now/d` 〜 `now`）。

`android.json`、`linux.json`、`macos.json` は既定の期間が直近 24 時間で、データソース変数は `grafanacloud-<stack>-prom` などを自動で選ぶ。
デスクトップのブラウザー行は拡張機能、エディターとターミナルの行は対応する拡張機能やシェルのフックがないと空になる。Android はユーザー補助サービスでタブ URL を記録し、「Recent tabs」にドメイン、プライバシー設定に従った URL、ブラウザー名を表示する。ページタイトルは取得しない。
Android の上部の数値パネル「Mobile data」は、選択期間のモバイル通信量（受信と送信の合計）を表示します。下部の横棒グラフ「Top apps by mobile data」は、同じ期間の通信量をアプリ別に集計した上位 15 件です。Wi-Fi 通信量は「Wi-Fi data」と「Top apps by Wi-Fi data」に分けて表示します。いずれも各系列に `increase(...[$__range])` を適用してから合計します。アプリが UID を共有する場合はまとめて表示し、システムやテザリングなどアプリに対応しない UID も別行にします。Android の端末合計と UID 別合計は一致しない場合があります。

Android ダッシュボード上部の「Device」で端末を 1 台選ぶと、すべてのパネルをその端末だけに絞って表示します。
All と複数選択は無効にし、データソース選択欄を隠して端末の選択欄だけを表示します。
各セクションの見出しにも選択中の端末名を表示します。
「By device」には選択端末の画面使用時間、ロック解除回数、モバイル通信量、Wi-Fi 通信量を表示します。
アプリの使用時間と通信量のランキングも選択端末だけが対象です。
端末の識別には `service.instance.id` を使うため、同じ端末名の機器がある場合は Android アプリの「Device name」で異なる名前を設定してください。
URL の `var-device` パラメーターでも端末を指定できます。

Wi-Fi 通信量の収集には Android アプリ v0.6.6 以降が必要です。

通信量は 15 分周期の収集値による概算で、期間内に各系列のサンプルが 2 点以上必要。データ不足の場合はゼロと区別して「No samples」または「No data」と表示する。アプリ更新直後は観測がたまるまで待つか、時間範囲を広げる。修正前の v0.6.4 が送った日次リセット値には、日付をまたぐ区間の欠落が残り、後から復元できない。OS の履歴反映の遅れや収集停止中の通信量は、再取得時の増分として見える。

## 位置の地図

「Location」行の「Locations (Android)」は、Android アプリの `device.location` ログを Geomap に表示する。
Loki の structured metadata（`geo_location_lat`、`geo_location_lon`）を Extract fields の変換で取り出して数値に変換している。
位置の記録がオフのとき（デフォルト）は何も表示されない。
`android.json` の「Route」は同じログを時刻順に線でつなぎ、「Speed」は位置に付いた速度（`activity_location_speed`）を km/h で表示する。

## ラベル名

Grafana Cloud の OTLP 取り込みでは、属性名の `.` が `_` に変わる。

- メトリクス: `activity.app.name` → `activity_app_name`、`activity.network.scope` → `activity_network_scope`、`url.domain` → `url_domain`。`service.instance.id` は `instance`、`job` は `activitylog/activitylog-agent` または `activitylog/activitylog-android` になる。
- ログ: `service.namespace` などのリソース属性はストリームラベル `service_namespace`、`service_instance_id` になり、`event.name` は structured metadata の `event_name` になる。
- トレース: 属性名はそのまま（`span.activity.context.kind` など）。

## データが出るまでの時間

- デスクトップのメトリクスは 60 秒周期、Android は WorkManager の 15 分周期で収集して送る。`increase` を使うパネルには期間内のサンプルが 2 点以上必要。Android の実行時刻は OS に調整される。
- トレースはスパンが閉じた時点で送られる。セッション（`active`）は AFK になるか `max_session_span`（既定 1 時間）で分割されるまで、Tempo のパネルに出ない。アプリやタブのスパンも、切り替えるまでは出ない。
- ログはイベントの発生時に送られる。

## 通信量パネルのテスト

Python 3 と Prometheus の `promtool` を使い、保存済みの PromQL を実行して期間集計、端末の絞り込み、アプリ別集計、欠測時の挙動を確認する。

```sh
PROMTOOL=/path/to/promtool python3 -m unittest discover -s dashboards/tests -v
```

`PROMTOOL` 未指定時は PATH から探す。見つからない場合はレイアウト検査だけを実行し、PromQL の検査はスキップする。
