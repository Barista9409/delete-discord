# CLAUDE.md — 引き継ぎ情報

## 目的
自分のDiscord発言を一括削除する個人用Androidアプリ。**非公式のユーザートークン利用(セルフボット)でDiscord規約違反の可能性あり**。本人は了承済み。他人への配布はしない。

## トークンの扱い(v2で方針変更・本人決定)
- トークンは**端末内に暗号化して保存する**(`data/TokenVault.kt`: Android Keystore の AES-256-GCM 鍵、allowBackup=false)。
- **discord.com 以外へ送信しない。ログ・例外メッセージ・通知・コミットに含めない。** リポジトリ(public)にも絶対に入れない。
- 取得はログイン画面のWebViewで `evaluateJavascript`(webpackの getToken → localStorage.token)。JSブリッジは使わない。取得後はWebViewのCookie等を消す(サーバー側ログアウトはしない=トークンは有効のまま)。

## 構造(android/、Kotlin + Jetpack Compose)
- **アプリID(applicationId)は `com.dcleaner.app`(v2.1〜)**。旧 `com.example.discordcleaner` とは別アプリ。コードのnamespaceは `com.example.discordcleaner` のまま。applicationIdを変えると別アプリ扱いになりデータも引き継がれないので、今後は変えないこと。
- `data/` Models(Account / Routine / RunRecord)、Store(filesDir のJSON、StateFlow)、TokenVault
- `discord/` DiscordApi(REST v9、429はretry_after待ち、202は再試行)、Engine(スキャン→削除)
- `work/` RunWorker(1回分の実行、手動・スケジュール共通)、Scheduler(15分ごとの見回りワーカーが予定時刻を過ぎたルーチンを起動)、Notifications
- `ui/` Compose画面: Home / Wizard(対象→条件→確認)/ Running / Routines / RoutineEdit / History / Settings / Login / AccountSheet
- `Updater.kt` GitHub Releases latest を見て自動更新(SHA-256・パッケージ名・署名を照合)
- `userscript/` はTampermonkey版(旧ロジック。アプリとは独立)。

## 削除エンジンの設計(壊さないこと)
- 検索API `sort_by=timestamp&sort_order=asc` で**先にスキャン**してから**古い順**に削除(削除しながら検索するとインデックスがずれて止まる)。
- 削除対象は通常メッセージ(type 0/19)のみ。50021・404はスキップ、401は即中止して「要再ログイン」。
- 削除間隔はランダム(標準1.2〜2.5秒/ゆっくり3〜6秒)。短くしない。ルーチンの1回上限の既定は300件。
- 検索offsetが5000を超えたら最後のIDを min_id にして取り直す。
- 「確認のみ」チェックは仕様上削除済み。復活させない(確認画面での件数表示は別物)。
- バックグラウンド: RunWorker は dataSync のフォアグラウンドサービス。バックグラウンドから昇格できない端末では通常ワーカー(10分制限)として約8分半で区切り、残りは次回。
- 同時実行は1つ(プロセス内Mutex)。

## APKの更新手順(ログイン保持の鍵)
1. `android/` を編集し、作業ブランチにpush → 「Android CI」(署名なしテストビルド)が通ることを確認。この作業環境はAndroid SDKのダウンロードが遮断されているのでビルドはCIで行う。
2. `main` にマージ後、Actions「Build APK」を手動実行(MCPの actions_run_trigger、inputs.notes に変更内容 → アプリの更新ダイアログに表示される)。
   - Secrets `KEYSTORE_BASE64`/`KSPASS`(登録済み)で v1+v2+v3 署名、versionCode+1・versionName+0.1 を自動決定
   - 署名証明書が v1.2 以来の鍵(SHA-256 64fc9443…)と一致するか、v3署名があるか検証
   - `releases/` とREADMEにコミット、GitHub Release作成 → 各端末のアプリが自動で更新を検知
3. 署名キーは alias `cleaner`。**`.jks` はリポジトリに入れない**。同じキー+versionCode増加なら上書きインストールでき、データ(アカウント・ルーチン)が保持される。
4. v1.4はv2署名のみで一部端末で「競合」になった。署名方式(v1+v2+v3)を変えないこと。

## 環境メモ
- Android ChromeはブックマークレットのjavascriptをブロックするためKiwi Browser+Tampermonkeyが代替手段(userscript版)。
- ユーザー方針: 環境構築・ビルドはなるべくClaude側で完結させ、手作業を最小にする。説明は日本語で簡潔に(スマホで読む)。
