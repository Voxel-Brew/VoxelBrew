rootProject.name = "voxelbrew"

include("util", "engine", "gui", "cli")

// Keep API implementation identities distinct from the independent API builds.
listOf("importer", "exporter", "slicer", "printer").forEach { module ->
    include("$module-impl")
    project(":$module-impl").projectDir = file(module)
}

// The export workflow PR builds against its matching Slicer API contract.
if (file(".tools/api-slicer/settings.gradle.kts").isFile) {
    includeBuild(".tools/api-slicer")
}
