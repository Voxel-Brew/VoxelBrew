package dev.voxelbrew.gui;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/** A locked session belongs to a live window. An unlocked session still marked open survived an abnormal exit. */
@Slf4j
final class BackupSession implements AutoCloseable {
    // On some filesystems closing a second channel in the same process can release its existing lock.
    private static final java.util.Set<Path> LOCAL_OWNERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    final Path backup;
    private final FileChannel owner;
    private final FileLock lock;
    volatile boolean interrupted;
    private volatile boolean dismissed;

    static BackupSession create(Path directory, Path project) throws IOException {
        Files.createDirectories(directory);
        Path filename = project == null ? null : project.getFileName();
        String name = filename == null ? "Untitled" : filename.toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        var backup =
                directory.resolve(name.substring(0, Math.min(64, name.length())) + "-" + UUID.randomUUID() + ".vxb");
        var session = acquire(backup, StandardOpenOption.CREATE_NEW);
        if (session == null) throw new IOException("Could not claim backup " + backup);
        try {
            session.record(project, true);
            return session;
        } catch (IOException failure) {
            session.close();
            throw failure;
        }
    }

    static BackupSession latest(Path directory, Path project) throws IOException {
        var latest = newestSession(directory, project);
        // Choosing the saved project also settles older snapshots of that same project.
        if (latest != null && latest.dismissed) {
            latest.close();
            return null;
        }
        return latest;
    }

    private static BackupSession newestSession(Path directory, Path project) throws IOException {
        if (!Files.isDirectory(directory)) return null;
        BackupSession latest = null;
        FileTime newest = null;
        try (var entries = Files.newDirectoryStream(directory, "*.vxb.session")) {
            for (var entry : entries) {
                String name = directory.relativize(entry).toString();
                Path backup = entry.resolveSibling(name.substring(0, name.length() - ".session".length()));
                BackupSession candidate = null;
                try {
                    var modified = Files.getLastModifiedTime(backup);
                    if (newest != null && newest.compareTo(modified) >= 0) continue;
                    candidate = acquire(backup, StandardOpenOption.WRITE);
                    if (candidate == null || !candidate.matches(project)) continue;
                    if (latest != null) latest.close();
                    latest = candidate;
                    candidate = null;
                    newest = modified;
                } catch (NoSuchFileException ignored) {
                    // The owning window may have finished cleanup during discovery.
                } catch (IOException | IllegalArgumentException failure) {
                    log.warn("Could not read backup session {}", entry, failure);
                } finally {
                    if (candidate != null) candidate.close();
                }
            }
            return latest;
        } catch (RuntimeException | IOException failure) {
            if (latest != null) latest.close();
            throw failure;
        }
    }

    private static Path sidecar(Path backup, String suffix) {
        return backup.resolveSibling(backup.getFileName() + suffix);
    }

    private static synchronized BackupSession acquire(Path backup, StandardOpenOption mode) throws IOException {
        backup = backup.toAbsolutePath().normalize();
        if (LOCAL_OWNERS.contains(backup)) return null;
        var channel = FileChannel.open(sidecar(backup, ".lock"), StandardOpenOption.WRITE, mode);
        try {
            var lock = channel.tryLock();
            if (lock != null) {
                LOCAL_OWNERS.add(backup);
                return new BackupSession(backup, channel, lock);
            }
        } catch (OverlappingFileLockException ignored) {
            // Another window in this JVM owns this session.
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
        channel.close();
        return null;
    }

    private BackupSession(Path backup, FileChannel owner, FileLock lock) {
        this.backup = backup;
        this.owner = owner;
        this.lock = lock;
    }

    private boolean matches(Path project) throws IOException {
        var metadata = new Properties();
        try (var input = Files.newInputStream(sidecar(backup, ".session"))) {
            metadata.load(input);
        }
        String original = metadata.getProperty("project");
        interrupted = "open".equals(metadata.getProperty("state"));
        dismissed = "dismissed".equals(metadata.getProperty("state"));
        if (dismissed && project == null) return false; // Untitled sessions are independent designs.
        return Objects.equals(
                original == null ? null : Path.of(original),
                project == null ? null : project.toAbsolutePath().normalize());
    }

    void record(Path project, boolean open) throws IOException {
        record(project, open ? "open" : "closed");
    }

    boolean projectSavedAfterClose(Path project) throws IOException {
        if (interrupted || project == null || !Files.isRegularFile(project)) return false;
        var saved = Files.getLastModifiedTime(project);
        return saved.compareTo(Files.getLastModifiedTime(sidecar(backup, ".session"))) > 0
                && saved.compareTo(Files.getLastModifiedTime(backup)) > 0;
    }

    void dismiss(Path project) throws IOException {
        if (project != null) {
            if (project.toAbsolutePath().normalize().equals(backup)) {
                record(project, "dismissed");
                return;
            }
            // Keep older copies from resurfacing after deleting the offered backup.
            try (var previous = latest(Objects.requireNonNull(backup.getParent()), project)) {
                if (previous != null
                        && Files.getLastModifiedTime(previous.backup).compareTo(Files.getLastModifiedTime(backup))
                                <= 0) {
                    previous.record(project, "dismissed");
                }
            }
        }
        finish(project, true);
    }

    private void record(Path project, String state) throws IOException {
        var metadata = new Properties();
        metadata.setProperty("state", state);
        if (project != null) {
            metadata.setProperty("project", project.toAbsolutePath().normalize().toString());
        }
        Path entry = sidecar(backup, ".session");
        Path temporary = Files.createTempFile(Objects.requireNonNull(entry.getParent()), "session-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                metadata.store(output, null);
            }
            Files.move(temporary, entry, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void finish(Path project, boolean removeBackup) {
        boolean removed = false;
        try {
            if (removeBackup || Files.notExists(backup)) {
                Files.deleteIfExists(backup);
                Files.deleteIfExists(sidecar(backup, ".session"));
                removed = true;
            } else {
                record(project, false);
            }
        } catch (IOException failure) {
            log.warn("Could not finalize backup {}", backup, failure);
        } finally {
            close();
        }
        if (removed) {
            try {
                Files.deleteIfExists(sidecar(backup, ".lock"));
            } catch (IOException failure) {
                log.warn("Could not remove backup lock {}", backup, failure);
            }
        }
    }

    @Override
    public void close() {
        if (!owner.isOpen()) return;
        try (owner) {
            lock.release();
        } catch (IOException failure) {
            log.warn("Could not release backup {}", backup, failure);
        } finally {
            LOCAL_OWNERS.remove(backup);
        }
    }
}
