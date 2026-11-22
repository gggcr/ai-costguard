plugins {
    alias(libs.plugins.spring.boot) apply false
}

val catalog = libs

subprojects {
    apply(plugin = "java-library")

    group = "ai.costguard"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    dependencies {
        "implementation"(platform(catalog.spring.boot.bom))
        "implementation"(platform(catalog.spring.cloud.bom))
        "implementation"(platform(catalog.spring.modulith.bom))
        // Security floors (gradle/libs.versions.toml): the higher version of a BOM and a floor wins
        "implementation"(platform(catalog.jackson2.bom))
        "implementation"(platform(catalog.opentelemetry.bom))
        constraints {
            "implementation"(catalog.snappy.java) { because("GHSA-gwq9-9ffj-mvrv, GHSA-q233-g92q-qxp6 (Kafka compression)") }
            "implementation"(catalog.lz4.java) { because("GHSA-mcr4-qmvw-px4g, GHSA-gm45-99xc-r7wv (Kafka compression)") }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
