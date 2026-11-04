plugins {
    `java-library`
}

val avroTools = configurations.create("avroTools")

dependencies {
    api(libs.avro)
    avroTools(libs.avro.tools)
}

val schemaDir = rootProject.layout.projectDirectory.dir("schemas/avro")
val generatedDir = layout.buildDirectory.dir("generated/avro/java")

val generateAvro = tasks.register<JavaExec>("generateAvro") {
    description = "Generates Java classes from schemas/avro."
    classpath = avroTools
    mainClass = "org.apache.avro.tool.Main"
    inputs.dir(schemaDir)
    outputs.dir(generatedDir)
    args(
        "compile", "-string", "-bigDecimal", "schema",
        schemaDir.asFile.path,
        generatedDir.get().asFile.path,
    )
    // No schemas yet is a valid state: the tool would fail on an empty input.
    onlyIf { !schemaDir.asFileTree.matching { include("**/*.avsc") }.isEmpty }
}

sourceSets {
    main {
        java.srcDir(generateAvro)
    }
}
