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
    compileOnly(libs.jsr305)
    compileOnly(libs.checker.qual)
    implementation(libs.jspecify)
    implementation(libs.jetbrains.annotations)

    // For JSON serialization interfaces (used by CodeUnit)
    implementation(libs.jackson.databind)

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.junit)
    testRuntimeOnly(libs.bundles.junit.runtime)
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
