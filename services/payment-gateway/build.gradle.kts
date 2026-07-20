// payment-gateway — REST edge. Business logic (idempotency, outbox, publish) arrives in
// Phases 1–2; Phase 0 is a bootable skeleton exposing only Actuator health/metrics.
dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)

    testImplementation(libs.spring.boot.starter.test)
}
