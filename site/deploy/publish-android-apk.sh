#!/usr/bin/env bash
# Кладёт release APK в site/android-app/ и обновляет version.json.
# APK в git не коммитится; version.json — коммитить.
# Скачивание только из личного кабинета: /admin/app → /api/admin/app/download
#
#   ./site/deploy/publish-android-apk.sh path/to/app-release.apk [versionName] [versionCode]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APK_SRC="${1:-}"
VERSION_NAME="${2:-}"
VERSION_CODE="${3:-}"
APP_DIR="${ROOT}/site/android-app"
GRADLE="${ROOT}/android/app/build.gradle.kts"

if [[ -z "${APK_SRC}" || ! -f "${APK_SRC}" ]]; then
  echo "Usage: $0 path/to/app-release.apk [versionName] [versionCode]" >&2
  exit 1
fi

if [[ -z "${VERSION_NAME}" ]]; then
  VERSION_NAME="$(sed -n 's/.*versionName = .*?: \"\([^\"]*\)\".*/\1/p' "${GRADLE}" | head -1)"
fi
if [[ -z "${VERSION_CODE}" ]]; then
  VERSION_CODE="$(sed -n 's/.*versionCode = .*?: \([0-9][0-9]*\).*/\1/p' "${GRADLE}" | head -1)"
fi
if [[ -z "${VERSION_NAME}" || -z "${VERSION_CODE}" ]]; then
  echo "Не удалось определить versionName/versionCode" >&2
  exit 1
fi

mkdir -p "${APP_DIR}"
cp -f "${APK_SRC}" "${APP_DIR}/rogein-chip.apk"
cat > "${APP_DIR}/version.json" <<EOF
{
  "versionName": "${VERSION_NAME}",
  "versionCode": ${VERSION_CODE},
  "url": "/api/admin/app/download",
  "updatedAt": "$(date -u +%Y-%m-%d)"
}
EOF

echo "OK: ${APP_DIR}/rogein-chip.apk"
echo "OK: ${APP_DIR}/version.json → ${VERSION_NAME} (${VERSION_CODE})"
echo "Закоммитьте version.json. На сервере: deploy-release.sh (ANDROID_APP_DIR)."
