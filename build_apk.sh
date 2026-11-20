#!/usr/bin/env bash
# 既存APKの assets/inject.js を差し替え、再署名する簡易ビルド。
# 使い方: VCODE=3 VNAME=1.3 KS=discord_cleaner_keystore.jks KSPASS=xxxx ./build_apk.sh <ベースAPK> <出力APK>
# 事前: uber-apk-signer.jar を同じ場所に置く (https://github.com/patrickfav/uber-apk-signer)
# 注意: 更新でログインを保持するには、必ず同じKeystoreで署名し、versionCodeを上げること。
set -euo pipefail
BASE=$1; OUT=$2; W=$(mktemp -d)
unzip -q "$BASE" -d "$W/x"
cp app/assets/inject.js "$W/x/assets/inject.js"
python3 tools/patch_manifest_version.py "$W/x/AndroidManifest.xml" "${VCODE:?VCODE(例:3)を指定}" "${VNAME:?VNAME(例:1.3)を指定}"
(cd "$W/x" && zip -qr -X "$W/unsigned.apk" AndroidManifest.xml && zip -qr -X -D "$W/unsigned.apk" . -x AndroidManifest.xml -x resources.arsc && zip -q -X -0 "$W/unsigned.apk" resources.arsc)
java -jar uber-apk-signer.jar -a "$W/unsigned.apk" --ks "$KS" --ksAlias cleaner --ksPass "$KSPASS" --ksKeyPass "$KSPASS" --out "$W/signed"
cp "$W"/signed/*-signed.apk "$OUT"
