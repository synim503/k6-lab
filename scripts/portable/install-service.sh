#!/bin/sh
# Устанавливает k6-lab как systemd-сервис (автозапуск, перезапуск при падении). Запускать от root из каталога комплекта:
#   sudo ./install-service.sh [пользователь]      (по умолчанию - владелец каталога)
set -eu
[ "$(id -u)" -eq 0 ] || { echo "Запустите от root: sudo $0"; exit 1; }
DIR="$(cd "$(dirname "$0")" && pwd)"
USER_NAME="${1:-$(stat -c %U "$DIR")}"
JAVA="$(command -v java || true)"
[ -x "$DIR/jre/bin/java" ] && JAVA="$DIR/jre/bin/java"
[ -n "$JAVA" ] || { echo "Не найдена Java 17+ (положите JRE в $DIR/jre или установите java)"; exit 1; }
if [ ! -f "$DIR/config/k6lab.properties" ] && [ -z "${K6LAB_DB_URL:-}" ]; then
  echo "Сначала один раз запустите ./start.sh и пройдите мастер настройки (PostgreSQL) в браузере, затем остановите (Ctrl+C) и повторите установку."
  exit 1
fi
cat > /etc/systemd/system/k6-lab.service <<UNIT
[Unit]
Description=k6-lab admin
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
User=$USER_NAME
WorkingDirectory=$DIR
ExecStart=$JAVA -Xmx1g -jar $DIR/k6-lab-admin.jar
Restart=always
RestartSec=5
SuccessExitStatus=143
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now k6-lab
echo "Готово: сервис k6-lab запущен. Логи: journalctl -u k6-lab -f ; остановка: systemctl stop k6-lab ; удаление: ./uninstall-service.sh"
