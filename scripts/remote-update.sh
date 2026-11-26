#!/bin/bash
# Выполняется НА ХОСТЕ (его запускает scripts/deploy-host.sh): заменяет установленную админку новой сборкой.
# Настройки (config/) и данные (data/) сохраняются, прежняя версия остаётся в <каталог>.prev; при неудачном старте - откат.
#   ARCHIVE=/tmp/k6-lab-linux-amd64.tar.gz DIR=/opt/k6-lab SERVICE=k6-lab PORT=8080 bash remote-update.sh
set -euo pipefail
ARCHIVE="${ARCHIVE:?не задан ARCHIVE}"
DIR="${DIR:-/opt/k6-lab}"
SERVICE="${SERVICE:-k6-lab}"
PORT="${PORT:-8080}"
WAIT="${WAIT:-90}"
NEW="$DIR.new"; PREV="$DIR.prev"
SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"

has_unit() { command -v systemctl >/dev/null 2>&1 && systemctl cat "$SERVICE" >/dev/null 2>&1; }
stop_app() {
  if has_unit; then $SUDO systemctl stop "$SERVICE" || true
  else $SUDO pkill -f "[k]6-lab-admin.jar" 2>/dev/null || true; sleep 2; fi
}
start_app() {
  if has_unit; then $SUDO systemctl start "$SERVICE"
  else (cd "$DIR" && setsid nohup ./start.sh > "$DIR/k6-lab.log" 2>&1 < /dev/null &); fi
}
healthy() {
  for _ in $(seq 1 "$WAIT"); do
    if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/actuator/health" 2>/dev/null \
       || curl -fsS -o /dev/null "http://127.0.0.1:$PORT/login" 2>/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}

echo "== Распаковка новой версии"
$SUDO rm -rf "$NEW"; $SUDO mkdir -p "$NEW"
$SUDO tar -xzf "$ARCHIVE" -C "$NEW" --strip-components=1
[ -f "$NEW/k6-lab-admin.jar" ] || { echo "В архиве нет k6-lab-admin.jar"; exit 1; }

echo "== Остановка приложения перед переносом данных"
stop_app

if [ -d "$DIR" ]; then
  echo "== Перенос настроек и данных"
  [ -d "$DIR/config" ] && $SUDO cp -a "$DIR/config" "$NEW/config"
  [ -d "$DIR/data" ] && $SUDO cp -a "$DIR/data" "$NEW/data"
  # тот же владелец, что у текущей установки (служба обычно работает не от root)
  $SUDO chown -R --reference="$DIR" "$NEW"
fi

echo "== Замена и запуск"
$SUDO rm -rf "$PREV"
[ -d "$DIR" ] && $SUDO mv "$DIR" "$PREV"
$SUDO mv "$NEW" "$DIR"
start_app
if healthy; then
  echo "Готово: новая версия работает (http://127.0.0.1:$PORT). Прежняя - в $PREV."
  # временную копию, которую загрузил deploy-host.sh, удаляем; чужие файлы не трогаем
  case "$ARCHIVE" in /tmp/*) rm -f "$ARCHIVE" ;; esac
else
  echo "✖ Новая версия не поднялась за ${WAIT} с - откат"
  stop_app
  $SUDO rm -rf "$DIR"
  [ -d "$PREV" ] && $SUDO mv "$PREV" "$DIR"
  start_app || true
  exit 1
fi
