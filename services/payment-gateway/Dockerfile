# Multi-stage build. BUILD CONTEXT MUST BE THE REPO ROOT (the Gradle build is multi-module and
# needs settings.gradle.kts + all modules + db-migrations/):
#   docker build -f services/payment-gateway/Dockerfile -t transactiq/payment-gateway:local .
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :services:payment-gateway:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/payment-gateway/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
