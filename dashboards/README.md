# Grafana ダッシュボード

`activitylog.json` は Grafana Cloud 用のダッシュボード。
メトリクス（Mimir）で集計を、トレース（Tempo）で区間の明細を、ログ（Loki）で状態遷移と診断を見る。

## インポート

1. Grafana Cloud で **Dashboards → New → Import** を開く。
2. `activitylog.json` をアップロードするか、内容を貼り付けて **Load** を押す。
3. 必要ならフォルダーと UID を変えて **Import** を押す。
4. ダッシュボード上部の変数で、データソースを選ぶ。

| 変数 | 種類 | Grafana Cloud で選ぶもの |
|---|---|---|
| `Metrics`（`prom`） | Prometheus | `grafanacloud-<stack>-prom` |
| `Traces`（`tempo`） | Tempo | `grafanacloud-<stack>-traces` |
| `Logs`（`loki`） | Loki | `grafanacloud-<stack>-logs` |
| `Device`（`instance`） | クエリ | 表示するデバイス（`service.instance.id`）。複数選択と All に対応 |

既定の期間は今日の 0 時から現在まで（`now/d` 〜 `now`）。

## ラベル名

Grafana Cloud の OTLP 取り込みでは、属性名の `.` が `_` に変わる。

- メトリクス: `activity.app.name` → `activity_app_name`、`url.domain` → `url_domain`。`service.instance.id` は `instance`、`job` は `activitylog/activitylog-agent` または `activitylog/activitylog-android` になる。
- ログ: `service.namespace` などのリソース属性はストリームラベル `service_namespace`、`service_instance_id` になり、`event.name` は structured metadata の `event_name` になる。
- トレース: 属性名はそのまま（`span.activity.context.kind` など）。

## データが出るまでの時間

- メトリクスはエージェントが 60 秒ごとに送るので、起動から 1 分ほどで出始める。
- トレースはスパンが閉じた時点で送られる。セッション（`active`）は AFK になるか `max_session_span`（既定 1 時間）で分割されるまで、Tempo のパネルに出ない。アプリやタブのスパンも、切り替えるまでは出ない。
- ログはイベントの発生時に送られる。
