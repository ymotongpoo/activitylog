# activitylog GNOME Shell 拡張

GNOME Wayland では外部プロセスから前面ウィンドウを取得できない。
この拡張は GNOME Shell の D-Bus 接続上にオブジェクトを公開し、`activitylog-agent` が前面ウィンドウを読めるようにする。
エージェントはこのメソッドを 1 秒ごとにポーリングする。

- 対応バージョン: GNOME Shell 45〜50（ESM 形式の拡張）
- UUID: `activitylog@ymotongpoo.net`
- 宛先: `org.gnome.Shell`
- オブジェクトパス: `/net/ymotongpoo/ActivityLog`
- インターフェース: `net.ymotongpoo.ActivityLog1`（メソッド `GetFocusedWindow` → JSON 文字列、プロパティ `Version`）

返す JSON の形式は [docs/design.md](../../docs/design.md) の「GNOME Shell 拡張の D-Bus インターフェース」を参照。

## インストール

```sh
./install.sh
```

`~/.local/share/gnome-shell/extensions/activitylog@ymotongpoo.net/` にファイルをコピーし、`gnome-extensions enable` で有効にする。

Wayland では、GNOME Shell は新しく入れた拡張をログイン時にしか読み込まない。
初回インストール後や更新後は一度ログアウトしてログインし直し、必要なら次を実行する。

```sh
gnome-extensions enable activitylog@ymotongpoo.net
```

## 動作確認

```sh
gdbus call --session --dest org.gnome.Shell \
  --object-path /net/ymotongpoo/ActivityLog \
  --method net.ymotongpoo.ActivityLog1.GetFocusedWindow
```

端末から実行すると、端末エミュレーター自身の情報が返る。
`sleep 3; gdbus call ...` として別のウィンドウに切り替えると、そのウィンドウの情報を確認できる。
アクティビティ画面などでフォーカスされたウィンドウがないときは `('{}',)` が返る。

インターフェースのバージョンは次で確認できる。

```sh
gdbus call --session --dest org.gnome.Shell \
  --object-path /net/ymotongpoo/ActivityLog \
  --method org.freedesktop.DBus.Properties.Get net.ymotongpoo.ActivityLog1 Version
```

エラーは `journalctl --user -f -o cat /usr/bin/gnome-shell` で確認できる。

## 注意

このオブジェクトはセッションバス上の任意のプロセスから呼べる。
ウィンドウタイトルは同じユーザーで動くプロセスに見える前提で使うこと。
