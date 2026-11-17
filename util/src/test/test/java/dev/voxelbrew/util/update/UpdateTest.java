package dev.voxelbrew.util.update;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.jar.*;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class UpdateTest {
    @TempDir
    Path directory;

    private final UpdateCancellation cancellation = new UpdateCancellation();
    private final ReleaseVersion current = ReleaseVersion.release("1.2.3");

    @Test
    void declaredFileIoModulesMustExistAndMatchTheInstallationVersion() throws Exception {
        for (String module : List.of("import", "export", "import-api", "export-api", "slicer-api", "model-api")) {
            var image = image(directory.resolve(module), current);
            Path marker = image.appDirectory().resolve(ApplicationImage.MARKER);
            String artifactName = switch (module) {
                case "import" -> "importer-impl.jar";
                case "export" -> "exporter-impl.jar";
                default -> module + ".jar";
            };
            Files.writeString(marker, Files.readString(marker) + module + "Jar=" + artifactName + "\n");
            assertThatThrownBy(() -> ApplicationImage.open(image.root(), image.component(), image.platform()))
                    .isInstanceOf(IOException.class);
            Path artifact = image.appDirectory().resolve(artifactName);
            jar(artifact, "voxelbrew-" + module + "-version.properties", "version=9.9.9");
            assertThatThrownBy(() -> ApplicationImage.open(image.root(), image.component(), image.platform()))
                    .isInstanceOf(IOException.class);
            jar(artifact, "voxelbrew-" + module + "-version.properties", "version=" + current);
            assertThat(ApplicationImage.open(image.root(), image.component(), image.platform())
                            .version())
                    .isEqualTo(current);
        }
    }

    @Test
    void apiVersionsAreIndependentAndStillVerified() throws Exception {
        var image = image(directory.resolve("independent-api"), current);
        Path marker = image.appDirectory().resolve(ApplicationImage.MARKER);
        Files.writeString(
                marker,
                Files.readString(marker) + "model-apiJar=model-0.1.0-SNAPSHOT.jar\nmodel-apiVersion=0.1.0-SNAPSHOT\n");
        Path artifact = image.appDirectory().resolve("model-0.1.0-SNAPSHOT.jar");
        jar(artifact, "voxelbrew-model-api-version.properties", "version=0.1.0-SNAPSHOT");
        assertThat(ApplicationImage.open(image.root(), image.component(), image.platform())
                        .version())
                .isEqualTo(current);
        jar(artifact, "voxelbrew-model-api-version.properties", "version=0.2.0-SNAPSHOT");
        assertThatThrownBy(() -> ApplicationImage.open(image.root(), image.component(), image.platform()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void utilityIdentityIsRequiredWhenDeclaredAndLegacyMetadataStillOpens() throws Exception {
        var image = image(directory.resolve("installation"), current);
        assertThat(image.updaterJar()).isEqualTo("util.jar");
        Path utility = image.appDirectory().resolve("util.jar");
        Files.delete(utility);
        assertThatThrownBy(() -> ApplicationImage.open(image.root(), image.component(), image.platform()))
                .isInstanceOf(IOException.class);
        jar(utility, "voxelbrew-util-version.properties", "version=9.9.9");
        assertThatThrownBy(() -> ApplicationImage.open(image.root(), image.component(), image.platform()))
                .isInstanceOf(IOException.class);
        Path marker = image.appDirectory().resolve(ApplicationImage.MARKER);
        Files.writeString(marker, Files.readString(marker).replace("utilJar=util.jar\n", ""));
        assertThat(ApplicationImage.open(image.root(), image.component(), image.platform())
                        .updaterJar())
                .isEqualTo("engine.jar");
    }

    @Test
    void versionsAndReleaseSelection() throws Exception {
        assertThat(ReleaseVersion.release("1.10.0").compareTo(ReleaseVersion.release("1.9.99")) > 0)
                .isTrue();
        assertThat(ReleaseVersion.parse("1.2.3-SNAPSHOT").compareTo(current) < 0)
                .isTrue();
        for (String invalid : List.of("01.2.3", "1.2", "1.2.3-rc1", "65535.0.0", "1.2.3/path"))
            assertThatThrownBy(() -> ReleaseVersion.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReleaseVersion.release("1.2.3-SNAPSHOT")).isInstanceOf(IllegalArgumentException.class);
        assertThat(check("1.2.3", null, false).available()).isFalse();
        assertThat(check("1.2.3", null, true).available()).isTrue();
        assertThat(check("1.0.0", null, true).available()).isFalse();
        assertThat(check("1.0.0", "1.0.0", false).available()).isTrue();
        assertThat(check("1.3.0", null, false).available()).isTrue();
        assertThat(check("1.3.0", null, false).installable()).isFalse();
    }

    @Test
    void rejectsUnsafeReleaseMetadataAndMissingAssets() throws Exception {
        assertThat(GitHubReleaseSource.parse(metadata("v1.2.3", "false", "false", "SHA256SUMS"), current)
                        .version())
                .isEqualTo(current);
        for (String metadata : List.of(
                metadata("v1.2.3", "true", "false"),
                metadata("v1.2.3", "false", "true"),
                metadata("v1.2.3", "false", "false", "a", "a"),
                metadata("v1.2.3", "false", "false", "../escape"),
                metadata("1.2.3", "false", "false"),
                metadata("v1.3.0", "false", "false"),
                metadata("v1.2.3", "\"false\"", "false"),
                "null",
                "{}",
                "{broken",
                "[]",
                metadata("v1.2.3", "false", "false") + " {}"))
            assertThatThrownBy(() -> GitHubReleaseSource.parse(metadata, current))
                    .isInstanceOf(IOException.class);
        var missing = new ReleaseSource.Release(current, Set.of("SHA256SUMS"));
        assertThatThrownBy(() ->
                        service(missing, Optional.empty()).check(new UpdateService.Request(null, true), cancellation))
                .isInstanceOf(IOException.class);
        var noChecksum =
                new ReleaseSource.Release(current, Set.of(ApplicationImage.Component.CLI.asset(current, "linux-x64")));
        assertThatThrownBy(() -> service(noChecksum, Optional.empty())
                        .check(new UpdateService.Request(null, true), cancellation))
                .isInstanceOf(IOException.class);
    }

    @Test
    void verifiesChecksumsAndCancelsWithoutAcceptingPartialData() throws Exception {
        Path archive = Files.writeString(directory.resolve("app.zip"), "original");
        Path checksums = Files.writeString(
                directory.resolve("SHA256SUMS"), UpdateArchive.sha256(archive, cancellation) + "  app.zip\n");
        UpdateArchive.verify(archive, checksums, "app.zip", cancellation);
        Files.writeString(archive, "corrupt");
        assertThatThrownBy(() -> UpdateArchive.verify(archive, checksums, "app.zip", cancellation))
                .isInstanceOf(IOException.class);
        Files.writeString(checksums, "0".repeat(64) + "  app.zip\n" + "0".repeat(64) + "  app.zip\n");
        assertThatThrownBy(() -> UpdateArchive.verify(archive, checksums, "app.zip", cancellation))
                .isInstanceOf(IOException.class);
        cancellation.cancel();
        assertThatThrownBy(() -> UpdateArchive.sha256(archive, cancellation)).isInstanceOf(CancellationException.class);
    }

    @Test
    void extractionRejectsTraversalLinksAndCaseCollisions() throws Exception {
        int attempt = 0;
        for (String path : List.of(
                "../escape",
                "VoxelBrewCLI/../escape",
                "/VoxelBrewCLI/root",
                "Wrong/file",
                "VoxelBrewCLI/a\\b",
                "VoxelBrewCLI/a:b",
                "VoxelBrewCLI/CON.txt",
                "VoxelBrewCLI/a./file")) {
            Path archive = zip("bad" + attempt + ".zip", List.of(path), 0100644);
            Path destination = directory.resolve("bad" + attempt++);
            assertThatThrownBy(() -> UpdateArchive.extract(archive, destination, "VoxelBrewCLI", cancellation))
                    .isInstanceOf(IOException.class);
        }
        Path links = zip("links.zip", List.of("VoxelBrewCLI/link"), 0120777);
        assertThatThrownBy(() -> UpdateArchive.extract(links, directory.resolve("links"), "VoxelBrewCLI", cancellation))
                .isInstanceOf(IOException.class);
        Path duplicates = zip("duplicates.zip", List.of("VoxelBrewCLI/Name", "VoxelBrewCLI/name"), 0100644);
        assertThatThrownBy(() -> UpdateArchive.extract(
                        duplicates, directory.resolve("duplicates"), "VoxelBrewCLI", cancellation))
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(directory.resolve("escape"))).isFalse();
    }

    @Test
    void extractionPreservesOnlySafeExecutablePermissions() throws Exception {
        Path archive = zip("good.zip", List.of("VoxelBrewCLI/bin/VoxelBrewCLI"), 0107777);
        Path result = UpdateArchive.extract(archive, directory.resolve("good"), "VoxelBrewCLI", cancellation);
        assertThat(Files.readString(result.resolve("bin/VoxelBrewCLI"))).isEqualTo("data");
        if (Files.getFileStore(result).supportsFileAttributeView("posix")) {
            assertThat(Files.isExecutable(result.resolve("bin/VoxelBrewCLI"))).isTrue();
            assertThat(Files.getPosixFilePermissions(result.resolve("bin/VoxelBrewCLI"))
                            .contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE))
                    .isFalse();
        }
    }

    @Test
    void failedReplacementRestoresOriginalAndNeverOverwritesBackup() throws Exception {
        Path target = Files.createDirectory(directory.resolve("installation"));
        Path source = Files.createDirectory(directory.resolve("replacement"));
        Path backup = directory.resolve("backup");
        Files.writeString(target.resolve("version"), "old");
        Files.writeString(source.resolve("version"), "new");
        var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> UpdateHelper.replace(target, source, backup, (from, to) -> {
                    if (from.equals(source)) throw new IOException("simulated locked directory");
                    Files.move(from, to);
                }),
                IOException.class);
        assertThat(failure.getMessage().contains("restored")).isTrue();
        assertThat(Files.readString(target.resolve("version"))).isEqualTo("old");
        UpdateHelper.replace(target, source, backup, (from, to) -> Files.move(from, to));
        assertThat(Files.readString(target.resolve("version"))).isEqualTo("new");
        assertThat(Files.readString(backup.resolve("version"))).isEqualTo("old");
        assertThatThrownBy(() -> UpdateHelper.replace(target, source, backup, (from, to) -> fail("must not move")))
                .isInstanceOf(IOException.class);
    }

    @SuppressWarnings("try")
    @Test
    void busyInstallationAndFailedDownloadPreserveOriginal() throws Exception {
        var image = image(directory.resolve("installed with spaces"), current);
        var release = release("1.3.0");
        var service = service(release, Optional.of(image));
        var selected = service.check(new UpdateService.Request(null, false), cancellation);
        try (var lock = ApplicationImage.lock(image.updateLock(), false)) {
            assertThatThrownBy(() -> service.prepare(selected, cancellation, ignored -> {}))
                    .isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> service.prepare(selected, cancellation, ignored -> {}))
                .isInstanceOf(IOException.class);
        assertThat(ApplicationImage.open(image.root(), ApplicationImage.Component.CLI, "linux-x64")
                        .version())
                .isEqualTo(current);
        try (var children = Files.list(image.root().getParent())) {
            assertThat(children.noneMatch(path -> path.getFileName().toString().startsWith(".VoxelBrew-update-")))
                    .isTrue();
        }
        assertThatThrownBy(() -> service(release, Optional.empty()).prepare(selected, cancellation, ignored -> {}))
                .isInstanceOf(IOException.class);
    }

    private UpdateService.Check check(String release, String requested, boolean force) throws IOException {
        return service(release(release), Optional.empty())
                .check(
                        new UpdateService.Request(requested == null ? null : ReleaseVersion.release(requested), force),
                        cancellation);
    }

    private static String metadata(String tag, String draft, String prerelease, String... assets) {
        return "{\"tag_name\":\""
                + tag
                + "\",\"draft\":"
                + draft
                + ",\"prerelease\":"
                + prerelease
                + ",\"assets\":["
                + Arrays.stream(assets)
                        .map(name -> "{\"name\":\"" + name + "\"}")
                        .collect(java.util.stream.Collectors.joining(","))
                + "]}";
    }

    @Test
    void cancelledStageCleanupHandlesReadOnlyFilesAndRejectsOtherDirectories() throws Exception {
        Path stage = Files.createTempDirectory(directory, ".VoxelBrew-update-");
        Path file = Files.writeString(stage.resolve("runtime.exe"), "staged file");
        var dos = Files.getFileAttributeView(
                file, java.nio.file.attribute.DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null) dos.setReadOnly(true);
        UpdateFiles.deleteStage(stage);
        assertThat(Files.exists(stage)).isFalse();
        Path unrelated = Files.createDirectory(directory.resolve("installation"));
        assertThatThrownBy(() -> UpdateFiles.deleteStage(unrelated)).isInstanceOf(IOException.class);
        assertThat(Files.isDirectory(unrelated)).isTrue();
    }

    private ReleaseSource.Release release(String value) {
        var version = ReleaseVersion.release(value);
        return new ReleaseSource.Release(
                version, Set.of("SHA256SUMS", ApplicationImage.Component.CLI.asset(version, "linux-x64")));
    }

    private UpdateService service(ReleaseSource.Release release, Optional<ApplicationImage> installation) {
        return new UpdateService(
                ApplicationImage.Component.CLI,
                current,
                "linux-x64",
                new ReleaseSource() {
                    @Override
                    public Release fetch(ReleaseVersion requested, UpdateCancellation token) {
                        return release;
                    }

                    @Override
                    public void download(Release selected, String asset, Path destination, UpdateCancellation token)
                            throws IOException {
                        Files.writeString(destination, "interrupted download");
                        throw new IOException("fixture network failure");
                    }
                },
                installation);
    }

    private Path zip(String file, List<String> paths, int mode) throws IOException {
        Path archive = directory.resolve(file);
        try (var zip = new ZipArchiveOutputStream(archive)) {
            for (String path : paths) {
                var entry = new ZipArchiveEntry(path);
                entry.setUnixMode(mode);
                zip.putArchiveEntry(entry);
                zip.write("data".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeArchiveEntry();
            }
        }
        return archive;
    }

    private ApplicationImage image(Path parent, ReleaseVersion version) throws IOException {
        Path root = Files.createDirectories(parent.resolve("VoxelBrewCLI"));
        var image = new ApplicationImage(
                root, ApplicationImage.Component.CLI, "linux-x64", version, "engine.jar", "cli.jar");
        Files.createDirectories(image.appDirectory().resolve("native"));
        Files.createDirectories(image.runtime().resolve("bin"));
        Files.createDirectories(image.launcher().getParent());
        Files.writeString(image.launcher(), "launcher");
        Files.writeString(image.runtime().resolve("bin/java"), "java");
        Files.writeString(
                image.appDirectory().resolve(ApplicationImage.MARKER),
                "format=1\ncomponent=CLI\nplatform=linux-x64\nversion="
                        + version
                        + "\nengineJar=engine.jar\nutilJar=util.jar\nmainJar=cli.jar\n");
        jar(image.appDirectory().resolve("util.jar"), "voxelbrew-util-version.properties", "version=" + version);
        jar(image.appDirectory().resolve("engine.jar"), "voxelbrew-engine-version.properties", "version=" + version);
        jar(image.appDirectory().resolve("cli.jar"), "dev/voxelbrew/cli/VoxelBrew.class", "class");
        return ApplicationImage.open(root, ApplicationImage.Component.CLI, "linux-x64");
    }

    private static void jar(Path path, String entry, String content) throws IOException {
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
