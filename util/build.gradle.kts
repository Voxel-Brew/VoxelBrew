plugins {
    `java-library`
    `java-test-fixtures`
    `maven-publish`
}

dependencies {
    api("dev.voxelbrew.api:model:${providers.gradleProperty("modelApiVersion").get()}")
    implementation("org.apache.commons:commons-compress")
    implementation("com.google.code.gson:gson")
    testFixturesImplementation(
        platform("dev.voxelbrew:bom:${providers.gradleProperty("bomVersion").get()}")
    )
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api")
    testFixturesImplementation("org.assertj:assertj-core")
    testFixturesImplementation("org.slf4j:slf4j-api")
    testFixturesImplementation("dev.voxelbrew.api:export")
    testFixturesImplementation("dev.voxelbrew.api:import")
    testImplementation("org.junit.platform:junit-platform-testkit")
}

sourceSets.named("testFixtures") {
    java.setSrcDirs(listOf("src/test/fixtures/java"))
    resources.setSrcDirs(listOf("src/test/fixtures/resources"))
}

val buildVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", buildVersion)
    filesMatching("voxelbrew-util-version.properties") { expand("version" to buildVersion) }
}

tasks.jar {
    manifest.attributes("Implementation-Version" to buildVersion)
    // The public model reads this host metadata when evaluating $vb.version.
    from(tasks.processResources) {
        include("voxelbrew-util-version.properties")
        into("META-INF/voxelbrew")
        rename { "application.properties" }
    }
}

tasks.named<Test>("integrationTest") {
    val artifact = tasks.jar.flatMap { it.archiveFile }
    inputs.file(artifact)
    systemProperty("voxelbrew.test.utilJar", artifact.get().asFile.absolutePath)
    systemProperty("voxelbrew.test.buildVersion", buildVersion)
}

publishing {
    publications { create<MavenPublication>("util") { from(components["java"]) } }
    repositories {
        maven {
            name = "fixture"
            url = uri(rootProject.layout.projectDirectory.dir("engine/build/repository"))
        }
    }
}
