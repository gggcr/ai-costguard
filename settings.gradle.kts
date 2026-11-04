rootProject.name = "ai-costguard"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

val javaProjects = mapOf(
    "gateway" to "apps/backend/gateway",
    "core" to "apps/backend/core",
    "platform-starter" to "libs/java/platform-starter",
    "events" to "libs/java/events",
)

javaProjects.forEach { (name, path) ->
    include(name)
    project(":$name").projectDir = file(path)
}
