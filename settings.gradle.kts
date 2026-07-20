rootProject.name = "transactiq"

// Backend service modules. The React dashboard (dashboard/) is intentionally NOT a Gradle
// module — it is a Vite/Node project built in Phase 5.
include(
    "services:payment-gateway",
    "services:payment-processor",
    "services:fraud-service",
)
