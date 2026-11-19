package dev.voxelbrew.gui;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.slicer.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.slicer.SliceService;
import dev.voxelbrew.slicer.SlicerInstallation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;

/** Owner-thread state with cancellable worker execution and revision-guarded publication. */
@lombok.extern.slf4j.Slf4j
public final class PrintController implements AutoCloseable {
    public enum State {
        IDLE,
        SLICING,
        SUCCEEDED,
        CANCELLED,
        FAILED,
        OUTDATED
    }

    private final ExecutorService worker;
    private final Consumer<Runnable> dispatch;
    private final Supplier<CadDocument> current;
    private final Path pluginDirectory;
    private final dev.voxelbrew.importer.GcodeSource gcodeReader;
    private Map<String, List<String>> completedGcode = Map.of();
    private Map<String, dev.voxelbrew.api.model.printing.PrintArtifact> completedArtifacts = Map.of();

    Map<String, dev.voxelbrew.api.model.printing.PrintArtifact> completedArtifacts() {
        return completedArtifacts;
    }

    private String restoredRevision = "";
    private List<String> restoredLines = List.of();

    public Map<String, List<String>> completedGcode() {
        return completedGcode;
    }

    @Getter
    @Accessors(fluent = true)
    private State state = State.IDLE;

    private Cancellation cancellation;
    private String revision = "";
    private String installationId = "";

    @Getter
    @Accessors(fluent = true)
    private String message = "Ready to slice";

    private final List<String> diagnostics = new ArrayList<>();

    @Getter
    @Accessors(fluent = true)
    private SliceService.Result result;

    @Setter
    @Accessors(fluent = true, chain = false)
    private Runnable onChanged = () -> {};

    private long generation;
    private boolean closed;

    @Getter
    @Accessors(fluent = true)
    private boolean running;

    public PrintController(Consumer<Runnable> dispatch, Supplier<CadDocument> current) {
        this(dispatch, current, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    public PrintController(Consumer<Runnable> dispatch, Supplier<CadDocument> current, Path pluginDirectory) {
        this(
                dispatch,
                current,
                pluginDirectory,
                WindowResources.worker("slicer"),
                dev.voxelbrew.importer.ImportRuntime.load().gcode());
    }

    PrintController(
            @NonNull Consumer<Runnable> dispatch,
            @NonNull Supplier<CadDocument> current,
            @NonNull Path pluginDirectory,
            @NonNull ExecutorService worker,
            @NonNull dev.voxelbrew.importer.GcodeSource gcodeReader) {
        this.dispatch = dispatch;
        this.current = current;
        this.pluginDirectory = pluginDirectory;
        this.worker = worker;
        this.gcodeReader = gcodeReader;
    }

    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public void clear() {
        if (running) throw new IllegalStateException("Wait for slicing to finish before clearing the job");
        generation++;
        result = null;
        completedGcode = Map.of();
        completedArtifacts = Map.of();
        restoredRevision = "";
        restoredLines = List.of();
        revision = "";
        installationId = "";
        diagnostics.clear();
        state = State.IDLE;
        message = "Ready to slice";
        onChanged.run();
    }

    public void documentChanged(CadDocument document) {
        if (revision.isEmpty()
                || (revision.equals(DocumentCodec.fingerprint(document))
                        && installationId.equals(SlicerInstallation.selectedId(document.printSetup())))) return;
        outdated("Model or print settings changed. Slice again to update the job.");
    }

    /** A provider reload can change evaluated geometry without changing persistent bytes. */
    public void invalidateGeometry() {
        if ((revision.isEmpty() && restoredRevision.isEmpty()) || state == State.OUTDATED) return;
        outdated("Geometry is being rebuilt. Slice again to update the job.");
    }

    private void outdated(String reason) {
        if (cancellation != null) cancellation.cancel();
        result = null;
        completedGcode = Map.of();
        completedArtifacts = Map.of();
        restoredRevision = "";
        restoredLines = List.of();
        state = State.OUTDATED;
        message = reason;
        onChanged.run();
    }

    public CompletableFuture<SliceService.Result> startEmbedded(
            CadDocument document, SlicerProvider provider, SlicerWindow window, boolean allPlates) {
        var plates = allPlates
                ? document.printSetup().plates().stream()
                        .filter(plate -> !plate.instances().isEmpty())
                        .toList()
                : List.of(document.printSetup().activePlate());
        if (plates.isEmpty()) throw new IllegalArgumentException("Add objects to at least one plate");
        var requests = plates.stream()
                .map(plate -> new CadDocument(
                        document.id(),
                        document.features(),
                        document.cursor(),
                        document.printSetup().selectPlate(plate.id()),
                        document.parameters(),
                        document.bodyStyles(),
                        document.bodyNames(),
                        document.featureNames()))
                .toList();
        return startJobs(document, provider, window, requests);
    }

    public void restoreEmbedded(CadDocument document) {
        var lines = document.activeGcode();
        if (closed || running || lines.isEmpty()) return;
        String captured = DocumentCodec.fingerprint(document);
        if (captured.equals(restoredRevision) && lines.equals(restoredLines)) return;
        restoredRevision = captured;
        restoredLines = lines;
        long ticket = ++generation;
        worker.execute(() -> {
            try {
                var job = gcodeReader.readLines(lines);
                dispatch.accept(() -> {
                    if (closed
                            || ticket != generation
                            || !captured.equals(restoredRevision)
                            || running
                            || !captured.equals(DocumentCodec.fingerprint(current.get()))
                            || !lines.equals(current.get().activeGcode())) return;
                    revision = captured;
                    installationId = SlicerInstallation.selectedId(document.printSetup());
                    result = new SliceService.Result(job, null);
                    state = State.SUCCEEDED;
                    message = "Stored G-code ready to export";
                    onChanged.run();
                });
            } catch (IOException failure) {
                dispatch.accept(() -> {
                    if (closed
                            || ticket != generation
                            || !captured.equals(restoredRevision)
                            || running
                            || !captured.equals(DocumentCodec.fingerprint(current.get()))
                            || !lines.equals(current.get().activeGcode())) return;
                    restoredRevision = "";
                    message = "Cannot preview stored G-code: " + failure.getMessage();
                    onChanged.run();
                });
            }
        });
    }

    private CompletableFuture<SliceService.Result> startJobs(
            CadDocument document, SlicerProvider provider, SlicerWindow window, List<CadDocument> requests) {
        if (closed || running) throw new IllegalStateException("Wait for the current slicer task to finish");
        running = true;
        state = State.SLICING;
        result = null;
        completedGcode = Map.of();
        completedArtifacts = Map.of();
        diagnostics.clear();
        revision = DocumentCodec.fingerprint(document);
        installationId = SlicerInstallation.selectedId(document.printSetup());
        message = "Validating print setup";
        var token = new Cancellation();
        cancellation = token;
        long ticket = ++generation;
        var future = new CompletableFuture<SliceService.Result>();
        onChanged.run();
        worker.execute(() -> {
            try {
                var models = new ArrayList<ManufacturingModel>();
                // Preflight every plate before publishing any output.
                for (var request : requests) {
                    token.check();
                    var model = ManufacturingModels.evaluate(request, MeshOptions.DEFAULT, pluginDirectory);
                    PrintValidation.validate(model).requireValid();
                    models.add(model);
                }
                SliceService.Result selectedResult = null;
                SliceService.Result lastResult = null;
                var jobs = new LinkedHashMap<String, List<String>>();
                var artifacts = new LinkedHashMap<String, dev.voxelbrew.api.model.printing.PrintArtifact>();
                for (int i = 0; i < requests.size(); i++) {
                    token.check();
                    var request = requests.get(i);
                    String label = "Plate " + (i + 1) + "/" + requests.size() + " ("
                            + request.printSetup().activePlate().name() + "): ";
                    var completed = SliceService.slice(
                                    provider,
                                    models.get(i),
                                    window,
                                    token,
                                    line -> dispatch.accept(() -> {
                                        if (closed || ticket != generation || state != State.SLICING) return;
                                        if (diagnostics.size() == 100) diagnostics.removeFirst();
                                        diagnostics.add(label + line);
                                        message = label + line;
                                        onChanged.run();
                                    }))
                            .orElseThrow(CancellationException::new);
                    lastResult = completed;
                    artifacts.put(request.printSetup().activePlateId(), completed.artifact());
                    jobs.put(
                            request.printSetup().activePlateId(),
                            completed.job().lines());
                    if (request.printSetup()
                            .activePlateId()
                            .equals(document.printSetup().activePlateId())) selectedResult = completed;
                }
                token.check();
                var completed = lastResult;
                var preview = selectedResult;
                dispatch.accept(() -> {
                    if (closed || ticket != generation) {
                        future.cancel(false);
                        return;
                    }
                    running = false;
                    var live = current.get();
                    if (state == State.OUTDATED
                            || !revision.equals(DocumentCodec.fingerprint(live))
                            || !Objects.equals(
                                    document.printSetup().workspaceSettings().get(PrintTarget.PRINTER_ID),
                                    live.printSetup().workspaceSettings().get(PrintTarget.PRINTER_ID))
                            || !SlicerInstallation.selectedId(document.printSetup())
                                    .equals(SlicerInstallation.selectedId(live.printSetup()))) {
                        state = State.OUTDATED;
                        message = "Job is outdated; slice the current revision again.";
                        result = null;
                        future.completeExceptionally(new SliceService.OutdatedException());
                    } else if (token.cancelled()) {
                        state = State.CANCELLED;
                        result = null;
                        message = "Slicing cancelled.";
                        future.completeExceptionally(new CancellationException(message));
                    } else {
                        try {
                            token.publish(() -> {
                                state = State.SUCCEEDED;
                                completedGcode = Map.copyOf(jobs);
                                completedArtifacts = Map.copyOf(artifacts);
                                result = preview;
                                message = "Sliced " + requests.size() + " plate(s). G-code ready to export.";
                                future.complete(completed);
                            });
                        } catch (IOException | CancellationException failure) {
                            state = failure instanceof CancellationException ? State.CANCELLED : State.FAILED;
                            message = failure.getMessage();
                            future.completeExceptionally(failure);
                        }
                    }
                    onChanged.run();
                });
            } catch (Exception | LinkageError failure) {
                if (failure instanceof CancellationException || failure instanceof SliceService.OutdatedException)
                    log.debug("Print job cancelled or outdated", failure);
                else log.error("Print job failed", failure);
                dispatch.accept(() -> {
                    if (closed || ticket != generation) {
                        future.cancel(false);
                        return;
                    }
                    running = false;
                    result = null;
                    if (state != State.OUTDATED) {
                        state = failure instanceof CancellationException
                                ? State.CANCELLED
                                : failure instanceof SliceService.OutdatedException ? State.OUTDATED : State.FAILED;
                        message = Objects.toString(failure.getMessage(), failure.toString());
                    }
                    onChanged.run();
                    future.completeExceptionally(failure);
                });
            }
        });
        return future;
    }

    public void cancel() {
        if (cancellation != null && running && cancellation.cancel()) {
            log.info("Print cancellation requested");
            state = State.CANCELLED;
            result = null;
            message = "Cancelling slicer";
            onChanged.run();
        }
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        if (cancellation != null) cancellation.cancel();
        worker.shutdown();
    }
}
