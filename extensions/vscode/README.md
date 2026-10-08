# activitylog VS Code 拡張

VS Code 系エディタ（VS Code、VS Code Insiders、Cursor、Windsurf、VSCodium）で、アクティブなファイル、プロジェクト、言語、Git ブランチをローカルの `activitylog-agent`（既定 `http://127.0.0.1:5610`）の `POST /v1/editor` へ送る。
送信先は手元のエージェントだけで、プライバシールールはエージェント側で適用される。

## ビルドとインストール

```sh
cd extensions/vscode
npm install
npm run compile
npm run package        # activitylog-0.1.0.vsix ができる
```

`npm run package` は LICENSE ファイルがないと続行するか確認してくるので `y` を入力する。

できた `.vsix` をインストールする。

```sh
code --install-extension activitylog-0.1.0.vsix
# Cursor:   cursor --install-extension activitylog-0.1.0.vsix
# Windsurf: windsurf --install-extension activitylog-0.1.0.vsix
# VSCodium: codium --install-extension activitylog-0.1.0.vsix
```

コマンドパレットの「Extensions: Install from VSIX...」からでもよい。

## 設定

| 設定 | 既定値 | 説明 |
|---|---|---|
| `activitylog.agentUrl` | `http://127.0.0.1:5610` | エージェントの ingest API |
| `activitylog.enabled` | `true` | 送信するかどうか |

コマンド「activitylog: Check Agent Connection」で `GET /v1/status` を呼び、疎通を確認できる。

## 送信するイベント

| `event` | タイミング |
|---|---|
| `focus` / `blur` | ウィンドウのフォーカス変化。拡張の停止時にも `blur` を送る |
| `open` | アクティブなエディタの切り替え |
| `edit` | アクティブなドキュメントの編集（2 秒に 1 回まで） |
| `save` | 保存 |
| `heartbeat` | フォーカスがある間 30 秒ごと |

- `editor` は `vscode.env.appName` から決める（`Cursor` → `cursor` など）。`instance` は `vscode.env.sessionId`。
- `file` は `file:` スキームのドキュメントだけ送る。未保存のバッファなどでは省略する。
- `branch` と `repository` は組み込みの Git 拡張（`vscode.git`）の API から取る。Git 拡張が無効でも動作する。
- エージェントが起動していなくてもエラーは出ない（2 秒でタイムアウトして破棄する）。

## Remote 開発

`extensionKind` を `ui` 優先にしているので、Remote - SSH や Dev Containers でもローカル側で動き、ローカルのエージェントに送る。
この場合ファイルは `vscode-remote:` スキームになるため `file` は送られず、Git 拡張もリモート側で動くのでブランチは取れない。
