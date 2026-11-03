#!/bin/sh
# k6-lab: запуск портативной версии (Linux/macOS). Нужна Java 17+ (каталог jre/ рядом имеет приоритет).
cd "$(dirname "$0")"
JAVA=java
[ -x jre/bin/java ] && JAVA=jre/bin/java
exec "$JAVA" -Xmx1g -jar k6-lab-admin.jar
