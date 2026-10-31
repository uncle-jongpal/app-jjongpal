#!/usr/bin/env bash
# 빌드된 APK 를 운영 서버 /static 에 올리고 latest.json(앱 안 업데이트가 보는 최신 버전 정보)을 바꾼다.
# 사용: scripts/publish-apk.sh "바뀐 점 한두 줄"
#   먼저 app/build.gradle.kts 의 versionCode +1, versionName 올리고 ./gradlew :app:assembleDebug
set -euo pipefail
cd "$(dirname "$0")/.."

HOST=${PRD_HOST:-agent@192.168.0.3}
STATIC=/home/agent/work/prd/app-jjongpal/pc/nginx/static
APK=app/build/outputs/apk/debug/app-debug.apk
NOTES=${1:-}

CODE=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
NAME=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
FILE="jjongpal-v$NAME.apk"
[ -f "$APK" ] || { echo "APK 없음: $APK (먼저 빌드)"; exit 1; }
SHA=$(sha256sum "$APK" | cut -d' ' -f1)

mkdir -p releases
cp "$APK" "releases/jjongpal-$NAME.apk"

python3 - "$CODE" "$NAME" "$FILE" "$SHA" "$NOTES" > /tmp/latest.$$.json <<'EOF'
import json, sys
code, name, apk, sha, notes = sys.argv[1:]
print(json.dumps({"versionCode": int(code), "versionName": name, "apk": apk, "sha256": sha, "notes": notes},
                 ensure_ascii=False, indent=2))
EOF

scp -q "$APK" "$HOST:$STATIC/$FILE"
# 서버에 올라간 파일이 같은지 확인한 뒤에만 latest.json 을 바꾼다(앱이 반쯤 올라간 파일을 받지 않게)
REMOTE_SHA=$(ssh "$HOST" "sha256sum $STATIC/$FILE" | cut -d' ' -f1)
[ "$REMOTE_SHA" = "$SHA" ] || { echo "업로드 검증 실패"; rm -f /tmp/latest.$$.json; exit 1; }
scp -q /tmp/latest.$$.json "$HOST:$STATIC/latest.json.tmp"
ssh "$HOST" "mv $STATIC/latest.json.tmp $STATIC/latest.json"
rm -f /tmp/latest.$$.json

echo "올림: v$NAME (code $CODE)"
echo "링크: https://jjongpal.uncle-jongpal.com/static/$FILE"
