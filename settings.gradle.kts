rootProject.name = "ArkitektCommand"

// Vendored zarr-java sources, built as a Gradle subproject (see zarr-java/build.gradle.kts).
include(":zarr-java")

// arkitekt-kotlin (the app, the login, the agent) is built from source, as a composite build:
// from the checkout beside this one, or from one inside it (where CI puts it).
val arkitektKotlin = listOf("../arkitekt-kotlin", "arkitekt-kotlin").map(::file).firstOrNull { it.isDirectory }
    ?: error("arkitekt-kotlin is not checked out: clone https://github.com/arkitektio/arkitekt-kotlin beside this repository")
includeBuild(arkitektKotlin)
