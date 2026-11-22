import java.util.Properties

plugins { `java-library` }

val qualityPolicy = "https://raw.githubusercontent.com/Voxel-Brew/BOM/master/quality"

apply(from = "$qualityPolicy/analysis.gradle")

group = "external.consumer"

version = "1.0"

repositories { maven { url = uri("../../engine/build/repository") } }

apply(from = "../../gradle/repositories.gradle.kts")

configurations.configureEach {
    // The host rebuilds the fixture against its freshly published engine snapshot.
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

val hostProperties =
    Properties().apply { file("../../gradle.properties").inputStream().use { load(it) } }
val bomVersion =
    providers.gradleProperty("bomVersion").getOrElse(hostProperties.getProperty("bomVersion"))
val hostVersion =
    providers
        .gradleProperty("projectVersion")
        .getOrElse(hostProperties.getProperty("projectVersion"))

dependencies {
    compileOnly(platform("dev.voxelbrew:bom:$bomVersion"))
    compileOnly("dev.voxelbrew.api:export")
    compileOnly("dev.voxelbrew.api:slicer")
    compileOnly("dev.voxelbrew.api:import")
}

// Native feature providers still exercise the AGPL engine contract separately.
sourceSets.main {
    java.exclude("consumer/SpacerProvider.java", "consumer/BrokenFeatureProviders.java")
}

val features =
    sourceSets.create("features") {
        java.srcDir("src/main/java")
        java.include("consumer/SpacerProvider.java", "consumer/BrokenFeatureProviders.java")
    }

dependencies {
    add(features.compileOnlyConfigurationName, platform("dev.voxelbrew:bom:$bomVersion"))
    add(features.compileOnlyConfigurationName, "dev.voxelbrew:engine")
}

// Feature providers compile against the engine and util built by the host checkout.
configurations.named(features.compileClasspathConfigurationName) {
    resolutionStrategy.force("dev.voxelbrew:engine:$hostVersion", "dev.voxelbrew:util:$hostVersion")
}

tasks.named<Jar>("jar") { from(features.output) }

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

tasks.named("jar") {
    doFirst {
        val hostModules =
            configurations.compileClasspath
                .get()
                .resolvedConfiguration
                .resolvedArtifacts
                .map { it.moduleVersion.id }
                .filter { it.group.startsWith("dev.voxelbrew") }
                .map { it.name }
                .toSet()
        check(hostModules == setOf("model", "import", "export", "slicer")) {
            "The external consumer must compile with API/domain artifacts only: $hostModules"
        }
    }
}
