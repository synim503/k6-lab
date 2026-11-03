# Бинари k6, лежащие в репозитории

Чтобы сборка и развёртывание работали в изолированной локальной сети, готовые бинари k6 хранятся здесь (Git LFS).
`./build.sh` берёт их отсюда и **ничего не скачивает**.

| Файл | Что это |
|---|---|
| `k6/k6-linux-amd64`, `k6/k6-linux-arm64` | k6 v1.7.1, официальный релиз grafana/k6 |
| `xk6/xk6-linux-amd64`, `xk6/xk6-linux-arm64` | k6 v1.7.1, собран xk6 с расширениями: xk6-kafka v1.3.0, xk6-sql v1.0.5 (+ драйверы postgres v0.1.1, mysql v0.2.1), xk6-faker v0.4.4, xk6-mqtt v0.40.3, xk6-sse v0.1.11 |

| `jre/temurin-jre17-linux-amd64.tar.gz`, `…-arm64.tar.gz` | Eclipse Temurin JRE 17 для Linux: встраивается в `k6-lab-linux-*.tar.gz`, чтобы на сервере админки не нужна была Java |

| `maven/apache-maven-3.9.11-bin.zip`, `maven/m2-repo.tar.gz` | Maven и локальный репозиторий всех зависимостей и плагинов, нужных для `package` (~250 МБ). `./build.sh` распаковывает их в `.tools/` и `.m2-vendor/` и собирает с `mvn -o` |

Контрольные суммы - в `SHA256SUMS` (`cd vendor && sha256sum -c SHA256SUMS`).

## Как обновить
Только на машине с интернетом:

```bash
./build.sh --download --k6 v1.8.0      # скачать k6 и пересобрать xk6, положить в vendor/
```

После этого закоммитьте обновлённые файлы. Версии расширений xk6 закреплены в `scripts/build-portable.sh`
(свежие релизы расширений собираются под k6 v2 и в бинаре k6 1.x не регистрируются).

Обновить `maven/m2-repo.tar.gz` после изменения зависимостей (с интернетом):

```bash
rm -rf /tmp/m2 && ./mvnw -Dmaven.repo.local=/tmp/m2 -DskipTests package && (cd /tmp/m2 && tar -czf - .) > vendor/maven/m2-repo.tar.gz
```
