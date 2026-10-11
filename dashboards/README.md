# Grafana ダッシュボード

Grafana Cloud 用のダッシュボード。
メトリクス（Mimir）で集計を、トレース（Tempo）で区間の明細を、ログ（Loki）で状態遷移と診断を見る。

| ファイル | 内容 |
|---|---|
| `activitylog.json` | 全デバイス（macOS、Linux、Android）の概要 |
| `android.json` | Android アプリ専用。画面時間、アプリ、当日のモバイルデータ通信量（端末合計とアプリ別）、ブラウザーのドメイン別時間とタブ URL、ロック解除、移動経路と速度 |
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
Android の「Mobile data」は NetworkStatsManager が返す当日（端末のローカル日付）のモバイル通信量を表示する。端末合計は受信と送信の合計、アプリ別は UID ごとの合計で、Android の集計による概算。アプリが UID を共有する場合はまとめて表示し、システムやテザリングなどアプリに対応しない UID も別行にする。Android の端末合計と UID 別の合計は一致しない場合がある。

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

- メトリクスはエージェントが 60 秒ごとに送るので、起動から 1 分ほどで出始める。
- トレースはスパンが閉じた時点で送られる。セッション（`active`）は AFK になるか `max_session_span`（既定 1 時間）で分割されるまで、Tempo のパネルに出ない。アプリやタブのスパンも、切り替えるまでは出ない。
- ログはイベントの発生時に送られる。
