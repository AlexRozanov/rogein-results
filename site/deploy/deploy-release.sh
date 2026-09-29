#!/usr/bin/env bash
# Деплой релиза сайта СК Малахит (Oracle Linux 9).
# Запуск от root:
#   /home/webdir/malahit/rogein-results/site/deploy/deploy-release.sh
#   /home/webdir/malahit/rogein-results/site/deploy/deploy-release.sh orient
set -euo pipefail

BRANCH="${1:-main}"
APP_USER="malahit"
ROOT="/home/webdir/malahit"
SRC="${ROOT}/rogein-results"
WWW="${ROOT}/www"
MEDIA="${ROOT}/media"
RUN="${ROOT}/run"
API_BIN="${RUN}/rogein-site-api"
SERVICE="malahit-api"
HEALTH_URL="http://127.0.0.1:18790/api/health"

STAGE="старт"

stage() {
  STAGE="$1"
  printf '\n\033[1;32m======== [%s] %s ========\033[0m\n' "$(date +%H:%M:%S)" "$1"
}

fail() {
  printf '\033[1;31mОШИБКА на стадии: %s\033[0m\n' "${STAGE}" >&2
}
trap fail ERR

as_malahit() {
  runuser -u "${APP_USER}" -- bash -lc "$*"
}

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Запускайте от root: sudo $0 ${BRANCH}" >&2
  exit 1
fi

if ! id "${APP_USER}" >/dev/null 2>&1; then
  echo "Нет пользователя ${APP_USER}" >&2
  exit 1
fi

echo "Релиз: ветка ${BRANCH}"
echo "Исходники: ${SRC}"
echo "Пользователь сборки: ${APP_USER}"

stage "1/6 Git: fetch, checkout ${BRANCH}, pull"
if [[ ! -d "${SRC}/.git" ]]; then
  echo "Нет клона ${SRC}. Сначала: git clone … ${SRC}" >&2
  exit 1
fi
as_malahit "
  cd '${SRC}'
  git fetch origin
  git checkout '${BRANCH}'
  git pull --ff-only origin '${BRANCH}'
  git log -1 --oneline
"

stage "2/6 Frontend: npm ci && npm run build"
as_malahit "
  export NVM_DIR=\"\$HOME/.nvm\"
  if [[ -s \"\$NVM_DIR/nvm.sh\" ]]; then
    . \"\$NVM_DIR/nvm.sh\"
  fi
  command -v npm >/dev/null || { echo 'npm не найден у ${APP_USER}. Проверьте nvm.'; exit 1; }
  echo \"node \$(node -v)  npm \$(npm -v)\"
  cd '${SRC}/site/web'
  npm ci
  npm run build
"

stage "3/6 Копирование www/ (карты media/ не трогаем)"
mkdir -p "${WWW}" "${MEDIA}" "${RUN}"
if [[ ! -d "${SRC}/site/web/dist" ]]; then
  echo "Нет ${SRC}/site/web/dist после сборки" >&2
  exit 1
fi
rsync -a --delete "${SRC}/site/web/dist/" "${WWW}/"
chown -R "${APP_USER}:${APP_USER}" "${WWW}"
chmod -R o+rX "${WWW}" "${MEDIA}"
if command -v restorecon >/dev/null 2>&1; then
  restorecon -Rv "${WWW}" "${MEDIA}" || true
fi

stage "4/6 Backend: cargo build --release"
as_malahit "
  if [[ -f \"\$HOME/.cargo/env\" ]]; then
    . \"\$HOME/.cargo/env\"
  fi
  command -v cargo >/dev/null || { echo 'cargo не найден у ${APP_USER}'; exit 1; }
  cd '${SRC}/site/api'
  cargo build --release
"

stage "5/6 Копирование бинарника API"
install -o "${APP_USER}" -g "${APP_USER}" -m 0755 \
  "${SRC}/site/api/target/release/rogein-site-api" \
  "${API_BIN}"
if [[ ! -f "${RUN}/.env" ]]; then
  echo "Нет ${RUN}/.env — создайте до запуска сервиса" >&2
  exit 1
fi

stage "6/6 Рестарт ${SERVICE} и проверка health"
systemctl restart "${SERVICE}"
sleep 1
systemctl --no-pager --full status "${SERVICE}" | head -n 20
for _ in 1 2 3 4 5; do
  if curl -fsS "${HEALTH_URL}" >/dev/null; then
    printf '\n\033[1;32mГотово. %s отвечает, ветка %s\033[0m\n' "${HEALTH_URL}" "${BRANCH}"
    curl -sS "${HEALTH_URL}"
    echo
    exit 0
  fi
  sleep 1
done

echo "Сервис перезапущен, но ${HEALTH_URL} не ответил" >&2
journalctl -u "${SERVICE}" -n 40 --no-pager >&2
exit 1
