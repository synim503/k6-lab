#!/bin/bash
# Сборка всего проекта одной командой (см. ./build.sh --help). Результат: portable/k6-lab-portable.zip
exec "$(cd "$(dirname "$0")" && pwd)/scripts/build-portable.sh" "$@"
