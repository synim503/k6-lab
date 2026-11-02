#!/bin/bash
# Собирает портативный комплект k6-lab: admin jar + агент + k6 (обычный и с популярными расширениями xk6) + JRE 21
# для генераторов + скрипты запуска. Результат: portable/k6-lab-portable.zip
#
# Нужны: JDK 21+, Go 1.22+ (агент на Go), curl, tar, python; для xk6-сборки - xk6 (go install go.k6.io/xk6/cmd/xk6@latest).
# Использование: scripts/build-portable.sh [K6_VERSION=v1.7.1]
# Переменные: XK6_EXTENSIONS (список модулей через пробел), SKIP_XK6=1, WITH_JRE=1 (добавить JRE 21 для Java-агента; по умолчанию не нужна - агент на Go), SKIP_TESTS=1
set -euo pipefail
cd "$(dirname "$0")/.."
K6_VERSION="${1:-v1.7.1}"
K6_NUM="${K6_VERSION#v}"
OUT=portable/k6-lab
DIST="$OUT/dist"

# популярные расширения: Kafka, SQL (PostgreSQL/MySQL), faker, MQTT, SSE.
# Версии ЗАКРЕПЛЕНЫ: последние релизы расширений собираются под k6 v2 и в бинаре k6 1.x молча не регистрируются
# (проверено: `k6 version` не показывает их). Эти версии проверены на k6 1.7.1 импортом всех модулей.
XK6_EXTENSIONS="${XK6_EXTENSIONS:-github.com/mostafa/xk6-kafka@v1.3.0 github.com/grafana/xk6-sql@v1.0.5 github.com/grafana/xk6-sql-driver-postgres@v0.1.1 github.com/grafana/xk6-sql-driver-mysql@v0.2.1 github.com/grafana/xk6-faker@v0.4.4 github.com/pmalhaire/xk6-mqtt@v0.40.3 github.com/phymbert/xk6-sse@v0.1.11}"

rm -rf portable
mkdir -p "$DIST/agent/linux-amd64" "$DIST/agent/linux-arm64" "$DIST/k6/linux-amd64" "$DIST/k6/linux-arm64" "$DIST/jre"

echo "== Сборка jar'ов"
./mvnw -q -DskipTests package
cp admin-server/target/k6-lab-admin.jar "$OUT/"
echo "== Агент на Go (статический бинарь, без JRE на генераторах)"
AGENT_VERSION="$(grep -m1 -o '<version>0\.[^<]*</version>' pom.xml | sed 's/<[^>]*>//g')"
for arch in amd64 arm64; do
  (cd agent-go && GOOS=linux GOARCH=$arch CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=$AGENT_VERSION" -o "../$DIST/agent/linux-$arch/k6-agent" .)
done

echo "== k6 $K6_VERSION"
for arch in amd64 arm64; do
  curl -fsSL "https://github.com/grafana/k6/releases/download/${K6_VERSION}/k6-${K6_VERSION}-linux-${arch}.tar.gz" \
    | tar -xz -C "$DIST/k6/linux-$arch" --strip-components=1 "k6-${K6_VERSION}-linux-${arch}/k6"
done

XK6_OK=0
if [ "${SKIP_XK6:-0}" != "1" ] && command -v xk6 >/dev/null 2>&1; then
  echo "== xk6-сборка с расширениями: $XK6_EXTENSIONS"
  WITH=""
  for e in $XK6_EXTENSIONS; do WITH="$WITH --with $e"; done
  for arch in amd64 arm64; do
    mkdir -p "$DIST/xk6/full/linux-$arch"
    if GOOS=linux GOARCH=$arch CGO_ENABLED=0 xk6 build "$K6_VERSION" $WITH --output "$DIST/xk6/full/linux-$arch/k6"; then
      XK6_OK=1
    else
      echo "!! xk6-сборка для $arch не удалась - пропущена"
      rm -rf "$DIST/xk6/full/linux-$arch"
    fi
  done
else
  echo "== xk6 пропущен (нет xk6 в PATH или SKIP_XK6=1)"
fi

if [ "${WITH_JRE:-0}" = "1" ]; then
  echo "== JRE 21 для генераторов (Temurin)"
  for pair in "x64:linux-amd64" "aarch64:linux-arm64"; do
    a="${pair%%:*}"; d="${pair##*:}"
    mkdir -p "$DIST/jre/$d"
    curl -fsSL -o "$DIST/jre/$d/temurin-jre21.tar.gz" "https://api.adoptium.net/v3/binary/latest/21/ga/linux/${a}/jre/hotspot/normal/eclipse"
  done
fi

cp scripts/portable/start.sh scripts/portable/start.bat scripts/portable/install-service.sh scripts/portable/uninstall-service.sh scripts/portable/install-service.bat scripts/portable/uninstall-service.bat scripts/portable/README-PORTABLE.txt "$OUT/"
chmod +x "$OUT/"*.sh

echo "== manifest.json и архив"
if python3 -c "" 2>/dev/null; then PY=python3; else PY=python; fi
"$PY" scripts/portable/pack.py "$DIST" "$K6_NUM" "$AGENT_VERSION"
