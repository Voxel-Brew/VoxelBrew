import java.util.Properties
import org.gradle.jvm.toolchain.JavaToolchainService

// Applied to the two frontends; packages the host architecture only.
val packageWindows = System.getProperty("os.name").startsWith("Windows")
val packageMac = System.getProperty("os.name").startsWith("Mac")
val productVersion = project.version.toString()

require(
    productVersion.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-SNAPSHOT)?"))
) {
    "projectVersion must be A.B.C or A.B.C-SNAPSHOT"
}

val numericVersion = productVersion.removeSuffix("-SNAPSHOT")

// JDK 21 requires a positive first Mac bundle component. Keep pre-1.0 builds distinct.
require(numericVersion.split('.').all { (it.toIntOrNull() ?: -1) in 0..65534 }) {
    "Version components must be below 65535 (native package limit)"
}

val bundleVersion =
    if (packageMac)
        "${numericVersion.substringBefore('.').toInt() + 1}.${numericVersion.substringAfter('.')}"
    else numericVersion
val packagePlatform = if (packageWindows) "windows" else if (packageMac) "macos" else "linux"
val packageArchitecture =
    when (System.getProperty("os.arch")) {
        "amd64",
        "x86_64" -> "x64"
        "aarch64",
        "arm64" -> "arm64"
        else -> error("Unsupported package architecture: ${System.getProperty("os.arch")}")
    }
val applicationName = if (project.name == "gui") "VoxelBrew" else "VoxelBrewCLI"
val applicationIcon =
    if (project.name != "gui") null
    else if (packageWindows) file("src/main/packaging/voxelbrew.ico")
    else if (packageMac) file("src/main/packaging/voxelbrew.icns")
    else file("src/main/resources/dev/voxelbrew/gui/icons/voxelbrew-512.png")
val mainType =
    if (project.name == "gui") "dev.voxelbrew.gui.Launcher" else "dev.voxelbrew.cli.VoxelBrew"
val packageJdk =
    extensions
        .getByType<JavaToolchainService>()
        .compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .get()
        .metadata
        .installationPath
        .asFile
val packageInput = layout.buildDirectory.dir("package-input")
val imageRoot = layout.buildDirectory.dir("app-image")
val image = imageRoot.map { it.dir(applicationName + if (packageMac) ".app" else "") }

val apiVersions =
    providers.provider {
        configurations
            .getByName("runtimeClasspath")
            .incoming
            .resolutionResult
            .allComponents
            .mapNotNull { it.moduleVersion }
            .filter { it.group == "dev.voxelbrew.api" }
            .associate { it.name to it.version }
            .also { check(it.keys == setOf("model", "import", "export", "slicer", "printer")) }
    }
val installationMetadata = layout.buildDirectory.dir("installation-metadata")
val generateInstallationMetadata by
    tasks.registering {
        inputs.property("version", productVersion)
        inputs.property("platform", "$packagePlatform-$packageArchitecture")
        inputs.property("component", project.name)
        inputs.property("apiVersions", apiVersions)
        outputs.dir(installationMetadata)
        val component = if (project.name == "gui") "GUI" else "CLI"
        val module = project.name
        doLast {
            val destination = installationMetadata.get().file("voxelbrew-install.properties").asFile
            destination.parentFile.mkdirs()
            destination.writeText(
                "format=1\ncomponent=$component\nversion=$productVersion\nplatform=$packagePlatform-$packageArchitecture\nengineJar=engine-$productVersion.jar\nutilJar=util-$productVersion.jar\nimportJar=importer-impl-$productVersion.jar\nexportJar=exporter-impl-$productVersion.jar\nmainJar=$module-$productVersion.jar\n" +
                    apiVersions.get().entries.joinToString("") { (api, apiVersion) ->
                        "${api}-apiJar=$api-$apiVersion.jar\n${api}-apiVersion=$apiVersion\n"
                    }
            )
        }
    }

val preparePackage by
    tasks.registering(Sync::class) {
        dependsOn("jar", ":engine:buildNative", generateInstallationMetadata)
        from(installationMetadata)
        from(tasks.named("jar"))
        from(configurations.named("runtimeClasspath"))
        from(project(":engine").layout.buildDirectory.dir("native/runtime")) {
            into("native")
            if (packagePlatform == "linux") {
                val nativeVersions =
                    Properties().apply {
                        rootProject.file("scripts/native-versions.properties").inputStream().use {
                            load(it)
                        }
                    }
                val sonameSuffix =
                    ".so." + nativeVersions.getProperty("occt.version").substringBeforeLast('.')
                // Copy the ELF SONAME once. Gradle otherwise dereferences all three OCCT aliases.
                exclude { it.name.startsWith("libTK") && !it.name.endsWith(sonameSuffix) }
            }
        }
        from(rootProject.file("LICENSE"))
        from(rootProject.file("THIRD_PARTY_NOTICES.md"))
        from(rootProject.file("third_party")) { into("third_party") }
        into(packageInput)
    }

val cliLauncher = rootProject.file("gui/src/main/packaging/cli-launcher.properties")

val appImage by
    tasks.registering(Exec::class) {
        group = "distribution"
        description = "Build a native launcher with a bundled Java 21 runtime and matching OCCT."
        dependsOn(preparePackage)
        if (project.name == "gui") inputs.file(cliLauncher)
        inputs.dir(packageInput)
        inputs.file(packageJdk.resolve("release"))
        inputs.file(rootProject.file("gradle/packaging.gradle.kts"))
        applicationIcon?.let { inputs.file(it) }
        inputs.property("productVersion", productVersion)
        outputs.dir(image)
        doFirst {
            check(packageJdk.resolve("jmods/java.se.jmod").isFile) {
                "Packaging requires a full Java 21 JDK with jmods (Ubuntu: install openjdk-21-jdk-headless); selected $packageJdk"
            }
            delete(image.get().asFile)
        }
        commandLine(
            listOf(
                packageJdk
                    .resolve("bin/jpackage${if (packageWindows) ".exe" else ""}")
                    .absolutePath,
                "--type",
                "app-image",
                "--name",
                applicationName,
                "--app-version",
                bundleVersion,
                "--vendor",
                "voxelbrew.dev",
                *(if (packageMac && project.name == "gui")
                    arrayOf("--mac-package-identifier", "dev.voxelbrew.gui")
                else emptyArray()),
                *(if (project.name == "gui")
                    arrayOf("--add-launcher", "VoxelBrewCLI=${cliLauncher.absolutePath}")
                else emptyArray()),
                "--input",
                packageInput.get().asFile.absolutePath,
                "--dest",
                imageRoot.get().asFile.absolutePath,
                "--main-jar",
                "${project.name}-${project.version}.jar",
                "--main-class",
                mainType,
                "--add-modules",
                "java.se,jdk.unsupported,jdk.crypto.ec,jdk.localedata",
                "--jlink-options",
                if (packageMac) "--strip-debug --no-man-pages --no-header-files"
                else "--strip-debug --no-man-pages --no-header-files --compress=zip-6",
            ) +
                (applicationIcon?.let { listOf("--icon", it.absolutePath) } ?: emptyList()) +
                (if (packageWindows && project.name == "cli") listOf("--win-console")
                else emptyList())
        )
    }

val packageCheck by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Exercise the packaged launcher without JAVA_HOME or Java on PATH."
        dependsOn(appImage, ":engine:buildExporterFixture")
        if (project.name == "gui") mustRunAfter("e2eTest")
        commandLine(
            if (packageWindows) "python" else "python3",
            rootProject.file("scripts/check-package.py").absolutePath,
            project.name,
            image.get().asFile.absolutePath,
            rootProject.projectDir.absolutePath,
            layout.buildDirectory.dir("package-artifacts").get().asFile.absolutePath,
        )
    }

tasks.named("platformCheck") { dependsOn(packageCheck) }

val updatePackageCheck by
    tasks.registering(Exec::class) {
        group = "verification"
        description =
            "Update a relocated app image with a verified offline release fixture and real bundled JVM."
        dependsOn(appImage, "packageZip")
        mustRunAfter(packageCheck)
        environment("VOXELBREW_TEST_JDK", packageJdk.absolutePath)
        commandLine(
            if (packageWindows) "python" else "python3",
            rootProject.file("scripts/check-update.py").absolutePath,
            project.name,
            image.get().asFile.absolutePath,
            rootProject.projectDir.absolutePath,
            layout.buildDirectory.dir("package-artifacts").get().asFile.absolutePath,
            layout.buildDirectory
                .file(
                    "distributions/$applicationName-$productVersion-$packagePlatform-$packageArchitecture.zip"
                )
                .get()
                .asFile
                .absolutePath,
        )
        doFirst { layout.buildDirectory.dir("package-artifacts").get().asFile.mkdirs() }
    }

tasks.named("platformCheck") { dependsOn(updatePackageCheck) }

val packageZip by
    tasks.registering(Zip::class) {
        group = "distribution"
        dependsOn(appImage)
        mustRunAfter(packageCheck)
        // Only ship the app image. Runtime instance locks live beside it after package checks.
        from(image) { into(applicationName + if (packageMac) ".app" else "") }
        // Keep Unix launchers executable after extraction; archives remain platform specific.
        eachFile { permissions { unix(if (file.canExecute()) "rwxr-xr-x" else "rw-r--r--") } }
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        archiveFileName.set(
            "$applicationName-$productVersion-$packagePlatform-$packageArchitecture.zip"
        )
    }

tasks.register<Exec>("releaseArchiveCheck") {
    group = "verification"
    description = "Extract and exercise the distributable ZIP, including Unix launcher permissions."
    dependsOn(packageZip, ":engine:buildExporterFixture")
    mustRunAfter("e2eTest", packageCheck, updatePackageCheck)
    commandLine(
        if (packageWindows) "python" else "python3",
        rootProject.file("scripts/check-release-archive.py").absolutePath,
        project.name,
        packageZip.get().archiveFile.get().asFile.absolutePath,
        rootProject.projectDir.absolutePath,
        layout.buildDirectory.dir("package-artifacts/archive").get().asFile.absolutePath,
    )
}

tasks.named("platformCheck") { dependsOn("releaseArchiveCheck") }

if (project.name == "gui") {
    val installerStage = layout.buildDirectory.dir("installer-stage")
    val installerOutput = layout.buildDirectory.dir("distributions")
    val installerName =
        "$applicationName-$productVersion-$packagePlatform-$packageArchitecture" +
            when {
                packageWindows -> "-Setup.exe"
                packageMac -> ".dmg"
                else -> ".deb"
            }
    val installerFile = installerOutput.map { it.file(installerName) }
    val stageInstaller by
        tasks.registering(Sync::class) {
            dependsOn(appImage)
            from(image) {
                include("VoxelBrew.exe", "VoxelBrewCLI.exe", "app/**", "runtime/**")
                into("VoxelBrew")
            }
            into(installerStage)
        }
    val associations = file("src/main/packaging/windows-associations.iss")
    val innoScript = layout.buildDirectory.file("installer/voxelbrew.iss")
    val generateInnoScript by
        tasks.registering {
            dependsOn(stageInstaller)
            inputs.property("productVersion", productVersion)
            inputs.property("numericVersion", numericVersion)
            inputs.file(rootProject.file("gradle/packaging.gradle.kts"))
            outputs.file(innoScript)
            doLast {
                val source = installerStage.get().dir("VoxelBrew").asFile.absolutePath
                val output = installerOutput.get().asFile.absolutePath
                val icon = applicationIcon!!.absolutePath
                val quotedExe = "\"\"{app}\\VoxelBrew.exe\"\""
                val quotedFile = "\"\"%1\"\""
                val script =
                    """
                    [Setup]
                    AppId=dev.voxelbrew.gui
                    AppName=VoxelBrew
                    AppVersion=$numericVersion
                    AppVerName=VoxelBrew $productVersion
                    AppPublisher=voxelbrew.dev
                    DefaultDirName={localappdata}\Programs\VoxelBrew
                    DefaultGroupName=VoxelBrew
                    PrivilegesRequired=lowest
                    ChangesAssociations=yes
                    OutputDir=$output
                    OutputBaseFilename=${installerName.removeSuffix(".exe")}
                    SetupIconFile=$icon
                    Compression=lzma
                    SolidCompression=yes

                    ChangesAssociations=yes

                    [Registry]
                    Root: HKCU; Subkey: "Software\Classes\.vxb\OpenWithProgids"; ValueType: none; ValueName: "VoxelBrew.Design"; Flags: uninsdeletevalue
                    Root: HKCU; Subkey: "Software\Classes\VoxelBrew.Design"; ValueType: string; ValueData: "VoxelBrew design"; Flags: uninsdeletekey
                    Root: HKCU; Subkey: "Software\Classes\VoxelBrew.Design\DefaultIcon"; ValueType: string; ValueData: "{app}\VoxelBrew.exe,0"
                    Root: HKCU; Subkey: "Software\Classes\VoxelBrew.Design\shell\open\command"; ValueType: string; ValueData: "${quotedExe} --open ${quotedFile}"

                    [Files]
                    Source: "$source\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs

                    #include "$associations"

                    [Icons]
                    Name: "{group}\VoxelBrew"; Filename: "{app}\VoxelBrew.exe"; WorkingDir: "{app}"
                    """
                val target = innoScript.get().asFile
                target.parentFile.mkdirs()
                target.writeText(script.trimIndent() + "\n")
            }
        }
    tasks.register<Exec>("packageInstaller") {
        group = "distribution"
        description = "Package the desktop and command-line launchers in the host installer."
        dependsOn(appImage)
        inputs.file(rootProject.file("gradle/packaging.gradle.kts"))
        outputs.file(installerFile)
        if (packageWindows) {
            dependsOn(generateInnoScript)
            inputs.dir(installerStage)
            inputs.file(innoScript)
            inputs.file(associations)
            commandLine(
                providers
                    .environmentVariable("INNO_SETUP_ISCC")
                    .orElse("C:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe")
                    .get(),
                innoScript.get().asFile.absolutePath,
            )
        } else {
            val script =
                rootProject.file(
                    if (packageMac) "scripts/package-macos-dmg.sh"
                    else "scripts/package-linux-deb.sh"
                )
            inputs.file(script)
            inputs.dir(image)
            commandLine(
                listOf(
                    "bash",
                    script.absolutePath,
                    image.get().asFile.absolutePath,
                    installerFile.get().asFile.absolutePath,
                ) + if (packageMac) emptyList() else listOf(productVersion)
            )
        }
        doFirst {
            installerOutput.get().asFile.mkdirs()
            delete(installerFile.get().asFile)
        }
    }
}
