plugins {
    `java-library`
    `maven-publish`
}

base { archivesName.set("slicer-impl") }

dependencies {
    api("dev.voxelbrew.api:slicer:${providers.gradleProperty("slicerApiVersion").get()}")
    implementation("com.google.code.gson:gson:2.15.0")
    testImplementation("com.google.protobuf:protobuf-java:4.30.2")
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
