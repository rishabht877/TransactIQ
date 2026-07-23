# Build from the REPO ROOT:
#   docker build -f services/payment-processor/Dockerfile -t transactiq/payment-processor:local .
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :services:payment-processor:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/payment-processor/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
