// fraud-service — LangChain4j AI triage service. LangChain4j + Ollama wiring arrives in
// Phase 3. Phase 0 is a bootable skeleton exposing only Actuator health/metrics.
dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)

    testImplementation(libs.spring.boot.starter.test)
}
