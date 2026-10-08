# activitylog Neovim プラグイン

Neovim（0.10 以降）で、開いているファイル、プロジェクト、filetype、Git ブランチをローカルの `activitylog-agent`（既定 `http://127.0.0.1:5610`）の `POST /v1/editor` へ送る。
送信は `vim.system()` で `curl` を非同期に起動して行うので UI は止まらない。`curl` がなければ何もしない。エージェントが起動していなくてもエラーは出ない。

## インストール

[lazy.nvim](https://github.com/folke/lazy.nvim) の例。

```lua
{
  dir = "~/repos/activitylog/extensions/nvim",
  name = "activitylog",
  event = "VeryLazy",
  opts = {
    -- agent_url = "http://127.0.0.1:5610",
    -- enabled = true,
  },
}
```

`opts` を渡すと lazy.nvim が `require("activitylog").setup(opts)` を呼ぶ。

他のプラグインマネージャーや手動で `runtimepath` に追加した場合は、`plugin/activitylog.lua` が既定値で自動的に起動する。
設定を変えたいときは `init.lua` で `require("activitylog").setup({ ... })` を呼ぶ（自動起動より先でも後でもよい）。
自動起動を止めたいときは、プラグインが読み込まれる前に `vim.g.activitylog_disable = true` を設定する。

## 設定

| キー | 既定値 | 説明 |
|---|---|---|
| `agent_url` | `http://127.0.0.1:5610` | エージェントの ingest API |
| `enabled` | `true` | 送信するかどうか |

## 送信するイベント

| `event` | autocmd |
|---|---|
| `focus` | `FocusGained`、起動時（`UIEnter`） |
| `blur` | `FocusLost`、`VimLeavePre` |
| `open` | `BufEnter`（`buftype` が空の通常ファイルのみ） |
| `edit` | `TextChanged` / `TextChangedI`（2 秒に 1 回まで） |
| `save` | `BufWritePost` |
| `heartbeat` | フォーカスがある間 30 秒ごと |

- `instance` と `pid` は Neovim の PID。
- `project` は `.git` を含むディレクトリ（なければカレントディレクトリ）の名前、`project_path` はその絶対パス。
- `branch` と `repository` は `git rev-parse --abbrev-ref HEAD` と `git config --get remote.origin.url` を非同期に実行して取得し、リポジトリごとに 60 秒キャッシュする。最初のイベントでは空のことがある。
- UI が接続していない（`--headless`）Neovim からは送らない。

## フォーカスの検出

`FocusGained` / `FocusLost` は端末のフォーカス通知に依存する。tmux では `set -g focus-events on` が必要。
フォーカス通知がない環境では、起動中はずっとフォーカスがあるものとして 30 秒ごとにハートビートを送る。エージェントは前面アプリが端末のときだけこの報告を採用する。
