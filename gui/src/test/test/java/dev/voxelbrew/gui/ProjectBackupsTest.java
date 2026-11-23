package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBackupsTest {
    @Test
    void decliningTheLatestBackupStillOffersANewerInterruptedSession(@TempDir Path directory) throws Exception {
        Path project = directory.resolve("part.vxb");
        for (int version = 1; version <= 3; version++) {
            try (var session = BackupSession.create(directory, project)) {
                DocumentWriter.save(CadDocument.empty(), session.backup, false);
                java.nio.file.Files.setLastModifiedTime(
                        session.backup, java.nio.file.attribute.FileTime.from(version, TimeUnit.DAYS));
            }
            if (version == 2) {
                try (var candidate = BackupSession.latest(directory, project)) {
                    candidate.dismiss(project);
                }
                assertThat(BackupSession.latest(directory, project)).isNull();
            }
        }
        try (var newest = BackupSession.latest(directory, project)) {
            assertThat(newest.interrupted).isTrue();
            assertThat(java.nio.file.Files.getLastModifiedTime(newest.backup).to(TimeUnit.DAYS))
                    .isEqualTo(3);
        }
    }

    @Test
    void disablingDuringAWriteRemovesOnlyTheCurrentSession(@TempDir Path directory) throws Exception {
        Path folder = directory.resolve("backups");
        Path other = directory.resolve("other.vxb");
        try (var session = BackupSession.create(folder, other)) {
            DocumentWriter.save(CadDocument.empty(), session.backup, false);
        }
        var files = WindowResources.worker();
        var resume = new java.util.concurrent.CompletableFuture<Void>();
        try (var controller = new DocumentController(
                Runnable::run,
                doc -> new DocumentController.Build(List.of(), List.of(), true),
                directory,
                WindowResources.worker(),
                files,
                DocumentReader::load,
                DocumentWriter::save)) {
            var backups = new ProjectBackups(controller, folder, new ProjectBackups.Settings(true, 5, 1));
            var project = directory.resolve("current.vxb");
            controller.save(project, false).get(5, TimeUnit.SECONDS);
            var original = java.nio.file.Files.readAllBytes(project);
            files.execute(resume::join);
            controller.printWorkspace(() -> Map.of("test", "unsaved"));
            backups.check(1);
            backups.configure(new ProjectBackups.Settings(false, 5, 1));
            resume.complete(null);
            files.submit(() -> {}).get(5, TimeUnit.SECONDS);
            backups.check(TimeUnit.MINUTES.toNanos(10));
            assertThat(backups.latest(project)).isNull();
            assertThat(project).hasBinaryContent(original);
            assertThat(controller.dirty()).isTrue();
            try (var retained = backups.latest(other)) {
                assertThat(retained.backup).isRegularFile();
                retained.finish(other, true);
            }
            assertThat(folder).isEmptyDirectory();
        } finally {
            resume.complete(null);
        }
    }

    @Test
    void savingTheRecoveredProjectDeletesItsBackupWhileTheProjectIsOpen(@TempDir Path directory) throws Exception {
        Path project = directory.resolve("part.vxb");
        Path folder = directory.resolve("backups");
        Path backup;
        try (var session = BackupSession.create(folder, project)) {
            backup = session.backup;
            DocumentWriter.save(CadDocument.empty(), backup, false);
            assertThat(BackupSession.latest(folder, project)).isNull();
        } // Release the process lock without recording a clean close, as on termination.
        assertThat(BackupSession.latest(folder, directory.resolve("other/part.vxb")))
                .isNull();
        try (var controller = new DocumentController(
                Runnable::run,
                doc -> new DocumentController.Build(List.of(), List.of(), true),
                directory,
                WindowResources.worker(),
                WindowResources.worker(),
                DocumentReader::load,
                DocumentWriter::save)) {
            var backups = new ProjectBackups(controller, folder, new ProjectBackups.Settings(false, 5, 10));
            var candidate = backups.latest(project);
            assertThat(candidate.interrupted).isTrue();
            backups.recover(candidate, project).get(5, TimeUnit.SECONDS);
            assertThat(controller.dirty()).isTrue();
            assertThat(controller.path()).isEqualTo(project);
            assertThat(project).doesNotExist();
            var recoveredId = DocumentReader.load(backup).id();
            controller.save(project, false).get(5, TimeUnit.SECONDS);
            assertThat(controller.dirty()).isFalse();
            assertThat(folder).isEmptyDirectory();
            assertThat(backups.latest(project)).isNull();
            assertThat(DocumentReader.load(project).id()).isEqualTo(recoveredId);
        }
        assertThat(folder).isEmptyDirectory();
    }

    @Test
    void failedSavePreservesTheSessionEvenAfterAnEarlierSuccessfulSaveAs(@TempDir Path directory) throws Exception {
        Path project = directory.resolve("renamed.vxb");
        Path folder = directory.resolve("backups");
        Path backup;
        try (var session = BackupSession.create(folder, null)) {
            backup = session.backup;
            DocumentWriter.save(CadDocument.empty(), backup, false);
        }
        var files = WindowResources.worker();
        try (var controller = new DocumentController(
                Runnable::run,
                doc -> new DocumentController.Build(List.of(), List.of(), true),
                directory,
                WindowResources.worker(),
                files,
                DocumentReader::load,
                DocumentWriter::save)) {
            var backups = new ProjectBackups(controller, folder, new ProjectBackups.Settings(true, 5, 1));
            backups.recover(backups.latest(null), null).get(5, TimeUnit.SECONDS);
            controller
                    .saveAs(dev.voxelbrew.util.testing.FileExportWindows.destination(project, false))
                    .get(5, TimeUnit.SECONDS);
            assertThat(folder).isEmptyDirectory();
            controller.printWorkspace(() -> Map.of("test", "new edits after Save As"));
            backups.check(System.nanoTime());
            files.submit(() -> {}).get(5, TimeUnit.SECONDS);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> controller.save(project, false).get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(java.io.IOException.class);
            controller.newDocument();
        }
        assertThat(backup).doesNotExist();
        assertThat(BackupSession.latest(folder, null)).isNull();
        try (var candidate = BackupSession.latest(folder, project)) {
            assertThat(candidate.backup).isNotEqualTo(backup).isRegularFile();
            assertThat(DocumentReader.load(candidate.backup).printSetup().workspaceSettings())
                    .containsEntry("test", "new edits after Save As");
            assertThat(candidate.interrupted).isFalse();
        }
    }

    @Test
    void eitherThresholdWritesARecoverableCopyWithoutSavingTheLiveDocument(@TempDir Path directory) throws Exception {
        var writes = new ArrayList<Path>();
        var files = WindowResources.worker();
        try (var controller = new DocumentController(
                ignored -> {},
                doc -> new DocumentController.Build(List.of(), List.of(), true),
                directory,
                WindowResources.worker(),
                files,
                path -> CadDocument.empty(),
                (doc, path, overwrite) -> {
                    DocumentWriter.save(doc, path, overwrite);
                    writes.add(path);
                })) {
            var backups = new ProjectBackups(
                    controller, directory.resolve("backups"), new ProjectBackups.Settings(true, 5, 2));
            backups.check(0);
            controller.printWorkspace(() -> Map.of("test", "first"));
            backups.check(1);
            backups.check(TimeUnit.MINUTES.toNanos(4));
            files.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertThat(writes).isEmpty();

            controller.printWorkspace(() -> Map.of("test", "second"));
            backups.check(TimeUnit.MINUTES.toNanos(4));
            files.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertThat(writes).hasSize(1);
            var path = writes.getFirst();
            assertThat(DocumentReader.load(path).printSetup().workspaceSettings())
                    .containsEntry("test", "second");
            assertThat(controller.path()).isNull();
            assertThat(controller.dirty()).isTrue();
            assertThat(controller.snapshot().document().parameters().builtins().fileVersion())
                    .isZero();

            controller.printWorkspace(() -> Map.of("test", "third"));
            backups.check(TimeUnit.MINUTES.toNanos(5));
            backups.check(TimeUnit.MINUTES.toNanos(10));
            files.submit(() -> {}).get(5, TimeUnit.SECONDS);
            backups.check(TimeUnit.MINUTES.toNanos(20));
            files.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertThat(writes).containsExactly(path, path);
            assertThat(DocumentReader.load(path).printSetup().workspaceSettings())
                    .containsEntry("test", "third");
        }
    }
}
