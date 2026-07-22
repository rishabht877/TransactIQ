// Root build. Declares the Spring plugins (but does NOT apply them here) so subprojects can
// apply them without re-declaring versions. All shared configuration lives in subprojects{}.
plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

subprojects {
    // Every service is a Spring Boot app.
    apply(plugin = "java")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")

    group = "com.transactiq"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    // Pin the Java toolchain to 21 for every module (locked tech decision).
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Integration tests (*IT) run against the docker-compose infra (MySQL/Redis) + an
        // in-JVM EmbeddedKafka broker, so `docker compose up` must be running first. See the
        // Javadoc on ReplayIdempotencyIT for why we use compose infra rather than Testcontainers
        // on this machine (Docker Desktop 29 drops JDBC connections to ephemeral container ports).
    }
}
