package dev.voxelbrew.gui;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.userconfig.UserConfigService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ProjectBackups {
    private final DocumentController controller;
    private final Path directory;
    private Settings settings;
    private long generation = -1;
    private long revision;
    private long operations;
    private long pendingSince;
    private long retryAfter;
    private CadDocument observed;
    private Map<String, String> workspace = Map.of();
    private BackupSession session;
    private CompletableFuture<Void> writing;

    record Settings(boolean enabled, int minutes, int operations) {
        static Settings load(UserConfigService store) {
            var values = store.readUserConfig();
            return new Settings(
                    Boolean.parseBoolean(values.getOrDefault("autosave.enabled", "true")),
                    positive(values.get("autosave.minutes"), 5),
                    positive(values.get("autosave.operations"), 10));
        }

        private static int positive(String value, int fallback) {
            try {
                int parsed = Integer.parseInt(value);
                return parsed > 0 ? parsed : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        void save(UserConfigService store) {
            store.saveUserConfig(Map.of(
                    "autosave.enabled",
                    Boolean.toString(enabled),
                    "autosave.minutes",
                    Integer.toString(minutes),
                    "autosave.operations",
                    Integer.toString(operations)));
        }
    }

    ProjectBackups(DocumentController controller, Path directory, Settings settings) {
        this.controller = controller;
        this.directory = directory;
        this.settings = settings;
        controller.onDocumentEnd(this::endDocument);
        controller.onProjectSaved(path -> {
            if (controller.dirty()) {
                recordProject(path);
            } else {
                finishSession(true);
                operations = 0;
            }
        });
    }

    Settings settings() {
        return settings;
    }

    void configure(Settings next) {
        if (settings.enabled() && !next.enabled()) {
            finishSession(true);
            generation = -1;
        }
        settings = next;
    }

    void check(long now) {
        if (!settings.enabled() || controller.ioBusy()) return;
        var snapshot = controller.snapshot();
        if (generation != controller.generation()) startDocument(now);
        finishWrite(now);
        var currentWorkspace = controller.backupWorkspace();
        if (controller.dirty(snapshot.document(), currentWorkspace)) {
            if (snapshot.revision() != revision
                    || !snapshot.document().equals(observed)
                    || !currentWorkspace.equals(workspace)) {
                if (operations == 0) pendingSince = now;
                operations += Math.max(1, snapshot.revision() - revision);
            }
        } else {
            operations = 0;
        }
        observed = snapshot.document();
        workspace = currentWorkspace;
        revision = snapshot.revision();
        if (operations > 0
                && writing == null
                && now - retryAfter >= 0
                && (operations >= settings.operations()
                        || now - pendingSince >= TimeUnit.MINUTES.toNanos(settings.minutes()))) {
            startWrite(now);
        }
    }

    private void startDocument(long now) {
        generation = controller.generation();
        revision = 0;
        observed = null;
        workspace = Map.of();
        operations = 0;
        writing = null;
        retryAfter = now;
    }

    private void finishWrite(long now) {
        if (writing == null || !writing.isDone()) return;
        try {
            writing.join();
        } catch (CompletionException failure) {
            log.warn("Could not save project backup {}", session.backup, failure.getCause());
            operations = Math.max(1, operations);
            pendingSince = now - TimeUnit.MINUTES.toNanos(settings.minutes());
            retryAfter = now + Duration.ofSeconds(30).toNanos();
        }
        writing = null;
    }

    private void startWrite(long now) {
        try {
            if (session == null) session = BackupSession.create(directory, controller.path());
            writing = controller.backup(session.backup, observed, workspace);
            operations = 0;
        } catch (IOException failure) {
            log.warn("Could not create backup session in {}", directory, failure);
            retryAfter = now + Duration.ofSeconds(30).toNanos();
        }
    }

    BackupSession latest(Path project) throws IOException {
        return BackupSession.latest(directory, project);
    }

    CompletableFuture<Void> recover(BackupSession candidate, Path project) {
        return controller
                .recover(candidate.backup, project, () -> {
                    startDocument(System.nanoTime());
                    session = candidate;
                    recordProject(project);
                })
                .whenComplete((unused, failure) -> {
                    if (failure != null) candidate.close();
                });
    }

    private void recordProject(Path path) {
        if (session == null) return;
        try {
            session.record(path, true);
        } catch (IOException failure) {
            log.warn("Could not update backup session {}", session.backup, failure);
        }
    }

    void endDocument() {
        if (session != null) finishSession(controller.savedForClose());
    }

    private void finishSession(boolean removeBackup) {
        var ending = session;
        if (ending == null) return;
        var path = controller.path();
        boolean remove = removeBackup
                && !ending.backup
                        .toAbsolutePath()
                        .normalize()
                        .equals(path == null ? null : path.toAbsolutePath().normalize());
        session = null;
        var pending = writing;
        writing = null;
        if (pending == null) ending.finish(path, remove);
        else pending.whenComplete((unused, failure) -> ending.finish(path, remove));
    }
}
