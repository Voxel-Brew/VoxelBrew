pluginManagement {
    val bomDirectory =
        providers.environmentVariable("VOXELBREW_BOM_DIR").orNull?.let { file(it) }
            ?: listOf("../BOM", ".tools/bom")
                .map { file(it) }
                .firstOrNull { it.resolve("spotbugs-conventions").isDirectory }
    require(bomDirectory?.resolve("spotbugs-conventions")?.isDirectory == true) {
        "Set VOXELBREW_BOM_DIR to a VoxelBrew BOM checkout with SpotBugs conventions."
    }
    includeBuild(checkNotNull(bomDirectory).resolve("spotbugs-conventions"))
}

rootProject.name = "voxelbrew"

// A direct app invocation in the workspace uses the same projects as the parent composite.
if (gradle.parent == null) {
    val bom = file("../BOM")
    val apis =
        listOf("model", "import", "export", "printer", "slicer").associateWith {
            file("../api/$it")
        }
    if (
        bom.resolve("settings.gradle.kts").isFile &&
            apis.values.all { it.resolve("settings.gradle.kts").isFile }
    ) {
        includeBuild(bom) { name = "bom" }
        apis.forEach { (api, directory) ->
            includeBuild(directory) {
                name = api
                dependencySubstitution {
                    substitute(module("dev.voxelbrew.api:$api")).using(project(":"))
                }
            }
        }
    }
}

include("util", "engine", "gui", "cli")

// Keep API implementation identities distinct from the independent API builds.
listOf("importer", "exporter", "slicer", "printer").forEach { module ->
    include("$module-impl")
    project(":$module-impl").projectDir = file(module)
}
