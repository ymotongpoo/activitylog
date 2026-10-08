# activitylog シェルフック

zsh と bash で、コマンドの開始と終了、カレントディレクトリの変化を `activitylog-agent emit terminal` でエージェントに送る。
HTTP は直接叩かず、JSON の組み立てと送信は `emit` サブコマンドに任せる。

呼び出しはすべてバックグラウンドで切り離して実行し、入出力は `/dev/null` に捨てる。プロンプトは遅くならず、何も表示しない。
エージェントのバイナリが見つからなければフックは登録しない。

## 使い方

`~/.zshrc` に追加する。

```sh
source ~/repos/activitylog/extensions/shell/activitylog.zsh
```

`~/.bashrc` に追加する。

```sh
source ~/repos/activitylog/extensions/shell/activitylog.bash
```

`activitylog-agent` が `PATH` にない場合は、読み込む前に `ACTIVITYLOG_BIN` でコマンド名かパスを指定する。

```sh
export ACTIVITYLOG_BIN="$HOME/.local/bin/activitylog-agent"
```

バイナリの場所は読み込み時に一度だけ解決する。エージェントの接続先は `emit` 側の設定（`ingest.listen` または `ACTIVITYLOG_ADDR`）に従う。

## 送るイベント

| イベント | zsh | bash |
|---|---|---|
| `start`（`--command` 付き） | `preexec`（入力したコマンドライン `$1`） | コマンドラインの最初のコマンドの直前 |
| `end`（`--exit-code` 付き） | `precmd`（`start` を送ったときだけ） | プロンプト表示前（`start` を送ったときだけ） |
| `cwd` | `chpwd` と読み込み時 | プロンプト表示前にディレクトリが変わっていたときと読み込み時 |

どのイベントにも `--shell`、`--pid $$`、`--tty`、`--cwd "$PWD"` を付ける。

## bash の注意点

[bash-preexec](https://github.com/rcaloras/bash-preexec) を先に読み込んでいれば、その `preexec_functions` / `precmd_functions` を使う。
読み込んでいなければ、DEBUG トラップと `PROMPT_COMMAND` による最小限の実装を使う（bash 3.2 以降）。この場合は次の制限がある。

- `PROMPT_COMMAND` の先頭と末尾に自前の関数を置く。既存の `PROMPT_COMMAND`（文字列、bash 5.1 以降の配列）はそのまま残る。後から追加されても次のプロンプトで並び直す。
- DEBUG トラップは最初のプロンプトで設定する。その時点で設定済みの DEBUG トラップは連結して残すが、後から DEBUG トラップを設定すると上書きされる。DEBUG トラップを使うツールは先に読み込むか、bash-preexec を使う。
- コマンド文字列は `history 1` から取る。`HISTCONTROL=ignorespace` などで履歴に残らなかった行は、最初の単純コマンドだけを送る。
- `(cd dir && make)` のようにサブシェルだけの行は DEBUG トラップが発火しないので送られない。
- 補完関数と `bind -x` のウィジェットで実行されるコマンドは無視する。
