rootProject.name = "voxelbrew"

include("util", "engine", "gui", "cli")

// Keep API implementation identities distinct from the independent API builds.
listOf("importer", "exporter", "slicer", "printer").forEach { module ->
    include("$module-impl")
    project(":$module-impl").projectDir = file(module)
}
