// payment-processor — consumes payments.requested, writes final payment state to MySQL.
// Phase 1: consume + write PROCESSED (fraud stubbed APPROVE). Phase 2: processed_events
// idempotent consumer, @RetryableTopic + DLQ, transactional outbox for output events.
dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.kafka)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.mysql.connector)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.awaitility)
}

// Shared Flyway migrations (see payment-gateway build for the shared-schema rationale).
sourceSets {
    named("main") {
        resources {
            srcDir(rootProject.file("db-migrations"))
        }
    }
}
