// payment-processor — Kafka consumer. Consume/dedup/outbox logic arrives in Phases 1–2.
// spring-kafka is included now (dependency present is fine); with no broker config it may
// log noisy connection-retry warnings on boot — harmless, does not affect health.
dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.kafka)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
}
