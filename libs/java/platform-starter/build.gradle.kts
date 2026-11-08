plugins {
    `java-library`
}

dependencies {
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.web)
    api(libs.micrometer.tracing)
    api(libs.cel)
    compileOnly(libs.spring.jdbc)
}
