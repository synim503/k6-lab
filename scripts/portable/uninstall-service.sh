#!/bin/sh
# Удаляет systemd-сервис k6-lab (данные и настройки не трогает).
set -eu
[ "$(id -u)" -eq 0 ] || { echo "Запустите от root: sudo $0"; exit 1; }
systemctl disable --now k6-lab 2>/dev/null || true
rm -f /etc/systemd/system/k6-lab.service
systemctl daemon-reload
echo "Сервис k6-lab удалён."
