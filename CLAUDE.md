# CLAUDE.md — 引き継ぎ情報

## 目的
自分のDiscord発言を一括削除する個人用Androidアプリ。**非公式のユーザートークン利用(セルフボット)でDiscord規約違反の可能性あり**。本人は了承済み。他人への配布・トークンの保存/送信/ログ出力は絶対にしない。

## 構造
- アプリ本体は `com.example.discordcleaner`(Kotlin/WebView)。discord.comを開き `assets/inject.js` を注入する。**Kotlinソースはこのリポジトリに無い**(v1.1 APKから逆算)。UIやロジックの変更は基本 `app/assets/inject.js` だけで済む。
- `userscript/` はTampermonkey版(inject.jsの元になったロジック。APK版とは起動ボタン等が少し違う)。
- 現行APK: `releases/discode_message_cleaner_v1.2.apk`(versionCode 2 / 1.2)。

## inject.js の設計(壊さないこと)
- トークン取得: webpackChunk* から getToken を持つモジュール → localStorage.token → 手入力の順。
- フロー: サーバー/DM選択で**自動スキャン**(検索API、`sort_by=timestamp&sort_order=asc`)→ 削除可能な通常メッセージ(type 0/19)のみ一覧化 → 「削除開始」で**古い順**に削除 → 赤い「停止」で中断。
- スキャン先行方式にしている理由: 削除しながら検索すると検索インデックスのずれで1件で止まる。
- 429はretry_afterで待機。50021(システムメッセージ)はスキップ。削除間隔は短くしすぎない。
- 「確認のみ」チェックは仕様上削除済み。復活させない。

## APKの更新手順(ログイン保持の鍵)
1. `app/assets/inject.js` を編集し `node --check` で構文確認。
2. `VCODE=<前回+1> VNAME=<同じ文字数> KS=<jks> KSPASS=<pw> ./build_apk.sh releases/<最新>.apk <出力>.apk`
   (uber-apk-signer.jar を作業ディレクトリに置く。GitHub releasesから取得可。パスワードは本人から受け取る)
3. 署名キーは alias `cleaner`。**`.jks` はリポジトリに入れない(public repo)**。本人が保管し、更新時に渡してもらう。
4. 同じキー+versionCode増加なら上書きインストールでき、WebViewのログイン(2段階認証)が保持される。キーが違うとアンインストール+再ログインが必要。
5. versionName は文字列プールの都合で同じ文字数のみ(1.2→1.3可、1.9→1.10不可)。超える場合はAPK再生成が必要。
6. 出力APKは `releases/` に追加しREADMEのバージョン履歴を更新。

## 環境メモ
- Android SDK/Gradleがない環境でも、APK差し替え+再署名で更新可能(上記)。
- Android ChromeはブックマークレットのjavascriptをブロックするためKiwi Browser+Tampermonkeyが代替手段。
- ユーザー方針: 環境構築・ビルドはなるべくClaude側で完結させ、手作業を最小にする。説明は日本語で簡潔に(スマホで読む)。
