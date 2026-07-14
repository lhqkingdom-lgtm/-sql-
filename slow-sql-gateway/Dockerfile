FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/slow-sql-gateway-5.0.0.jar app.jar
COPY src/main/resources/rules.yaml rules.yaml
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=docker"]
