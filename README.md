# delete-discord

自分のDiscord発言を一括削除する個人用ツール(Android向け)。

> ⚠️ ユーザートークンを使う自動操作はDiscord利用規約(セルフボット禁止)に抵触し、アカウント停止のリスクがあります。自己責任で、少量・低頻度で使ってください。

## 構成
- `app/assets/inject.js` — APK(WebViewラッパー `com.example.discordcleaner`)がdiscord.comに注入するスクリプト本体
- `userscript/` — Tampermonkey版(`.user.js`)と単体HTML版
- `tools/discord_delete_my_messages.py` — 旧CLI版
- `releases/` — 署名済みAPK
- `build_apk.sh` — inject.js差し替え+再署名

## 動作
1. サーバー/DM選択で自動スキャン(削除可能な通常メッセージのみ)
2. 件数表示 → 「削除開始」で**古い順**に削除、赤い「停止」で中断
3. キーワード/期間フィルタあり

## バージョン
- v1.2: 古い順削除、「確認のみ」削除、署名キーを新規作成(versionCode 2)

## 署名キーについて
`discord_cleaner_keystore.jks`(alias `cleaner`)は**リポジトリに含めません**(.gitignore)。
別途安全に保管してください。同じキーで署名し versionCode を上げれば、アンインストールせず更新でき、ログイン(2段階認証)が保持されます。紛失すると再インストール+再ログインが必要です。
