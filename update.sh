#!/bin/bash
# Обновление установленной админки ПРЯМО НА ХОСТЕ: обновить исходники, собрать, заменить установленную версию, перезапустить.
# Всё нужное для сборки лежит в репозитории (vendor/), интернет не нужен.
#
#   ./update.sh                       # git pull + сборка + замена в /opt/k6-lab (служба k6-lab)
#   ./update.sh --no-pull             # исходники уже обновлены вручную (например, скопированы с другой машины)
#   ./update.sh --no-build            # использовать уже собранный portable/k6-lab-linux-<arch>.tar.gz
#   ./update.sh --dir /srv/k6-lab --service k6-lab --port 8080
#
# На хосте должны быть JDK 17+ и Go 1.22+ (ставятся один раз). Права: sudo для службы (если не root).
# Настройки (config/) и данные (data/) сохраняются; прежняя версия остаётся в <каталог>.prev; при неудачном старте - откат.
set -euo pipefail
cd "$(dirname "$0")"

DIR="/opt/k6-lab"; SERVICE="k6-lab"; PORT="8080"; PULL=1; BUILD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --service) SERVICE="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --no-pull) PULL=0; shift ;;
    --no-build) BUILD=0; shift ;;
    -h|--help) sed -n 2,11p "$0"; exit 0 ;;
    *) echo "Неизвестный параметр: $1 (см. ./update.sh --help)"; exit 2 ;;
  esac
done

case "$(uname -m)" in
  x86_64|amd64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) echo "Неподдерживаемая архитектура: $(uname -m)"; exit 1 ;;
esac

if [ "$PULL" -eq 1 ]; then
  if [ -d .git ]; then
    echo "== Обновление исходников (git pull)"
    git pull --ff-only
    command -v git-lfs >/dev/null 2>&1 && git lfs pull || true
  else
    echo "(не git-клон - исходники не обновляю; используйте --no-pull, если обновили их вручную)"
  fi
fi

if [ "$BUILD" -eq 1 ]; then
  echo "== Сборка"
  ./build.sh
fi

ARCHIVE="portable/k6-lab-linux-$ARCH.tar.gz"
[ -f "$ARCHIVE" ] || { echo "✖ нет $ARCHIVE (запустите без --no-build)"; exit 1; }
echo "== Замена установленной версии ($DIR)"
ARCHIVE="$PWD/$ARCHIVE" DIR="$DIR" SERVICE="$SERVICE" PORT="$PORT" bash scripts/remote-update.sh
