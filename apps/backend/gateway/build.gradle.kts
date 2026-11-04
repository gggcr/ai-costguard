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
    implementation(libs.spring.boot.starter.data.redis.reactive)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.jtokkit)
    implementation(libs.cel)
    runtimeOnly(libs.micrometer.prometheus)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.kafka)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<BootJar>("bootJar") {
    archiveFileName = "gateway.jar"
}
