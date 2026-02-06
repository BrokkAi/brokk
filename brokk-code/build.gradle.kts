plugins {
    base
}

val pythonExecutable = providers.gradleProperty("brokkCodePython").orElse("python3")

tasks.register<Exec>("test") {
    group = "verification"
    description = "Runs brokk-code Python tests via pytest"
    workingDir = projectDir
    commandLine(pythonExecutable.get(), "-m", "pytest", "-q")
    onlyIf {
        file("tests").exists()
    }
}

tasks.named("check") {
    dependsOn("test")
}
