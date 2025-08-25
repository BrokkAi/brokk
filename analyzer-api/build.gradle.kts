plugins {
    java
}

group = "io.github.jbellis"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Minimal dependencies for analyzer API
    compileOnly(libs.checker.qual)
    compileOnly(libs.jetbrains.annotations)

    // Console and logging
    implementation(libs.bundles.logging)

    // For JSON serialization interfaces (used by CodeUnit)
    runtimeOnly(libs.jackson.databind)
    api(libs.jackson.annotations)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "-parameters",
        "-g:source,lines,vars"
    ))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
