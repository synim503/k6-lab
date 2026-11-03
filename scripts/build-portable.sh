#!/bin/bash
# Собирает портативный комплект k6-lab ОДНОЙ командой: админка (jar) + агент на Go (linux-amd64/arm64) + k6 + k6 с популярными
# расширениями xk6 + скрипты запуска/установки службы. Результат: portable/k6-lab-portable.zip
#
# По умолчанию НИЧЕГО НЕ СКАЧИВАЕТСЯ: k6 и k6 с расширениями берутся из каталога vendor/ репозитория (Git LFS),
# Go-зависимости агента лежат в agent-go/vendor. Для сборки в изолированной сети нужны только JDK и Go
# (и локальный кеш Maven: ~/.m2, см. --offline).
#
#   ./build.sh                    # сборка из того, что лежит в репозитории
#   ./build.sh --online           # Maven из интернета/кеша ~/.m2 вместо vendor/maven (по умолчанию берётся vendor/maven)
#   ./build.sh --offline          # mvnw в офлайн-режиме (зависимости только из ~/.m2); нужен только без vendor/maven
#   ./build.sh --with-tests       # перед сборкой прогнать все тесты (./mvnw verify)
#   ./build.sh --with-jre         # добавить JRE 21 для Java-агента (для Go-агента не нужна; требует интернет)
#   ./build.sh --download         # ТОЛЬКО на машине с интернетом: скачать k6, собрать xk6 и обновить vendor/
#   ./build.sh --k6 v1.7.1        # версия k6 (используется с --download)
#   ./build.sh --k6-dir ДИР       # взять k6 из другого каталога (k6-linux-amd64, k6-linux-arm64)
#   ./build.sh --xk6-dir ДИР      # взять k6 с расширениями из другого каталога (xk6-linux-amd64, xk6-linux-arm64)
#   ./build.sh --check            # только проверить, что установлено всё необходимое
#
# Нужно: JDK 17+ и Go 1.22+. Для --download дополнительно curl, tar (xk6 ставится автоматически).
set -euo pipefail
cd "$(dirname "$0")/.."

K6_DIR="vendor/k6"; XK6_DIR="vendor/xk6"; K6_VERSION="v1.7.1"
WITH_TESTS=0; WITH_JRE=0; CHECK=0; DOWNLOAD=0; OFFLINE=0; ONLINE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --k6) K6_VERSION="$2"; shift 2 ;;
    --k6-dir) K6_DIR="$2"; shift 2 ;;
    --xk6-dir) XK6_DIR="$2"; shift 2 ;;
    --download) DOWNLOAD=1; shift ;;
    --offline) OFFLINE=1; shift ;;
    --online) ONLINE=1; shift ;;
    --with-tests) WITH_TESTS=1; shift ;;
    --with-jre) WITH_JRE=1; shift ;;
    --check) CHECK=1; shift ;;
    -h|--help) sed -n 2,20p "$0"; exit 0 ;;
    *) echo "Неизвестный параметр: $1 (см. ./build.sh --help)"; exit 2 ;;
  esac
done
K6_NUM="${K6_VERSION#v}"
OUT=portable/k6-lab
DIST="$OUT/dist"
MVN="./mvnw"
[ "$OFFLINE" -eq 1 ] && MVN="./mvnw -o"

# ---------------------------------------------------------------- проверка окружения
fail=0
need() { if ! command -v "$1" >/dev/null 2>&1; then echo "✖ не найден $1: $2"; fail=1; else echo "✔ $1"; fi; }
need java "поставьте JDK 17 или новее"
need go "поставьте Go 1.22+ - на нём собирается агент"
if [ "$DOWNLOAD" -eq 1 ]; then
  need curl "нужен для скачивания k6"
  need tar "нужен для распаковки k6"
fi
if command -v java >/dev/null 2>&1; then
  JV="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
  if [ -n "$JV" ] && [ "$JV" -lt 17 ]; then echo "✖ Java $JV: нужна 17 или новее"; fail=1; else echo "  Java $JV"; fi
fi
if [ "$DOWNLOAD" -eq 0 ]; then
  for f in "$K6_DIR/k6-linux-amd64" "$K6_DIR/k6-linux-arm64"; do
    if [ ! -f "$f" ]; then echo "✖ нет $f (запустите ./build.sh --download на машине с интернетом или положите бинарь)"; fail=1; fi
    # файл-заглушка Git LFS вместо бинаря: репозиторий склонирован без git lfs
    if [ -f "$f" ] && [ "$(wc -c < "$f")" -lt 1000000 ]; then echo "✖ $f - заглушка Git LFS. Выполните: git lfs install && git lfs pull"; fail=1; fi
  done
fi
if [ "$fail" -ne 0 ]; then exit 1; fi
if [ "$CHECK" -eq 1 ]; then echo "Окружение в порядке."; exit 0; fi

# Расширения xk6. Версии ЗАКРЕПЛЕНЫ: последние релизы расширений собираются под k6 v2 и в бинаре k6 1.x молча не регистрируются
# (проверено: `k6 version` не показывает их). Эти версии проверены на k6 1.7.1 импортом всех модулей.
XK6_EXTENSIONS="${XK6_EXTENSIONS:-github.com/mostafa/xk6-kafka@v1.3.0 github.com/grafana/xk6-sql@v1.0.5 github.com/grafana/xk6-sql-driver-postgres@v0.1.1 github.com/grafana/xk6-sql-driver-mysql@v0.2.1 github.com/grafana/xk6-faker@v0.4.4 github.com/pmalhaire/xk6-mqtt@v0.40.3 github.com/phymbert/xk6-sse@v0.1.11}"

# ---------------------------------------------------------------- обновление vendor/ (только с интернетом)
if [ "$DOWNLOAD" -eq 1 ]; then
  echo "== Скачивание k6 $K6_VERSION в vendor/ (нужен интернет)"
  mkdir -p vendor/k6 vendor/xk6
  for arch in amd64 arm64; do
    tmp="$(mktemp -d)"
    curl -fsSL "https://github.com/grafana/k6/releases/download/${K6_VERSION}/k6-${K6_VERSION}-linux-${arch}.tar.gz" \
      | tar -xz -C "$tmp" --strip-components=1 "k6-${K6_VERSION}-linux-${arch}/k6"
    mv "$tmp/k6" "vendor/k6/k6-linux-$arch"; rm -rf "$tmp"
  done
  if ! command -v xk6 >/dev/null 2>&1; then
    echo "== Установка xk6"
    go install go.k6.io/xk6/cmd/xk6@latest
    export PATH="$PATH:$(go env GOPATH)/bin"
  fi
  WITH=""
  for e in $XK6_EXTENSIONS; do WITH="$WITH --with $e"; done
  for arch in amd64 arm64; do
    GOOS=linux GOARCH=$arch CGO_ENABLED=0 xk6 build "$K6_VERSION" $WITH --output "vendor/xk6/xk6-linux-$arch"
  done
  (cd vendor && sha256sum k6/* xk6/* > SHA256SUMS)
  echo "vendor/ обновлён. Закоммитьте изменения (файлы хранятся в Git LFS)."
  K6_DIR="vendor/k6"; XK6_DIR="vendor/xk6"
fi

# Maven: если в репозитории лежит vendor/maven (Git LFS) - собираем встроенным Maven и локальным репозиторием зависимостей, ничего
# не скачивая. Иначе (нет vendor/maven, клонировали без LFS, или --online) - ./mvnw (нужен интернет или кеш ~/.m2).
VM_ZIP="vendor/maven/apache-maven-3.9.11-bin.zip"; VM_REPO="vendor/maven/m2-repo.tar.gz"
if [ "$ONLINE" -eq 0 ] && [ -f "$VM_REPO" ] && [ "$(wc -c < "$VM_REPO")" -gt 1000000 ] && [ -f "$VM_ZIP" ]; then
  echo "== Maven из vendor/maven (без интернета)"
  if [ ! -f .tools/apache-maven-3.9.11/bin/mvn ]; then mkdir -p .tools && (cd .tools && jar xf "../$VM_ZIP"); fi
  if [ ! -f .m2-vendor/.ready ]; then rm -rf .m2-vendor; mkdir -p .m2-vendor && tar -xzf "$VM_REPO" -C .m2-vendor && touch .m2-vendor/.ready; fi
  MVN="bash .tools/apache-maven-3.9.11/bin/mvn -o -Dmaven.repo.local=$PWD/.m2-vendor"
fi

rm -rf portable
mkdir -p "$DIST/agent/linux-amd64" "$DIST/agent/linux-arm64" "$DIST/k6/linux-amd64" "$DIST/k6/linux-arm64" "$DIST/jre"

# ---------------------------------------------------------------- админка
if [ "$WITH_TESTS" -eq 1 ]; then
  echo "== Тесты ($MVN verify)"
  $MVN -q verify
fi
echo "== Сборка админки"
$MVN -q -DskipTests package
cp admin-server/target/k6-lab-admin.jar "$OUT/"

# ---------------------------------------------------------------- агент на Go (зависимости - agent-go/vendor, без сети)
echo "== Агент на Go (статический бинарь, JRE на генераторах не нужна)"
AGENT_VERSION="$(grep -m1 -o '<version>0\.[^<]*</version>' pom.xml | sed 's/<[^>]*>//g')"
AGENT_VERSION="${AGENT_VERSION:-dev}"
# к версии добавляем идентификатор сборки (коммит; для изменённого дерева - ещё время): новый бинарь агента с тем же номером версии
# иначе не импортировался бы как новый дистрибутив, а генераторы получали бы старого агента
GIT_ID="$(git rev-parse --short HEAD 2>/dev/null || echo local)"
if [ -n "$(git status --porcelain 2>/dev/null)" ]; then GIT_ID="$GIT_ID-$(date +%Y%m%d%H%M)"; fi
AGENT_VERSION="$AGENT_VERSION+$GIT_ID"
for arch in amd64 arm64; do
  (cd agent-go && GOFLAGS=-mod=vendor GOOS=linux GOARCH=$arch CGO_ENABLED=0 go build -trimpath \
     -ldflags "-s -w -X main.version=$AGENT_VERSION" -o "../$DIST/agent/linux-$arch/k6-agent" .)
done

# ---------------------------------------------------------------- k6 и k6 с расширениями: копируем готовые
echo "== k6 и k6 с расширениями (из $K6_DIR и $XK6_DIR)"
for arch in amd64 arm64; do
  cp "$K6_DIR/k6-linux-$arch" "$DIST/k6/linux-$arch/k6"
  if [ -f "$XK6_DIR/xk6-linux-$arch" ]; then
    mkdir -p "$DIST/xk6/full/linux-$arch"
    cp "$XK6_DIR/xk6-linux-$arch" "$DIST/xk6/full/linux-$arch/k6"
  else
    echo "  (нет $XK6_DIR/xk6-linux-$arch - сборка без расширений для $arch)"
  fi
done

if [ "$WITH_JRE" -eq 1 ]; then
  echo "== JRE 21 для Java-агента (Temurin, нужен интернет)"
  for pair in "x64:linux-amd64" "aarch64:linux-arm64"; do
    a="${pair%%:*}"; d="${pair##*:}"
    mkdir -p "$DIST/jre/$d"
    curl -fsSL -o "$DIST/jre/$d/temurin-jre21.tar.gz" "https://api.adoptium.net/v3/binary/latest/21/ga/linux/${a}/jre/hotspot/normal/eclipse"
  done
fi

# ---------------------------------------------------------------- упаковка (утилита на Go, зависимостей нет)
cp scripts/portable/start.sh scripts/portable/start.bat scripts/portable/install-service.sh scripts/portable/uninstall-service.sh \
   scripts/portable/install-service.bat scripts/portable/uninstall-service.bat scripts/portable/README-PORTABLE.txt "$OUT/"
chmod +x "$OUT/"*.sh
echo "== manifest.json и архив"
(cd agent-go && GOFLAGS=-mod=vendor go run ./tools/pack "../portable" "$K6_NUM" "$AGENT_VERSION" "../vendor/jre")
echo
echo "Готово:"
echo "  portable/k6-lab-portable.zip           - для Windows (нужна Java 17+) и любой ОС с Java"
echo "  portable/k6-lab-linux-amd64.tar.gz     - для Linux x86_64, JRE внутри (Java ставить не нужно)"
echo "  portable/k6-lab-linux-arm64.tar.gz     - для Linux ARM64, JRE внутри"
