#!/bin/bash
# Обновление админки на хосте одной командой (см. ./deploy.sh --help)
exec "$(cd "$(dirname "$0")" && pwd)/scripts/deploy-host.sh" "$@"
