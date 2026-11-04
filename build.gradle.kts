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
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
