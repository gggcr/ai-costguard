plugins {
    `java-library`
}

dependencies {
    api(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.jdbc)
}
