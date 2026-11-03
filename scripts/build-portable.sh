#!/bin/bash
# Собирает портативный комплект k6-lab ОДНОЙ командой: админка (jar) + агент на Go (linux-amd64/arm64) + k6 + k6 с популярными
# расширениями xk6 + скрипты запуска/установки службы. Результат: portable/k6-lab-portable.zip
#
#   ./build.sh                    # обычная сборка (тесты пропускаются)
#   ./build.sh --with-tests       # перед сборкой прогнать все тесты (./mvnw verify)
#   ./build.sh --k6 v1.7.1        # версия k6 (по умолчанию v1.7.1)
#   ./build.sh --no-xk6           # без сборки k6 с расширениями (быстрее)
#   ./build.sh --k6-dir ДИР       # взять k6 из каталога (k6-linux-amd64, k6-linux-arm64) вместо скачивания
#   ./build.sh --xk6-dir ДИР      # взять готовые k6 с расширениями (xk6-linux-amd64, xk6-linux-arm64) вместо сборки xk6
#   ./build.sh --with-jre         # добавить JRE 21 для Java-агента (для Go-агента не нужна)
#   ./build.sh --check            # только проверить, что установлено всё необходимое
#
# Нужно: JDK 17+, Go 1.22+, curl, tar. xk6 ставится автоматически (go install go.k6.io/xk6/cmd/xk6@latest).
# Переменная XK6_EXTENSIONS - свой список расширений (модули через пробел).
set -euo pipefail
cd "$(dirname "$0")/.."

K6_DIR=""; XK6_DIR=""; K6_VERSION="v1.7.1"; WITH_TESTS=0; NO_XK6=0; WITH_JRE=0; CHECK=0
while [ $# -gt 0 ]; do
  case "$1" in
    --k6) K6_VERSION="$2"; shift 2 ;;
    --k6-dir) K6_DIR="$2"; shift 2 ;;
    --xk6-dir) XK6_DIR="$2"; shift 2 ;;
    --with-tests) WITH_TESTS=1; shift ;;
    --no-xk6) NO_XK6=1; shift ;;
    --with-jre) WITH_JRE=1; shift ;;
    --check) CHECK=1; shift ;;
    v[0-9]*) K6_VERSION="$1"; shift ;;
    -h|--help) sed -n 2,16p "$0"; exit 0 ;;
    *) echo "Неизвестный параметр: $1 (см. ./build.sh --help)"; exit 2 ;;
  esac
done
K6_NUM="${K6_VERSION#v}"
OUT=portable/k6-lab
DIST="$OUT/dist"

# ---------------------------------------------------------------- проверка окружения
fail=0
need() { if ! command -v "$1" >/dev/null 2>&1; then echo "✖ не найден $1: $2"; fail=1; else echo "✔ $1"; fi; }
need java "поставьте JDK 17 или новее (https://adoptium.net)"
need go "поставьте Go 1.22+ (https://go.dev/dl) - на нём собирается агент"
if [ -z "$K6_DIR" ]; then
  need curl "нужен для скачивания k6 (или укажите --k6-dir)"
  need tar "нужен для распаковки k6"
fi
if command -v java >/dev/null 2>&1; then
  JV="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
  if [ -n "$JV" ] && [ "$JV" -lt 17 ]; then echo "✖ Java $JV: нужна 17 или новее"; fail=1; else echo "  Java $JV"; fi
fi
if [ "$fail" -ne 0 ]; then exit 1; fi
if [ "$CHECK" -eq 1 ]; then echo "Окружение в порядке."; exit 0; fi

# популярные расширения: Kafka, SQL (PostgreSQL/MySQL), faker, MQTT, SSE.
# Версии ЗАКРЕПЛЕНЫ: последние релизы расширений собираются под k6 v2 и в бинаре k6 1.x молча не регистрируются
# (проверено: `k6 version` не показывает их). Эти версии проверены на k6 1.7.1 импортом всех модулей.
XK6_EXTENSIONS="${XK6_EXTENSIONS:-github.com/mostafa/xk6-kafka@v1.3.0 github.com/grafana/xk6-sql@v1.0.5 github.com/grafana/xk6-sql-driver-postgres@v0.1.1 github.com/grafana/xk6-sql-driver-mysql@v0.2.1 github.com/grafana/xk6-faker@v0.4.4 github.com/pmalhaire/xk6-mqtt@v0.40.3 github.com/phymbert/xk6-sse@v0.1.11}"

rm -rf portable
mkdir -p "$DIST/agent/linux-amd64" "$DIST/agent/linux-arm64" "$DIST/k6/linux-amd64" "$DIST/k6/linux-arm64" "$DIST/jre"

# ---------------------------------------------------------------- админка
if [ "$WITH_TESTS" -eq 1 ]; then
  echo "== Тесты (./mvnw verify)"
  ./mvnw -q verify
fi
echo "== Сборка админки"
./mvnw -q -DskipTests package
cp admin-server/target/k6-lab-admin.jar "$OUT/"

# ---------------------------------------------------------------- агент на Go
echo "== Агент на Go (статический бинарь, JRE на генераторах не нужна)"
AGENT_VERSION="$(grep -m1 -o '<version>0\.[^<]*</version>' pom.xml | sed 's/<[^>]*>//g')"
AGENT_VERSION="${AGENT_VERSION:-dev}"
for arch in amd64 arm64; do
  (cd agent-go && GOOS=linux GOARCH=$arch CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=$AGENT_VERSION" \
     -o "../$DIST/agent/linux-$arch/k6-agent" .)
done

# ---------------------------------------------------------------- k6
echo "== k6 $K6_VERSION"
for arch in amd64 arm64; do
  if [ -n "$K6_DIR" ]; then
    cp "$K6_DIR/k6-linux-$arch" "$DIST/k6/linux-$arch/k6"
  else
    curl -fsSL "https://github.com/grafana/k6/releases/download/${K6_VERSION}/k6-${K6_VERSION}-linux-${arch}.tar.gz"       | tar -xz -C "$DIST/k6/linux-$arch" --strip-components=1 "k6-${K6_VERSION}-linux-${arch}/k6"
  fi
done

if [ -n "$XK6_DIR" ]; then
  echo "== k6 с расширениями из $XK6_DIR"
  for arch in amd64 arm64; do
    if [ -f "$XK6_DIR/xk6-linux-$arch" ]; then
      mkdir -p "$DIST/xk6/full/linux-$arch"; cp "$XK6_DIR/xk6-linux-$arch" "$DIST/xk6/full/linux-$arch/k6"
    fi
  done
  NO_XK6=1
fi

if [ "$NO_XK6" -eq 0 ]; then
  if ! command -v xk6 >/dev/null 2>&1; then
    echo "== Установка xk6"
    go install go.k6.io/xk6/cmd/xk6@latest
    export PATH="$PATH:$(go env GOPATH)/bin"
  fi
  if command -v xk6 >/dev/null 2>&1; then
    echo "== k6 с расширениями xk6: $XK6_EXTENSIONS"
    WITH=""
    for e in $XK6_EXTENSIONS; do WITH="$WITH --with $e"; done
    for arch in amd64 arm64; do
      mkdir -p "$DIST/xk6/full/linux-$arch"
      if ! GOOS=linux GOARCH=$arch CGO_ENABLED=0 xk6 build "$K6_VERSION" $WITH --output "$DIST/xk6/full/linux-$arch/k6"; then
        echo "!! xk6-сборка для $arch не удалась - пропущена"
        rm -rf "$DIST/xk6/full/linux-$arch"
      fi
    done
  else
    echo "!! xk6 недоступен (go install не удался) - сборка без расширений"
  fi
fi

if [ "$WITH_JRE" -eq 1 ]; then
  echo "== JRE 21 для Java-агента (Temurin)"
  for pair in "x64:linux-amd64" "aarch64:linux-arm64"; do
    a="${pair%%:*}"; d="${pair##*:}"
    mkdir -p "$DIST/jre/$d"
    curl -fsSL -o "$DIST/jre/$d/temurin-jre21.tar.gz" "https://api.adoptium.net/v3/binary/latest/21/ga/linux/${a}/jre/hotspot/normal/eclipse"
  done
fi

# ---------------------------------------------------------------- упаковка
cp scripts/portable/start.sh scripts/portable/start.bat scripts/portable/install-service.sh scripts/portable/uninstall-service.sh \
   scripts/portable/install-service.bat scripts/portable/uninstall-service.bat scripts/portable/README-PORTABLE.txt "$OUT/"
chmod +x "$OUT/"*.sh
echo "== manifest.json и архив"
(cd agent-go && go run ./tools/pack "../portable" "$K6_NUM" "$AGENT_VERSION")
echo
echo "Готово. Распакуйте portable/k6-lab-portable.zip и запустите start.bat / start.sh (нужна Java 17+ и PostgreSQL 16)."
