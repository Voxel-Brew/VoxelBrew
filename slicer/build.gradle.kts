plugins {
    `java-library`
    `maven-publish`
}

base { archivesName.set("slicer-impl") }

val slicerPlatform =
    when {
        System.getProperty("os.name").startsWith("Windows") -> "windows"
        System.getProperty("os.name").startsWith("Mac") -> "macos"
        System.getProperty("os.name") == "Linux" -> "linux"
        else -> null
    }
val supportsSlicerBundles =
    slicerPlatform != null &&
        (System.getProperty("os.arch") in setOf("amd64", "x86_64") ||
            (slicerPlatform == "macos" &&
                System.getProperty("os.arch") in setOf("aarch64", "arm64")))
val bundleSlicers =
    providers
        .gradleProperty("bundleSlicers")
        .map(String::toBoolean)
        .getOrElse(supportsSlicerBundles)

if (bundleSlicers) {
    check(supportsSlicerBundles) {
        "Bundled slicers are unavailable for this platform architecture"
    }
    val script = rootProject.file("scripts/bundle-slicers.py")
    val platform = requireNotNull(slicerPlatform)
    val cache = rootProject.file(".tools/bundled-slicers")
    val resources = layout.buildDirectory.dir("bundled-slicers")
    val stageBundledSlicers by
        tasks.registering(Exec::class) {
            inputs.file(script)
            inputs.property("platform", platform)
            outputs.dir(resources)
            environment("VOXELBREW_BUNDLE_OFFLINE", gradle.startParameter.isOffline.toString())
            commandLine(
                providers
                    .gradleProperty("slicerPython")
                    .getOrElse(if (platform == "windows") "python" else "python3"),
                script,
                platform,
                cache,
                resources.get().asFile,
            )
        }
    tasks.processResources {
        from(stageBundledSlicers) { into("dev/voxelbrew/slicer/bundled/$platform") }
    }
    tasks.jar { entryCompression = ZipEntryCompression.STORED }
}

dependencies {
    api("dev.voxelbrew.api:slicer")
    implementation("com.google.code.gson:gson")
    testImplementation("com.google.protobuf:protobuf-java")
    testImplementation(project(":printer-impl"))
    runtimeOnly(project(":printer-impl"))
    implementation(project(":engine"))
    implementation(project(":exporter-impl"))
    implementation(project(":importer-impl"))
    implementation(project(":util"))
}

tasks.named<Test>("test") {
    dependsOn(":engine:buildExporterFixture")
    systemProperty(
        "voxelbrew.test.plugin",
        rootProject
            .file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar")
            .absolutePath,
    )
}

tasks.named<Test>("integrationTest") {
    dependsOn(":engine:buildNative", ":engine:buildExporterFixture")
    systemProperty(
        "voxelbrew.native.path",
        project(":engine").layout.buildDirectory.dir("native/runtime").get().asFile.absolutePath,
    )
    systemProperty(
        "voxelbrew.test.plugin",
        rootProject
            .file("fixtures/exporter-plugin/build/libs/fixture-exporter-1.0.jar")
            .absolutePath,
    )
}

publishing {
    publications {
        create<MavenPublication>("slicer") {
            from(components["java"])
            artifactId = "slicer-impl"
        }
    }
}
