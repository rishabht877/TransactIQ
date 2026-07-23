// fraud-service — deterministic fraud rules + LangChain4j LLM triage (Ollama by default).
// Positioned as a triage/explanation layer: the rules are the authoritative backbone and set a
// severity floor; the LLM produces the final call + human-readable reasoning on top.
dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.langchain4j)
    implementation(libs.langchain4j.ollama)

    testImplementation(libs.spring.boot.starter.test)
}
