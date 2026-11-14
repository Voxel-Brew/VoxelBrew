plugins { application }

tasks.named<Test>("test") {
    dependsOn(":engine:buildExporterFixture")
    val fixture = rootProject.file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar")
    inputs.file(fixture)
    systemProperty("voxelbrew.test.plugin", fixture.absolutePath)
}

apply(from = rootProject.file("gradle/di.gradle.kts"))

apply(from = rootProject.file("gradle/packaging.gradle.kts"))

dependencies {
    implementation("dev.voxelbrew.api:import")
    implementation("dev.voxelbrew.api:export")
    implementation("dev.voxelbrew.api:slicer")
    implementation("dev.voxelbrew.api:printer")
    runtimeOnly(project(":importer-impl"))
    runtimeOnly(project(":exporter-impl"))
    runtimeOnly(project(":slicer-impl"))
    runtimeOnly(project(":printer-impl"))
    testImplementation(project(":importer-impl"))
    testImplementation(project(":exporter-impl"))
    testImplementation(project(":slicer-impl"))
    implementation(project(":util"))
    implementation(project(":engine"))
    implementation("info.picocli:picocli")
    implementation("com.google.code.gson:gson")
    testImplementation("com.google.protobuf:protobuf-java")
}

application { mainClass.set("dev.voxelbrew.cli.VoxelBrew") }

tasks.named<JavaExec>("run") {
    dependsOn(":engine:buildNative")
    systemProperty(
        "voxelbrew.native.path",
        project(":engine").layout.buildDirectory.dir("native/runtime").get().asFile.absolutePath,
    )
}

tasks
    .withType<Test>()
    .matching { it.name != "test" }
    .configureEach {
        dependsOn(":engine:buildNative")
        val runtime = project(":engine").layout.buildDirectory.dir("native/runtime")
        inputs.dir(runtime)
        systemProperty("voxelbrew.native.path", runtime.get().asFile.absolutePath)
        systemProperty("voxelbrew.test.classpath", sourceSets.main.get().runtimeClasspath.asPath)
    }

tasks.named<Sync>("installDist") {
    dependsOn(":engine:buildNative")
    from(project(":engine").layout.buildDirectory.dir("native/runtime")) { into("native") }
}

// GUI parity tests need this launcher, not the results of CLI qualification.
// When both are requested, publish the launcher first so the two suites can overlap.
for (taskName in
    listOf("e2eTest", "appImage", "packageCheck", "updatePackageCheck", "releaseArchiveCheck")) {
    tasks.named(taskName) { mustRunAfter("installDist") }
}

tasks.named<Test>("e2eTest") {
    inputs.file(rootProject.file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar"))
    dependsOn(":engine:buildExporterFixture")
    systemProperty(
        "voxelbrew.test.plugin",
        rootProject
            .file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar")
            .absolutePath,
    )
}
