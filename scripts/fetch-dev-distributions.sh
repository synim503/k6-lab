#!/bin/sh
# Скачивает дистрибутивы для ЗАГРУЗКИ в раздел «Дистрибутивы» (на машине с интернетом):
#   k6 для linux-amd64 / linux-arm64 и Temurin JRE 21. Агент собирается так: mvn -DskipTests package
#   (admin-agent/target/k6-agent.jar).
# Использование: scripts/fetch-dev-distributions.sh [K6_VERSION] [OUT_DIR]
set -eu
K6_VERSION="${1:-v1.7.1}"
OUT="${2:-./dist}"
mkdir -p "$OUT"

for arch in amd64 arm64; do
  echo "k6 $K6_VERSION linux-$arch"
  curl -fsSL "https://github.com/grafana/k6/releases/download/${K6_VERSION}/k6-${K6_VERSION}-linux-${arch}.tar.gz" \
    | tar -xz -C "$OUT" --strip-components=1 "k6-${K6_VERSION}-linux-${arch}/k6"
  mv "$OUT/k6" "$OUT/k6-${K6_VERSION}-linux-${arch}"
done

for arch in x64 aarch64; do
  echo "Temurin JRE 21 linux-$arch"
  curl -fsSL -o "$OUT/temurin-jre21-linux-${arch}.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/${arch}/jre/hotspot/normal/eclipse"
done

(cd "$OUT" && sha256sum * 2>/dev/null || shasum -a 256 *)
echo "Готово: загрузите файлы в UI -> Дистрибутивы (k6: arch по имени файла; JRE: x64=linux-amd64, aarch64=linux-arm64)"
