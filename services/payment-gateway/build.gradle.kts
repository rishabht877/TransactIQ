// payment-gateway — REST edge. Accepts payments (Idempotency-Key), persists them, and
// publishes PaymentRequested. Phase 1: persist + publish. Phase 2: Redis idempotency +
// transactional outbox + relay.
dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.kafka)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.mysql.connector)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.awaitility)
}

// Shared Flyway migrations live once at the repo root (db-migrations/db/migration). Add that
// directory to this module's resources so they resolve on the classpath as db/migration/*.sql.
sourceSets {
    named("main") {
        resources {
            srcDir(rootProject.file("db-migrations"))
        }
    }
}
