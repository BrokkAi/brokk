rootProject.name = "brokk"

include("app")
include("errorprone-checks")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}
