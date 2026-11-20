"""
Discord 自分の発言 一括削除スクリプト
=====================================

【重要な注意】
- このスクリプトは Discord の「セルフボット禁止」規約に抵触するリスクがあります。
  自己責任で、少数の削除・低頻度での実行を推奨します。アカウント停止のリスクを理解の上でご使用ください。
- 公式Bot API(Botトークン)ではなく、あなた自身のユーザートークンを使います。
  トークンは絶対に他人に共有しないでください(アカウント乗っ取りに直結します)。

【事前準備】
1. Discordの「設定 > 詳細設定」で開発者モードをON
2. 削除したいサーバーを右クリック → 「IDをコピー」→ GUILD_ID
   (DMの場合は対象のDMを開いて、URLの数字部分がCHANNEL_ID)
3. 自分のユーザートークンの取得方法:
   - ブラウザでDiscordを開く (discord.com/app)
   - F12で開発者ツール → Network タブ
   - 適当にメッセージを送るなどして通信を発生させる
   - リクエストヘッダーの "authorization" の値をコピー
   ※ トークンはパスワードと同等に厳重に扱ってください

【使い方】
    pip install requests
    python discord_delete_my_messages.py

実行するとトークンや対象ID、キーワードなどを対話形式で聞かれます。
まずは DRY_RUN(削除せず確認のみ)で動作確認することを強く推奨します。
"""

import time
import requests

API_BASE = "https://discord.com/api/v9"


def get_headers(token: str) -> dict:
    return {
        "authorization": token,
        "content-type": "application/json",
    }


def get_my_id(token: str) -> str:
    resp = requests.get(f"{API_BASE}/users/@me", headers=get_headers(token))
    resp.raise_for_status()
    return resp.json()["id"]


def search_messages(token, guild_id, channel_id, author_id, keyword, offset):
    """guild_id が指定されていればサーバー全体検索、なければチャンネル内検索"""
    params = {"author_id": author_id, "offset": offset}
    if keyword:
        params["content"] = keyword

    if guild_id:
        url = f"{API_BASE}/guilds/{guild_id}/messages/search"
    else:
        url = f"{API_BASE}/channels/{channel_id}/messages/search"

    resp = requests.get(url, headers=get_headers(token), params=params)

    if resp.status_code == 429:
        retry_after = resp.json().get("retry_after", 2)
        print(f"  レート制限中... {retry_after}秒待機")
        time.sleep(retry_after + 0.5)
        return search_messages(token, guild_id, channel_id, author_id, keyword, offset)

    resp.raise_for_status()
    return resp.json()


def delete_message(token, channel_id, message_id):
    url = f"{API_BASE}/channels/{channel_id}/messages/{message_id}"
    resp = requests.delete(url, headers=get_headers(token))

    if resp.status_code == 429:
        retry_after = resp.json().get("retry_after", 2)
        print(f"    レート制限中... {retry_after}秒待機")
        time.sleep(retry_after + 0.5)
        return delete_message(token, channel_id, message_id)

    return resp.status_code in (200, 204)


def main():
    print("=== Discord 自分の発言 一括削除 ===\n")
    token = input("あなたのユーザートークン: ").strip()

    my_id = get_my_id(token)
    print(f"ログイン確認OK (User ID: {my_id})\n")

    scope = input("対象は [1] サーバー全体 / [2] 特定チャンネルのみ どちらですか? (1/2): ").strip()

    guild_id = None
    channel_id = None
    if scope == "1":
        guild_id = input("対象サーバー(ギルド)のID: ").strip()
    else:
        channel_id = input("対象チャンネル(またはDM)のID: ").strip()

    keyword = input("特定のキーワードを含む発言のみ削除する場合は入力(絞り込み不要ならEnter): ").strip()

    dry_run_input = input("まずは確認のみ(削除しない)で実行しますか? (y/n) [推奨: y]: ").strip().lower()
    dry_run = dry_run_input != "n"

    delay = 1.5  # 削除ごとの待機秒数(レート制限・BAN回避のため短くしすぎない)

    print("\n検索を開始します...\n")

    offset = 0
    total_found = 0
    total_deleted = 0

    while True:
        data = search_messages(token, guild_id, channel_id, my_id, keyword, offset)
        messages = data.get("messages", [])
        total_results = data.get("total_results", 0)

        if not messages:
            break

        for group in messages:
            # group は [対象メッセージ, コンテキストメッセージ...] の形なので先頭のみ使う
            msg = group[0]
            msg_channel_id = msg["channel_id"]
            msg_id = msg["id"]
            content_preview = (msg.get("content") or "[添付/埋め込みのみ]")[:40]

            total_found += 1
            print(f"[{total_found}/{total_results}] {content_preview!r}")

            if not dry_run:
                ok = delete_message(token, msg_channel_id, msg_id)
                if ok:
                    total_deleted += 1
                    print("    → 削除しました")
                else:
                    print("    → 削除失敗")
                time.sleep(delay)

        offset += len(messages)

        if offset >= total_results:
            break

        # 検索APIのインデックス更新待ち(削除直後は少し待つ必要あり)
        time.sleep(2)

    print("\n=== 完了 ===")
    print(f"該当メッセージ数: {total_found}")
    if dry_run:
        print("DRY RUN のため実際には削除していません。削除する場合は再実行時に 'n' を選んでください。")
    else:
        print(f"削除成功: {total_deleted} 件")


if __name__ == "__main__":
    main()
