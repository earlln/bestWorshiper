#!/usr/bin/env bash
# The three version strings must agree, or the service worker serves a stale app
# and the APK reports a version the update check will not recognise.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
app=$(grep -oP "^const APP_VERSION = '\K[^']+" "$root/bestWorshiper.html")
sw=$(grep -oP "^const APP_CACHE_VERSION = '\K[^']+" "$root/sw.js")
gradle=$(grep -oP 'versionName "\K[^"]+' "$root/android/app/build.gradle")

echo "bestWorshiper.html : $app"
echo "sw.js              : $sw"
echo "build.gradle       : $gradle"

if [ "$app" != "$sw" ] || [ "$app" != "$gradle" ]; then
  echo "::error::버전이 서로 다릅니다. 셋을 같이 올려주세요." >&2
  exit 1
fi

if [ $# -ge 1 ] && [ -n "$1" ]; then
  want="${1#v}"
  if [ "$app" != "$want" ]; then
    echo "::error::태그 v$want 와 앱 버전 $app 이 다릅니다." >&2
    exit 1
  fi
  echo "태그와 일치: v$app"
fi
echo "버전 일치 확인"
