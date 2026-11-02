# Dev-образ админки: собранный fat jar (mvn -DskipTests package) + JRE 21.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY admin-server/target/k6-lab-admin.jar /app/app.jar
EXPOSE 8080 47331
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
