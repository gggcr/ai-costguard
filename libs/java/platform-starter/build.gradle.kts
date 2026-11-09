plugins {
    `java-library`
}

dependencies {
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.web)
    api(libs.micrometer.tracing)
    api(libs.cel)
    compileOnly(libs.spring.jdbc)
    // The services bring Logback with Spring Boot; the starter only adds a layout to it.
    compileOnly(platform(libs.spring.boot.bom))
    compileOnly(libs.logback.classic)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    systemProperty("repo.root", rootDir.path)
}
