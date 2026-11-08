import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    `java-library`
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":platform-starter"))
    implementation(project(":events"))

    implementation(libs.spring.cloud.starter.gateway)
    implementation(libs.spring.cloud.starter.vault)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.spring.boot.starter.data.redis.reactive)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.jtokkit)
    implementation(libs.jackson.databind)
    implementation(libs.cel)
    implementation(libs.springdoc.webflux)
    runtimeOnly(libs.micrometer.prometheus)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<BootJar>("bootJar") {
    archiveFileName = "gateway.jar"
}

tasks.test {
    systemProperty("repo.root", rootDir.path)
    systemProperty("openapi.update", providers.gradleProperty("openapi.update").getOrElse("false"))
}
