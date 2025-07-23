rootProject.name = "brokk"

include("analyzer-api", "joern-analyzers", "app")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}