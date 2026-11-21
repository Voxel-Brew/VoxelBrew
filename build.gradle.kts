import com.diffplug.spotless.LineEnding
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    `java-base`
    id("com.diffplug.spotless") version "8.11.0"
    id("dev.voxelbrew.spotbugs") apply false
    id("dev.voxelbrew.checkstyle") apply false
}

// Root-owned targets include the independent consumer build without making it a subproject.
val sourceProjects =
    listOf(
        "util",
        "engine",
        "importer",
        "exporter",
        "slicer",
        "printer",
        "cli",
        "gui",
        "fixtures/exporter-plugin",
    )
val formatterWindows = System.getProperty("os.name").startsWith("Windows")
val formatterEnvironment = layout.projectDirectory.dir(".tools/formatters")
val formatterBin = formatterEnvironment.dir(if (formatterWindows) "Scripts" else "bin")
val formatterPython = formatterBin.file(if (formatterWindows) "python.exe" else "python")
val clangFormatExecutable =
    formatterBin.file(if (formatterWindows) "clang-format.exe" else "clang-format")
val clangFormatVersion = "18.1.8"
val clangStyle = "{BasedOnStyle: Google, IndentWidth: 4, ColumnLimit: 100, SortIncludes: Never}"
val createFormatterEnvironment by
    tasks.registering(Exec::class) {
        outputs.file(formatterPython)
        commandLine(
            if (formatterWindows) "python" else "python3",
            "-m",
            "venv",
            formatterEnvironment.asFile,
        )
    }
val installClangFormat by
    tasks.registering(Exec::class) {
        dependsOn(createFormatterEnvironment)
        inputs.property("version", clangFormatVersion)
        outputs.file(clangFormatExecutable)
        commandLine(
            formatterPython.asFile,
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            "--only-binary=:all:",
            "clang-format==$clangFormatVersion",
        )
    }

spotless {
    encoding("UTF-8")
    lineEndings = LineEnding.UNIX
    java {
        target(
            sourceProjects.map { "$it/src/**/java/**/*.java" } +
                listOf("fixtures/updater/*.java", "scripts/*.java", "gradle/JavaMemberOrder.java")
        )
        palantirJavaFormat("2.103.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target(
            listOf("*.gradle.kts", "gradle/*.gradle.kts") +
                sourceProjects.map { "$it/*.gradle.kts" }
        )
        ktfmt("0.59").kotlinlangStyle()
        trimTrailingWhitespace()
        endWithNewline()
    }
    cpp {
        target(
            "engine/native/src/**/*.cpp",
            "engine/native/src/**/*.h",
            "engine/native/tests/**/*.cpp",
        )
        clangFormat(clangFormatVersion)
            .pathToExe(clangFormatExecutable.asFile.absolutePath)
            .style(clangStyle)
        trimTrailingWhitespace()
        endWithNewline()
    }
    protobuf {
        target("engine/src/main/proto/*.proto")
        clangFormat(clangFormatVersion)
            .pathToExe(clangFormatExecutable.asFile.absolutePath)
            .style("Google")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("webAndDocs") {
        target(
            "*.md",
            "*/AGENTS.md",
            "importer/*.md",
            "exporter/*.md",
            "slicer/*.md",
            "printer/*.md",
            "docs/*.md",
            ".github/**/*.yml",
            ".github/scripts/*.cjs",
            "gui/src/**/*.css",
        )
        prettier("3.6.2").config(mapOf("printWidth" to 100, "proseWrap" to "preserve"))
        endWithNewline()
    }
    format("text") {
        target(
            ".gitattributes",
            ".gitignore",
            "*.properties",
            "lombok.config",
            "scripts/*.properties",
            "scripts/*.sh",
            "scripts/*.ps1",
            "scripts/*.py",
            "engine/native/CMakeLists.txt",
            "*/src/main/resources/META-INF/services/*",
            "*/src/main/resources/**/*.properties",
            "*/src/test/**/resources/**/*.properties",
            "*/src/main/resources/**/*.ini",
            "fixtures/exporter-plugin/src/main/resources/META-INF/services/*",
        )
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

tasks
    .matching { it.name in setOf("spotlessCpp", "spotlessProtobuf") }
    .configureEach { dependsOn(installClangFormat) }

allprojects {
    group = "dev.voxelbrew"
    version = rootProject.providers.gradleProperty("projectVersion").get()
    apply(from = rootProject.file("gradle/repositories.gradle.kts"))
}

val libraryProjects = subprojects

// Composite builds invoke these root tasks directly rather than CLI task selectors.
tasks.named("assemble") { dependsOn(libraryProjects.map { "${it.path}:assemble" }) }

tasks.named("clean") { dependsOn(libraryProjects.map { "${it.path}:clean" }) }

configure(libraryProjects) {
    configurations.configureEach {
        // Build the application against its own modules when the BOM manages a newer release.
        resolutionStrategy.preferProjectModules()
        // API and BOM snapshots can be republished under the same version.
        resolutionStrategy.cacheChangingModulesFor(0, "seconds")
    }
    // Fixture publications share engine/build; its clean must finish before any publisher writes.
    tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
        if (name.endsWith("ToFixtureRepository")) mustRunAfter(":engine:clean")
    }
    apply(plugin = "java-library")
    extensions.configure<BasePluginExtension> { archivesName.set(projectDir.name) }
    apply(plugin = "dev.voxelbrew.spotbugs")
    apply(plugin = "dev.voxelbrew.checkstyle")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    apply(from = rootProject.file("gradle/lombok.gradle.kts"))
    dependencies {
        "api"(
            platform(
                "dev.voxelbrew:bom:${rootProject.providers.gradleProperty("bomVersion").get()}"
            )
        )
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        if (project.name != "util") {
            "testImplementation"(testFixtures(project(":util")))
        }
    }
    val sources = extensions.getByType<SourceSetContainer>()
    if (
        name in
            setOf(
                "util",
                "cli",
                "gui",
                "slicer-impl",
                "exporter-impl",
                "importer-impl",
                "printer-impl",
            )
    ) {
        sources.configureEach {
            project.dependencies.add(
                compileOnlyConfigurationName,
                "com.github.spotbugs:spotbugs-annotations:4.10.5",
            )
        }
    }
    if (name == "engine") {
        tasks.named<SpotBugsTask>("spotbugsMain") {
            // Keep generated Protobuf classes available to callers without analyzing them.
            classes =
                sources.getByName("main").output.classesDirs.asFileTree.matching {
                    exclude("**/persistence/proto/**")
                }
        }
    }
    sources.named("test") {
        java.setSrcDirs(listOf("src/test/test/java"))
        resources.setSrcDirs(listOf("src/test/test/resources"))
    }
    for (suite in listOf("integrationTest", "e2eTest")) {
        val source =
            sources.create(suite) {
                java.setSrcDirs(listOf("src/test/$suite/java"))
                resources.setSrcDirs(listOf("src/test/$suite/resources"))
                compileClasspath += sources.getByName("main").output
                runtimeClasspath += sources.getByName("main").output
            }
        configurations.named(source.implementationConfigurationName) {
            extendsFrom(configurations.getByName("testImplementation"))
        }
        configurations.named(source.runtimeOnlyConfigurationName) {
            extendsFrom(configurations.getByName("testRuntimeOnly"))
        }
        tasks.register<Test>(suite) {
            group = "verification"
            description = "Run the $suite suite."
            testClassesDirs = source.output.classesDirs
            classpath = source.runtimeClasspath
            mustRunAfter("test")
            if (suite == "e2eTest") mustRunAfter("integrationTest")
        }
    }
    tasks.register("platformCheck") {
        group = "verification"
        description = "Run end-to-end and packaged-application checks for this platform."
        dependsOn("e2eTest")
    }
    tasks.named("check") { dependsOn("integrationTest", "platformCheck") }
    val cleanupTasks =
        (listOf("test", "integrationTest", "e2eTest") +
                if (project.name == "engine") listOf("nativeIntegrationTest") else emptyList())
            .associateWith { suite ->
                tasks.register<Exec>("${suite}Cleanup") {
                    description =
                        "Remove this suite's temporary files without changing its test result."
                    commandLine(
                        if (formatterWindows) "python" else "python3",
                        rootProject.file("scripts/test_workspace.py").absolutePath,
                        layout.buildDirectory.dir("test-tmp/$suite").get().asFile.absolutePath,
                        layout.buildDirectory.dir("test-home/$suite").get().asFile.absolutePath,
                    )
                    isIgnoreExitValue = true
                }
            }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        if (name != "test") {
            outputs.doNotCacheIf("Platform tests qualify the current runner and installed tools") {
                true
            }
        }
        // Parallelize projects, not suite JVMs: homes and evidence are shared within a suite.
        maxParallelForks = 1
        val temporaryDirectory = layout.buildDirectory.dir("test-tmp/$name")
        val testHome = layout.buildDirectory.dir("test-home/$name")
        systemProperty("java.io.tmpdir", temporaryDirectory.get().asFile.absolutePath)
        doFirst { temporaryDirectory.get().asFile.mkdirs() }
        finalizedBy(cleanupTasks.getValue(name))
        systemProperty("voxelbrew.home", testHome.get().asFile.absolutePath)
        systemProperty(
            "voxelbrew.slicer",
            providers
                .gradleProperty("slicerExecutable")
                .orElse(providers.environmentVariable("VOXELBREW_SLICER"))
                .orElse(
                    if (formatterWindows)
                        rootProject
                            .file(
                                ".tools/PrusaSlicer-2.7.2/PrusaSlicer-2.7.2+win64-202402291307/prusa-slicer-console.exe"
                            )
                            .absolutePath
                    else "prusa-slicer"
                )
                .get(),
        )
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
}

val javaMemberOrderLauncher =
    extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }

val checkJavaMemberOrder by
    tasks.registering(JavaExec::class) {
        group = "verification"
        description = "Check Java member declaration order."
        javaLauncher.set(javaMemberOrderLauncher)
        mainClass.set(layout.projectDirectory.file("gradle/JavaMemberOrder.java").asFile.path)
        args(sourceProjects.map { "$it/src" } + listOf("fixtures/updater", "scripts", "gradle"))
    }

tasks.named("check") { dependsOn(checkJavaMemberOrder) }

tasks.register("checkstyleAll") {
    group = "verification"
    description = "Check handwritten Java in all application source sets."
    dependsOn(libraryProjects.map { it.tasks.withType<Checkstyle>() })
}

tasks.register("basicChecks") {
    group = "verification"
    description = "Check Java member order and run SpotBugs on production code."
    dependsOn(checkJavaMemberOrder)
    dependsOn(libraryProjects.map { "${it.path}:spotbugsMain" })
}

for (suite in listOf("test", "integrationTest", "e2eTest", "platformCheck")) {
    tasks.register(suite) {
        group = "verification"
        description = "Run $suite across all modules."
        dependsOn(libraryProjects.map { "${it.path}:$suite" })
    }
}

val workflowTest by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Test release automation without publishing."
        commandLine("node", "--test", ".github/scripts/release.test.cjs")
    }

val testCleanupTest by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Run tests for the Python build and verification scripts."
        environment(
            "VOXELBREW_MEMBER_ORDER_JAVA",
            javaMemberOrderLauncher.get().executablePath.asFile.path,
        )
        commandLine(
            if (formatterWindows) "python" else "python3",
            "-m",
            "unittest",
            "discover",
            "-s",
            "scripts",
            "-p",
            "*_test.py",
        )
    }

tasks.named("test") { dependsOn(workflowTest, testCleanupTest) }

tasks.named("check") {
    dependsOn(workflowTest, testCleanupTest)
    dependsOn(libraryProjects.map { "${it.path}:check" })
}

tasks.register("headlessPlatformCheck") {
    group = "verification"
    description = "Run CLI end-to-end and packaged-CLI checks without qualifying GUI graphics."
    dependsOn(":cli:platformCheck")
}

tasks.register("headlessWorkflowCheck") {
    group = "verification"
    description = "Run CLI end-to-end workflows on an isolated headless CI runner."
    dependsOn(":cli:e2eTest")
}

tasks.register("headlessPackageCheck") {
    group = "verification"
    description = "Verify CLI app images, updates and release archives on a headless CI runner."
    dependsOn(":cli:packageCheck", ":cli:updatePackageCheck", ":cli:releaseArchiveCheck")
}

tasks.wrapper {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806"
}

// Frontends use APIs for operations. The host uses application services for slicing and printer
// storage.
for (module in listOf(":cli", ":gui")) {
    project(module) {
        val verifyFrontendIoDependencies by
            tasks.registering {
                doLast {
                    fun modules(scope: String): Set<String> {
                        // ResolutionResult alone omits failed dependencies; surface the actual
                        // cause.
                        configurations[scope].resolve()
                        return configurations[scope]
                            .incoming
                            .resolutionResult
                            .allComponents
                            .mapNotNull { it.moduleVersion?.let { id -> "${id.group}:${id.name}" } }
                            .toSet()
                    }
                    val compile = modules("compileClasspath")
                    val runtime = modules("runtimeClasspath")
                    val apis =
                        setOf("import", "export", "slicer", "printer").map {
                            "dev.voxelbrew.api:$it"
                        }
                    val hostServices =
                        setOf("importer", "exporter", "slicer", "printer").map {
                            "dev.voxelbrew:$it-impl"
                        }
                    check(compile.containsAll(apis)) {
                        "$module is missing public API dependencies"
                    }
                    val applicationModules = compile.filter { it.startsWith("dev.voxelbrew:") }
                    val allowed =
                        hostServices +
                            listOf("bom", "engine", "util", module.removePrefix(":")).map {
                                "dev.voxelbrew:$it"
                            }
                    check(allowed.containsAll(applicationModules)) {
                        "$module has unexpected compile dependencies: ${applicationModules - allowed.toSet()}"
                    }
                    check(runtime.containsAll(apis + hostServices)) {
                        "$module runtime is missing APIs or implementations"
                    }
                }
            }
        tasks.named("check") { dependsOn(verifyFrontendIoDependencies) }
    }
}

tasks.register("verifyFrontendIoDependencies") {
    dependsOn(":cli:verifyFrontendIoDependencies", ":gui:verifyFrontendIoDependencies")
}
