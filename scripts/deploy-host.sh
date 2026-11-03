#!/bin/bash
# Обновление админки на вашем хосте ОДНОЙ командой, без интернета на хосте: собирает архив (если нужно), копирует его по SSH и
# заменяет установленную версию. Настройки и данные сохраняются, при неудачном старте - автоматический откат.
#
#   ./deploy.sh user@host                       # сборка + обновление (каталог /opt/k6-lab, служба k6-lab)
#   ./deploy.sh user@host --no-build            # взять уже собранный portable/k6-lab-linux-amd64.tar.gz
#   ./deploy.sh user@host --dir /srv/k6-lab     # другой каталог установки
#   ./deploy.sh user@host --arch arm64          # хост на ARM64
#   ./deploy.sh user@host --port 8080 --ssh-port 22 --service k6-lab
#
# Нужно на вашей машине: ssh и scp (на Windows - OpenSSH из Windows или Git for Windows).
# На хосте админка уже должна быть установлена (первый раз: распаковать архив, ./start.sh, sudo ./install-service.sh).
set -euo pipefail
cd "$(dirname "$0")/.."

TARGET=""; DIR="/opt/k6-lab"; ARCH="amd64"; PORT="8080"; SSH_PORT="22"; SERVICE="k6-lab"; BUILD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --arch) ARCH="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --ssh-port) SSH_PORT="$2"; shift 2 ;;
    --service) SERVICE="$2"; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    -h|--help) sed -n 2,13p "$0"; exit 0 ;;
    -*) echo "Неизвестный параметр: $1 (см. ./deploy.sh --help)"; exit 2 ;;
    *) TARGET="$1"; shift ;;
  esac
done
[ -n "$TARGET" ] || { echo "Укажите хост: ./deploy.sh user@host"; exit 2; }
for t in ssh scp; do command -v "$t" >/dev/null 2>&1 || { echo "✖ не найден $t"; exit 1; }; done

ARCHIVE="portable/k6-lab-linux-$ARCH.tar.gz"
if [ "$BUILD" -eq 1 ]; then
  echo "== Сборка"
  ./build.sh --offline
fi
[ -f "$ARCHIVE" ] || { echo "✖ нет $ARCHIVE (запустите без --no-build)"; exit 1; }

SSH_OPTS=(-p "$SSH_PORT")
SCP_OPTS=(-P "$SSH_PORT")
REMOTE_ARCHIVE="/tmp/$(basename "$ARCHIVE")"
echo "== Копирование на $TARGET ($(du -h "$ARCHIVE" | cut -f1))"
scp "${SCP_OPTS[@]}" "$ARCHIVE" "$TARGET:$REMOTE_ARCHIVE"
scp "${SCP_OPTS[@]}" scripts/remote-update.sh "$TARGET:/tmp/k6-lab-remote-update.sh"

echo "== Обновление на хосте (может запросить пароль sudo)"
ssh -t "${SSH_OPTS[@]}" "$TARGET" "ARCHIVE='$REMOTE_ARCHIVE' DIR='$DIR' SERVICE='$SERVICE' PORT='$PORT' bash /tmp/k6-lab-remote-update.sh; rc=\$?; rm -f /tmp/k6-lab-remote-update.sh; exit \$rc"
