#!/usr/bin/env bash
# Клонирует (или обновляет) k6-lab с GitVerse, собирает проект локально и копирует готовый архив в указанный каталог.
#
# Использование:
#   bash clone-and-build.sh                     # соберёт ./k6-lab-linux-amd64.tar.gz (нужны JDK 17+, Go 1.22+, git-lfs)
#   bash clone-and-build.sh --out /data         # положит готовый архив в /data/k6-lab-linux-amd64.tar.gz
#   bash clone-and-build.sh --jar-only          # соберёт только ./k6-lab-admin.jar (Go не нужен, только JDK 17+)
#   bash clone-and-build.sh --online            # Maven из интернета/кеша ~/.m2 вместо vendor/maven
#   bash clone-and-build.sh --repo URL          # другой git URL (по умолчанию https://gitverse.ru/503/k6-lab.git)
set -euo pipefail

REPO_URL="${K6LAB_REPO_URL:-https://gitverse.ru/503/k6-lab.git}"
BRANCH="${K6LAB_BRANCH:-main}"
OUT_DIR="$(pwd)"
WORK_DIR=""
JAR_ONLY=0
ONLINE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT_DIR="$2"; shift 2 ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    --repo) REPO_URL="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    --jar-only) JAR_ONLY=1; shift ;;
    --online) ONLINE=1; shift ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "Неизвестный аргумент: $1 (см. --help)" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "✖ Не найден $1: $2" >&2
    exit 1
  fi
}

need git "установите git"
need javac "установите JDK 17+ (не JRE — нужен компилятор javac)"
JAVA_VER="$(javac -version 2>&1 | sed -n 's/.* \([0-9][0-9]*\).*/\1/p' | head -n1)"
if [ -n "$JAVA_VER" ] && [ "$JAVA_VER" -lt 17 ]; then
  echo "✖ Найдена JDK $JAVA_VER, требуется JDK 17 или новее" >&2
  exit 1
fi

if [ "$JAR_ONLY" -eq 0 ]; then
  if ! command -v go >/dev/null 2>&1; then
    echo "⚠ Go не найден — переключаюсь в режим --jar-only (соберу только k6-lab-admin.jar)."
    echo "  Для полного архива с Go-агентом и JRE установите Go 1.22+ и запустите скрипт снова."
    JAR_ONLY=1
  fi
fi

# Если скрипт запущен внутри уже склонированного репозитория (из корня или из scripts/) и --work-dir не задан — используем корень репозитория,
# иначе клонируем в $OUT_DIR/k6-lab-src.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "$WORK_DIR" ]; then
  if [ -f "./pom.xml" ] && [ -f "./scripts/build-portable.sh" ]; then
    WORK_DIR="$(pwd)"
  elif [ -f "$SCRIPT_DIR/../pom.xml" ] && [ -f "$SCRIPT_DIR/build-portable.sh" ]; then
    WORK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
    # если ранее из scripts/ случайно создали scripts/k6-lab-src — убираем дубликат
    rm -rf "$SCRIPT_DIR/k6-lab-src" 2>/dev/null || true
  else
    WORK_DIR="$OUT_DIR/k6-lab-src"
  fi
fi

echo "== [1/3] Получение исходников ($REPO_URL, ветка $BRANCH)"
if [ -d "$WORK_DIR/.git" ]; then
  cd "$WORK_DIR"
  git fetch origin "$BRANCH" || true
  git checkout "$BRANCH"
  git pull --ff-only origin "$BRANCH" || true
else
  GIT_LFS_SKIP_SMUDGE=1 git clone --branch "$BRANCH" --single-branch "$REPO_URL" "$WORK_DIR"
  cd "$WORK_DIR"
fi

if [ "$JAR_ONLY" -eq 1 ]; then
  echo "== [2/3] Сборка только k6-lab-admin.jar"
  APP_BUILD="$(git describe --tags --always 2>/dev/null || echo local)"
  MVN="./mvnw -DskipTests -Dapp.build=$APP_BUILD"
  if [ "$ONLINE" -eq 0 ] && command -v git-lfs >/dev/null 2>&1; then
    git lfs pull --include="vendor/maven/*"
    VM_ZIP="vendor/maven/apache-maven-3.9.11-bin.zip"
    VM_REPO="vendor/maven/m2-repo.tar.gz"
    if [ -f "$VM_REPO" ] && [ "$(wc -c < "$VM_REPO")" -gt 1000000 ] && [ -f "$VM_ZIP" ]; then
      echo "   Использую офлайн Maven из vendor/maven"
      if [ ! -f .tools/apache-maven-3.9.11/bin/mvn ]; then mkdir -p .tools && (cd .tools && jar xf "../$VM_ZIP"); fi
      if [ ! -f .m2-vendor/.ready ]; then
        rm -rf .m2-vendor && mkdir -p .m2-vendor && tar -xzf "$VM_REPO" -C .m2-vendor && touch .m2-vendor/.ready
      fi
      find .m2-vendor \( -name "_remote.repositories" -o -name "resolver-status.properties" \) -delete 2>/dev/null || true
      for f in $(find .m2-vendor -name "maven-metadata-central-https.xml" 2>/dev/null); do
        dir="${f%/*}"
        [ -f "$dir/maven-metadata-central.xml" ] || cp "$f" "$dir/maven-metadata-central.xml"
        [ -f "$dir/maven-metadata-local.xml" ] || cp "$f" "$dir/maven-metadata-local.xml"
      done
      printf '<settings/>\n' > .m2-vendor/empty-settings.xml
      HOST_PWD="$(pwd -W 2>/dev/null || pwd)"
      MVN="bash .tools/apache-maven-3.9.11/bin/mvn -o -Dmaven.legacyLocalRepo=true -s $HOST_PWD/.m2-vendor/empty-settings.xml -Dmaven.repo.local=$HOST_PWD/.m2-vendor -DskipTests -Dapp.build=$APP_BUILD"
    fi
  fi
  chmod +x mvnw 2>/dev/null || true
  $MVN -pl admin-server -am package
  echo "== [3/3] Копирование результата"
  cp -f admin-server/target/k6-lab-admin.jar "$OUT_DIR/k6-lab-admin.jar"
  echo
  echo "Готово: $OUT_DIR/k6-lab-admin.jar"
  sha256sum "$OUT_DIR/k6-lab-admin.jar" || true
  echo "Для обновления админки замените /data/k6-lab/k6-lab-admin.jar и перезапустите сервис."
  exit 0
fi

need git-lfs "установите git-lfs (apt install git-lfs / dnf install git-lfs)"
echo "== [2/3] Загрузка бинарей из Git LFS и сборка портативного архива"
LFS_INCLUDE="vendor/k6/*,vendor/xk6/*,vendor/jre/temurin-jre17-linux-amd64.tar.gz"
if [ "$ONLINE" -eq 0 ]; then
  LFS_INCLUDE="$LFS_INCLUDE,vendor/maven/*"
fi
git lfs pull --include="$LFS_INCLUDE"
chmod +x build.sh mvnw scripts/*.sh scripts/portable/*.sh

BUILD_ARGS=()
if [ "$ONLINE" -eq 1 ]; then
  BUILD_ARGS+=(--online)
fi
bash ./build.sh "${BUILD_ARGS[@]}"

echo "== [3/3] Копирование архива в $OUT_DIR"
cp -f portable/k6-lab-linux-amd64.tar.gz "$OUT_DIR/k6-lab-linux-amd64.tar.gz"
cp -f portable/k6-lab/k6-lab-admin.jar "$OUT_DIR/k6-lab-admin.jar"
(cd "$OUT_DIR" && sha256sum k6-lab-linux-amd64.tar.gz > SHA256SUMS)

echo
echo "Готово:"
ls -lh "$OUT_DIR/k6-lab-linux-amd64.tar.gz" "$OUT_DIR/k6-lab-admin.jar"
cat "$OUT_DIR/SHA256SUMS"
echo
echo "Распаковка на сервере:"
echo "  tar -xzf $OUT_DIR/k6-lab-linux-amd64.tar.gz -C /data/"
