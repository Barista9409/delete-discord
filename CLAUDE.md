# CLAUDE.md — 引き継ぎ情報

## 目的
自分のDiscord発言を一括削除する個人用Androidアプリ。**非公式のユーザートークン利用(セルフボット)でDiscord規約違反の可能性あり**。本人は了承済み。他人への配布・トークンの保存/送信/ログ出力は絶対にしない。

## 構造
- アプリ本体は `com.example.discordcleaner`(Kotlin/WebView)。ソースは `android/`(v1.4で旧APKを元に再作成)。discord.comを開き `app/assets/inject.js` を注入する(Gradleが `../../app/assets` を参照)。削除ツールのUIやロジックの変更は基本 `app/assets/inject.js` だけで済む。
- `Updater.kt`: 起動時(とメニュー「更新を確認」)に GitHub Releases latest を確認 → 新版なら確認ダイアログ → APKダウンロード → SHA-256・パッケージ名・署名を照合 → Android標準のインストール画面。リポジトリ名がハードコードされているので、リポジトリ名を変えたら更新する。
- 旧APKと同じ設定を維持: デスクトップChrome UA、JS/DOMストレージのみ有効、ツールバー#2B2D31、メニュー「再読み込み」、戻るでWebView履歴を戻る。
- `userscript/` はTampermonkey版(inject.jsの元になったロジック。APK版とは起動ボタン等が少し違う)。
- APKは `releases/` に版ごとに置く(最新版をワークフローがバージョン判定に使う)。

## inject.js の設計(壊さないこと)
- トークン取得: webpackChunk* から getToken を持つモジュール → localStorage.token → 手入力の順。
- フロー: サーバー/DM選択で**自動スキャン**(検索API、`sort_by=timestamp&sort_order=asc`)→ 削除可能な通常メッセージ(type 0/19)のみ一覧化 → 「削除開始」で**古い順**に削除 → 赤い「停止」で中断。
- スキャン先行方式にしている理由: 削除しながら検索すると検索インデックスのずれで1件で止まる。
- 429はretry_afterで待機。50021(システムメッセージ)はスキップ。削除間隔は短くしすぎない。
- 「確認のみ」チェックは仕様上削除済み。復活させない。

## APKの更新手順(ログイン保持の鍵)
1. `app/assets/inject.js` や `android/` を編集し、作業ブランチにpush → 「Android CI」(署名なしテストビルド)が通ることを確認。
2. `main` にマージ後、Actions「Build APK」(`.github/workflows/build-apk.yml`)を手動実行(MCPの actions_run_trigger で可、inputs.notes に変更内容)。
   - Secrets `KEYSTORE_BASE64`/`KSPASS`(登録済み)で署名、versionCode+1・versionName+0.1 を自動決定(文字数制限なし)
   - 署名証明書が v1.2 以来の鍵(SHA-256 64fc9443…)と一致するか検証
   - `releases/` とREADMEにコミット、GitHub Release作成 → 各端末のアプリが自動で更新を検知
3. 署名キーは alias `cleaner`。**`.jks` はリポジトリに入れない(public repo)**。同じキー+versionCode増加なら上書きインストールでき、WebViewのログイン(2段階認証)が保持される。
4. ローカルでビルドする場合: `KS=<jks> KSPASS=<pw> gradle -p android assembleRelease -PvCode=N -PvName=X.Y`(Android SDKが必要。この作業環境はSDKのダウンロードが遮断されているのでCIを使う)。

## 環境メモ
- Android ChromeはブックマークレットのjavascriptをブロックするためKiwi Browser+Tampermonkeyが代替手段。
- ユーザー方針: 環境構築・ビルドはなるべくClaude側で完結させ、手作業を最小にする。説明は日本語で簡潔に(スマホで読む)。
