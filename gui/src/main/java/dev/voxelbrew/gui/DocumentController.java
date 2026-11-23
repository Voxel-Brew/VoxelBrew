package dev.voxelbrew.gui;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.mesh.*;
import dev.voxelbrew.api.model.sketch.SketchGeometry.Entity;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.importer.ImportRuntime;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/** UI-neutral document controller. UI dispatch confines all state changes to its owner thread. */
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class DocumentController implements AutoCloseable {
    @NonNull
    private final Consumer<Runnable> dispatch;

    @NonNull
    private final Function<CadDocument, Build> evaluator;

    @NonNull
    private final Path pluginDirectory;

    @NonNull
    private final ExecutorService worker;

    @NonNull
    private final ExecutorService io;

    @NonNull
    private final dev.voxelbrew.importer.DocumentSource documents;

    @NonNull
    private final DocumentSave documentWriter;

    private final Object queueLock = new Object();

    private final AtomicLong sequence = new AtomicLong();

    private final AtomicLong documentGeneration = new AtomicLong();

    private Request pending;

    private boolean running;

    private volatile boolean closed;

    private ModelSession session = new ModelSession(newDesktopDocument());

    private CadDocument saved = session.document();

    private volatile boolean unsavedImport;

    private volatile boolean lastSaveSucceeded;

    @Setter
    @Accessors(fluent = true, chain = false)
    private Runnable onDocumentEnd = () -> {};

    @Setter
    @Accessors(fluent = true, chain = false)
    private Consumer<Path> onProjectSaved = ignored -> {};

    @Setter
    @Accessors(fluent = true, chain = false)
    private Function<Path, CompletableFuture<Void>> projectOpener = this::openOriginal;

    @Getter
    @Accessors(fluent = true)
    private Path path;

    private Feature preview;

    private String previewExpression = "";

    @Getter
    @Accessors(fluent = true)
    private Build build = new Build(List.of(), List.of(), true);

    @Getter
    @Accessors(fluent = true)
    private volatile boolean rebuilding;

    @Getter
    @Accessors(fluent = true)
    private volatile boolean ioBusy;

    @Getter
    @Accessors(fluent = true)
    private boolean displayedPreview;

    @Getter
    @Accessors(fluent = true)
    private String error = "";

    @Setter
    @Accessors(fluent = true, chain = false)
    private Runnable onChanged = () -> {};

    @Setter
    @Accessors(fluent = true, chain = false)
    private Consumer<Path> onFileUsed = ignored -> {};

    private java.util.function.Supplier<java.util.Map<String, String>> printWorkspace =
            () -> session.document().printSetup().workspaceSettings();

    public record Body(
            String id,
            Feature.BodyRef output,
            MeshData mesh,
            double volume,
            List<dev.voxelbrew.api.model.geometry.PlanarFace> planarFaces,
            TechnicalEdges edges,
            int[] faceEdges,
            double[] edgeSamples) {
        public Body(String id, Feature.BodyRef output, MeshData mesh, double volume) {
            this(id, output, mesh, volume, List.of());
        }

        public Body(
                String id,
                Feature.BodyRef output,
                MeshData mesh,
                double volume,
                List<dev.voxelbrew.api.model.geometry.PlanarFace> planarFaces) {
            this(id, output, mesh, volume, planarFaces, TechnicalEdges.from(mesh), new int[0], new double[0]);
        }

        public Body(
                String id,
                Feature.BodyRef output,
                MeshData mesh,
                double volume,
                List<dev.voxelbrew.api.model.geometry.PlanarFace> planarFaces,
                int[] faceEdges,
                double[] edgeSamples) {
            this(id, output, mesh, volume, planarFaces, TechnicalEdges.from(mesh), faceEdges, edgeSamples);
        }

        public Body {
            planarFaces = List.copyOf(planarFaces);
            faceEdges = faceEdges.clone();
            edgeSamples = edgeSamples.clone();
        }

        @Override
        public int[] faceEdges() {
            return faceEdges.clone();
        }

        @Override
        public double[] edgeSamples() {
            return edgeSamples.clone();
        }

        public List<Integer> edgesOnFace(int face) {
            var result = new java.util.TreeSet<Integer>();
            for (int i = 0; i < faceEdges.length; i += 2) {
                if (faceEdges[i] == face) {
                    result.add(faceEdges[i + 1]);
                }
            }
            return List.copyOf(result);
        }
    }

    public record Build(
            List<Body> bodies,
            List<Evaluation.Diagnostic> diagnostics,
            boolean valid,
            List<Feature> features,
            Map<String, dev.voxelbrew.api.model.construction.ConstructionGeometry.Datum> construction,
            List<ConstructionFeature.Input> constructionInputs,
            Map<String, List<Entity>> projections) {
        public Build(
                List<Body> bodies,
                List<Evaluation.Diagnostic> diagnostics,
                boolean valid,
                List<Feature> features,
                Map<String, dev.voxelbrew.api.model.construction.ConstructionGeometry.Datum> construction,
                List<ConstructionFeature.Input> constructionInputs) {
            this(bodies, diagnostics, valid, features, construction, constructionInputs, Map.of());
        }

        public Build(
                List<Body> bodies, List<Evaluation.Diagnostic> diagnostics, boolean valid, List<Feature> features) {
            this(bodies, diagnostics, valid, features, Map.of(), List.of());
        }

        public Build(List<Body> bodies, List<Evaluation.Diagnostic> diagnostics, boolean valid) {
            this(bodies, diagnostics, valid, List.of());
        }

        public Build {
            bodies = List.copyOf(bodies);
            diagnostics = List.copyOf(diagnostics);
            features = List.copyOf(features);
            construction = Map.copyOf(construction);
            constructionInputs = List.copyOf(constructionInputs);
            projections = Map.copyOf(projections);
        }
    }

    private record Request(long sequence, long generation, long revision, CadDocument document, boolean preview) {}

    static CadDocument newDesktopDocument() {
        var document = CadDocument.empty();
        return new CadDocument(
                document.id(),
                document.features(),
                document.cursor(),
                document.printSetup().withSupportsDisabled(true),
                document.parameters());
    }

    static Build evaluate(CadDocument document) {
        return evaluate(document, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    static Build evaluate(CadDocument document, Path pluginDirectory) {
        try (var evaluation = Evaluator.evaluate(document, pluginDirectory)) {
            if (!evaluation.valid()) {
                return new Build(List.of(), evaluation.diagnostics(), false, evaluation.resolvedFeatures());
            }
            var bodies = dev.voxelbrew.util.concurrent.ParallelWork.map(
                    evaluation.bodies(),
                    b -> new Body(
                            b.id(),
                            b.output(),
                            b.solid().tessellate(MeshOptions.DEFAULT),
                            b.solid().volume(),
                            b.solid().planarFaces(),
                            b.solid().faceEdges(),
                            b.solid().edgeSamples()));
            var inputs = new ArrayList<ConstructionFeature.Input>();
            var zero = new dev.voxelbrew.api.model.construction.ConstructionGeometry.Vector(0, 0, 0);
            for (var body : evaluation.bodies()) {
                for (int kind = 0; kind < 3; kind++) {
                    int count = (int) body.solid().constructionInput(kind, -1, .5)[0];
                    var selection = switch (kind) {
                        case 0 -> ConstructionFeature.Selection.FACE;
                        case 1 -> ConstructionFeature.Selection.EDGE;
                        default -> ConstructionFeature.Selection.VERTEX;
                    };
                    for (int index = 0; index < count; index++) {
                        inputs.add(new ConstructionFeature.Input(
                                selection,
                                body.output().featureId(),
                                body.output().outputName(),
                                index,
                                zero));
                    }
                }
            }
            return new Build(
                    bodies,
                    evaluation.diagnostics(),
                    true,
                    evaluation.resolvedFeatures(),
                    evaluation.construction(),
                    inputs,
                    evaluation.projections());
        }
    }

    public DocumentController(Consumer<Runnable> dispatch) {
        this(dispatch, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    public DocumentController(Consumer<Runnable> dispatch, Path pluginDirectory) {
        this(dispatch, document -> evaluate(document, pluginDirectory), pluginDirectory);
    }

    DocumentController(Consumer<Runnable> dispatch, Function<CadDocument, Build> evaluator) {
        this(dispatch, evaluator, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    private DocumentController(
            Consumer<Runnable> dispatch, Function<CadDocument, Build> evaluator, Path pluginDirectory) {
        this(
                dispatch,
                evaluator,
                pluginDirectory,
                WindowResources.worker(),
                WindowResources.worker(),
                ImportRuntime.load().documents(),
                dev.voxelbrew.exporter.DocumentWriter::save);
    }

    DocumentController(
            Consumer<Runnable> dispatch,
            Path pluginDirectory,
            ExecutorService worker,
            ExecutorService io,
            dev.voxelbrew.importer.DocumentSource documents,
            DocumentSave documentWriter) {
        this(
                dispatch,
                document -> evaluate(document, pluginDirectory),
                pluginDirectory,
                worker,
                io,
                documents,
                documentWriter);
    }

    public ModelSession.Snapshot snapshot() {
        return session.snapshot();
    }

    long generation() {
        return documentGeneration.get();
    }

    Map<String, String> backupWorkspace() {
        return Map.copyOf(printWorkspace.get());
    }

    CompletableFuture<Void> backup(Path destination, CadDocument document, Map<String, String> workspace) {
        // The writer updates file metadata after publishing. Keep the live project's metadata separate.
        var parameters = document.parameters();
        var detached = new dev.voxelbrew.api.model.parameters.ParameterSet(
                parameters.parameters(),
                parameters.configurations(),
                parameters.active(),
                parameters.bindings(),
                new UuidGenerator(parameters.uuidGenerator().nextValue()));
        detached.builtins().fileVersion(parameters.builtins().fileVersion());
        detached.builtins().projectPath(path);
        var captured = new CadDocument(
                document.id(),
                document.features(),
                document.cursor(),
                document.printSetup().withWorkspaceSettings(workspace),
                detached,
                document.bodyStyles(),
                document.bodyNames(),
                document.featureNames(),
                document.gcode(),
                document.printArtifacts());
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        var parent = destination.getParent();
                        if (parent != null) java.nio.file.Files.createDirectories(parent);
                        documentWriter.save(captured, destination, true);
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                },
                io);
    }

    public List<Feature> effectiveFeatures() {
        return !rebuilding
                        && build.features().size()
                                == session.document().features().size()
                ? build.features()
                : session.document().features();
    }

    public void parameters(dev.voxelbrew.api.model.parameters.ParameterSet parameters) {
        session.setParameters(parameters);
        preview = null;
        schedule(session.document(), false);
    }

    public void configuration(String nameOrId) {
        session.selectConfiguration(nameOrId);
        preview = null;
        schedule(session.document(), false);
    }

    public void expression(String id, String field, String expression) {
        session.bindExpression(id, field, expression);
        preview = null;
        schedule(session.document(), false);
    }

    public void sketch(
            Feature.Sketch sketch, dev.voxelbrew.api.model.parameters.ParameterSet parameters, boolean create) {
        if (create) session.add(sketch, parameters);
        else session.replace(sketch, parameters);
        preview = null;
        schedule(session.document(), false);
    }

    void printWorkspace(java.util.function.Supplier<java.util.Map<String, String>> capture) {
        printWorkspace = capture;
    }

    private void capturePrintWorkspace() {
        var setup = session.document().printSetup();
        var settings = printWorkspace.get();
        if (!settings.equals(setup.workspaceSettings())) {
            var jobs = session.document().gcode();
            var artifacts = session.document().printArtifacts();
            session.setPrintSetup(setup.withWorkspaceSettings(settings));
            session.setGcode(jobs);
            session.setPrintArtifacts(artifacts);
        }
    }

    public boolean dirty() {
        return dirty(session.document(), printWorkspace.get());
    }

    boolean dirty(CadDocument document, Map<String, String> workspace) {
        return unsavedImport
                || !document.equals(saved)
                || !workspace.equals(document.printSetup().workspaceSettings());
    }

    public boolean hasPreview() {
        return preview != null;
    }

    boolean canCommitExtrusion() {
        return preview != null
                && (!BodyExtrusion.modifiesBody(preview) || !rebuilding && displayedPreview && build.valid());
    }

    public boolean canExport() {
        var d = session.document();
        return !closed
                && !rebuilding
                && !ioBusy
                && !hasPreview()
                && !displayedPreview
                && build.valid()
                && !build.bodies().isEmpty()
                && d.cursor() == d.features().size();
    }

    public void newDocument() {
        replace(newDesktopDocument(), null);
    }

    private void replace(CadDocument document, Path source) {
        replace(document, source, false, () -> {});
    }

    boolean savedForClose() {
        return lastSaveSucceeded && !ioBusy && !dirty();
    }

    private void replace(CadDocument document, Path source, boolean recovered, Runnable installed) {
        if (ioBusy) throw new IllegalStateException("Wait for the current file operation");
        onDocumentEnd.run();
        session = new ModelSession(document);
        saved = document;
        unsavedImport = recovered;
        lastSaveSucceeded = false;
        path = source;
        preview = null;
        documentGeneration.incrementAndGet();
        installed.run();
        build = new Build(List.of(), List.of(), true);
        schedule(document, false);
    }

    public void add(Feature feature) {
        session.add(feature);
        preview = null;
        schedule(session.document(), false);
    }

    public void add(Feature feature, dev.voxelbrew.api.model.parameters.ParameterSet parameters) {
        session.add(feature, parameters);
        preview = null;
        schedule(session.document(), false);
    }

    public void edit(String id, String field, double value) {
        session.editDimension(id, field, value, pluginDirectory);
        preview = null;
        schedule(session.document(), false);
    }

    public void replaceFeature(Feature feature) {
        session.replace(feature);
        preview = null;
        schedule(session.document(), false);
    }

    public void deleteFeatures(int from, int to) {
        if (ioBusy || rebuilding || hasPreview()) throw new IllegalStateException("Wait for the current operation");
        session.deleteFeatures(from, to);
        schedule(session.document(), false);
    }

    public void rebuild() {
        session.invalidateGcode();
        preview = null;
        schedule(session.document(), false);
    }

    public void printSetup(dev.voxelbrew.api.model.printing.PrintSetup setup) {
        var previous = session.document();
        boolean workspaceOnly = !rebuilding
                && !hasPreview()
                && !displayedPreview
                && previous.printSetup()
                        .withWorkspaceSettings(setup.workspaceSettings())
                        .equals(setup);
        boolean reuseGeometry = canExport() || workspaceOnly;
        session.setPrintSetup(setup);
        var current = session.document();
        preview = null;
        // The model shares the built-ins instance across setups with the same expression inputs.
        if (reuseGeometry
                && previous.parameters().builtins() == current.parameters().builtins()) {
            onChanged.run();
            return;
        }
        schedule(current, false);
    }

    void gcode(java.util.Map<String, java.util.List<String>> jobs) {
        capturePrintWorkspace();
        session.setGcode(jobs);
        onChanged.run();
    }

    void attachPrintJobs(
            java.util.Map<String, java.util.List<String>> jobs,
            java.util.Map<String, dev.voxelbrew.api.model.printing.PrintArtifact> artifacts) {
        session.setGcode(jobs);
        session.setPrintArtifacts(artifacts);
        onChanged.run();
    }

    void invalidateGcode() {
        if (session.document().gcode().isEmpty()) return;
        session.invalidateGcode();
        onChanged.run();
    }

    void captureSlicerWorkspace() {
        capturePrintWorkspace();
    }

    public void bodyStyle(String bodyId, BodyStyle style) {
        if (!canExport() || build.bodies().stream().noneMatch(b -> b.id().equals(bodyId))) {
            throw new IllegalStateException("Select a current body at the end of a successful rebuild");
        }
        session.setBodyStyle(bodyId, style);
        schedule(session.document(), false);
    }

    public void renameBody(String bodyId, String name) {
        if (!canExport() || build.bodies().stream().noneMatch(b -> b.id().equals(bodyId))) {
            throw new IllegalStateException("Select a current body at the end of a successful rebuild");
        }
        long before = session.revision();
        session.renameBody(bodyId, name);
        if (session.revision() != before) {
            log.info(
                    "Renamed body {} in document {}", bodyId, session.document().id());
            schedule(session.document(), false);
        }
    }

    public void renameFeature(String featureId, String name) {
        if (ioBusy || rebuilding || hasPreview()) throw new IllegalStateException("Wait for the current operation");
        session.renameFeature(featureId, name);
        schedule(session.document(), false);
    }

    public void cursor(int position) {
        preview = null;
        session.setCursor(position);
        schedule(session.document(), false);
    }

    public void undo() {
        preview = null;
        session.undo();
        schedule(session.document(), false);
    }

    public void redo() {
        preview = null;
        session.redo();
        schedule(session.document(), false);
    }

    public void previewExtrusion(Feature.Sketch sketch, double distance) {
        previewExtrusion(sketch, sketch.profileId(), distance);
    }

    public void previewExtrusion(Feature.Sketch sketch, String profileId, double distance) {
        previewExtrusion(sketch, profileId, distance, BodyExtrusion.Operation.NEW_BODY, null);
    }

    private void previewExtrusion(
            Feature.Sketch sketch,
            String profileId,
            double distance,
            BodyExtrusion.Operation operation,
            Feature.BodyRef target) {
        previewExpression = "";
        String id = preview == null
                ? "extrude-" + session.document().uuidGenerator().next()
                : preview.id();
        var candidate = new BodyExtrusion(
                        new Feature.Extrude(id, new Feature.ProfileRef(sketch.id(), profileId), distance),
                        operation,
                        target)
                .feature();
        var temporary = new ModelSession(session.document());
        temporary.add(candidate);
        preview = candidate;
        schedule(temporary.document(), true);
    }

    public void previewExtrusion(Feature.Sketch sketch, String expression) {
        previewExtrusion(sketch, sketch.profileId(), expression);
    }

    public void previewExtrusion(Feature.Sketch sketch, String profileId, String expression) {
        previewExtrusion(sketch, profileId, expression, BodyExtrusion.Operation.NEW_BODY, null);
    }

    void previewExtrusion(
            Feature.Sketch sketch,
            String profileId,
            String expression,
            BodyExtrusion.Operation operation,
            Feature.BodyRef target) {
        double value =
                session.document().parameters().value(expression, dev.voxelbrew.api.model.parameters.ParameterUnit.MM);
        previewExtrusion(sketch, profileId, value, operation, target);
        if (!expression.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
            previewExpression = expression;
        }
    }

    public void commitPreview() {
        if (preview == null) return;
        var feature = preview;
        if (previewExpression.isEmpty()) add(feature);
        else {
            var parameters = session.document()
                    .parameters()
                    .bind(
                            new dev.voxelbrew.api.model.parameters.ParameterSet.Target(feature.id(), "distance"),
                            previewExpression);
            session.add(feature, parameters);
            preview = null;
            previewExpression = "";
            schedule(session.document(), false);
        }
    }

    public void cancelPreview() {
        if (preview != null || displayedPreview) {
            preview = null;
            schedule(session.document(), false);
        }
    }

    public CompletableFuture<Void> open(Path source) {
        requireIdleIo();
        return projectOpener.apply(source);
    }

    CompletableFuture<Void> openOriginal(Path source) {
        return open(source, source.toAbsolutePath(), false, () -> {});
    }

    CompletableFuture<Void> recover(Path backup, Path original, Runnable installed) {
        return open(backup, original == null ? null : original.toAbsolutePath(), true, installed);
    }

    private CompletableFuture<Void> open(Path source, Path original, boolean recovered, Runnable installed) {
        requireIdleIo();
        ioBusy = true;
        onChanged.run();
        var result = new CompletableFuture<Void>();
        io.execute(() -> {
            try {
                var document = documents.load(source);
                dispatch.accept(() -> {
                    if (closed) {
                        result.cancel(false);
                        return;
                    }
                    ioBusy = false;
                    if (recovered) document.parameters().builtins().projectPath(original);
                    replace(document, original, recovered, installed);
                    if (original != null) onFileUsed.accept(original);
                    log.info("Opened document {} from {}", document.id(), source);
                    result.complete(null);
                });
            } catch (Exception failure) {
                finishIo(result, failure);
            }
        });
        return result;
    }

    public CompletableFuture<Void> save(Path destination, boolean overwrite) {
        requireIdleIo();
        lastSaveSucceeded = false;
        capturePrintWorkspace();
        var captured = session.document();
        long generation = documentGeneration.get();
        ioBusy = true;
        onChanged.run();
        var result = new CompletableFuture<Void>();
        io.execute(() -> {
            try {
                documentWriter.save(captured, destination, overwrite);
                dispatch.accept(() -> {
                    if (closed) {
                        result.cancel(false);
                        return;
                    }
                    ioBusy = false;
                    if (documentGeneration.get() == generation) {
                        saved = captured;
                        unsavedImport = false;
                        path = destination.toAbsolutePath();
                        lastSaveSucceeded = true;
                        onProjectSaved.accept(path);
                    }
                    onFileUsed.accept(destination);
                    onChanged.run();
                    result.complete(null);
                });
            } catch (Exception failure) {
                finishIo(result, failure);
            }
        });
        return result;
    }

    /** Runs export on the file worker and shows its dialog on the UI thread. */
    public CompletableFuture<Void> saveAs(dev.voxelbrew.api.exporter.ExportWindow parent) {
        dev.voxelbrew.api.exporter.Exporter<dev.voxelbrew.api.exporter.FileExportDialog.Destination> exporter =
                (document, window) -> {
                    var selected = window.show(
                            new dev.voxelbrew.api.exporter.FileExportDialog("VoxelBrew design (*.vxb)", "vxb"));
                    if (selected.isEmpty()) return java.util.Optional.empty();
                    var destination = selected.get();
                    documentWriter.save(
                            document, destination.path().toAbsolutePath().normalize(), destination.overwrite());
                    return selected;
                };
        return exportDocument(exporter, "vxb", true, parent, captureExportDocument(true));
    }

    public CompletableFuture<Void> exportDocument(
            dev.voxelbrew.api.exporter.ExporterProvider<?> provider, dev.voxelbrew.api.exporter.ExportWindow parent) {
        String id = provider.descriptor().id();
        var exporter = provider.create();
        var captured = captureExportDocument(id.equals("vxb"));
        if (id.equals("3mf") && captured.printSetup().instances().isEmpty()) {
            return CompletableFuture.failedFuture(
                    new java.io.IOException("No objects on the active plate; place a model in the Slicer first"));
        }
        if (id.equals("gcode") && captured.activeGcode().isEmpty()) {
            return CompletableFuture.failedFuture(
                    new java.io.IOException("No G-code for the active plate; slice it first"));
        }
        return exportDocument(exporter, id, id.equals("vxb"), parent, captured);
    }

    private CadDocument captureExportDocument(boolean savesDocument) {
        requireIdleIo();
        if (savesDocument) lastSaveSucceeded = false;
        capturePrintWorkspace();
        return session.document();
    }

    private CompletableFuture<Void> exportDocument(
            dev.voxelbrew.api.exporter.Exporter<?> exporter,
            String exporterId,
            boolean savesDocument,
            dev.voxelbrew.api.exporter.ExportWindow parent,
            CadDocument captured) {
        long generation = documentGeneration.get();
        ioBusy = true;
        onChanged.run();
        var result = new CompletableFuture<Void>();
        var selectedPath = new java.util.concurrent.atomic.AtomicReference<Path>();
        class TrackingWindow
                implements dev.voxelbrew.api.exporter.ExportWindow,
                        dev.voxelbrew.util.preferences.PluginDirectorySource {
            @Override
            public Path pluginDirectory() {
                return pluginDirectory;
            }

            @Override
            public <R> java.util.Optional<R> show(dev.voxelbrew.api.exporter.ExportDialog<R> dialog) {
                var selection = new CompletableFuture<java.util.Optional<R>>();
                dispatch.accept(() -> {
                    try {
                        var value = parent.show(dialog);
                        if (savesDocument) {
                            value.ifPresent(settings -> {
                                if (settings
                                        instanceof
                                        dev.voxelbrew.api.exporter.FileExportDialog.Destination destination) {
                                    selectedPath.set(
                                            destination.path().toAbsolutePath().normalize());
                                }
                            });
                        }
                        selection.complete(value);
                    } catch (RuntimeException | LinkageError failure) {
                        selection.completeExceptionally(failure);
                    }
                });
                return selection.join();
            }
        }
        dev.voxelbrew.api.exporter.ExportWindow trackingWindow = new TrackingWindow();
        try {
            CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return exporter.export(captured, trackingWindow);
                                } catch (Exception failure) {
                                    throw new CompletionException(failure);
                                }
                            },
                            io)
                    .whenComplete((settings, failure) -> {
                        if (failure != null || settings.isEmpty()) {
                            Throwable cause =
                                    failure instanceof CompletionException completion && completion.getCause() != null
                                            ? completion.getCause()
                                            : failure;
                            if (cause == null || cause instanceof CancellationException) {
                                log.info("Export {} cancelled", exporterId);
                                dispatch.accept(() -> {
                                    ioBusy = false;
                                    onChanged.run();
                                    result.cancel(false);
                                });
                            } else {
                                log.error("Export {} failed", exporterId, cause);
                                finishIo(
                                        result,
                                        cause instanceof Exception exception ? exception : new RuntimeException(cause),
                                        false);
                            }
                            return;
                        }
                        log.info("Export {} succeeded", exporterId);
                        dispatch.accept(() -> {
                            if (closed) {
                                result.cancel(false);
                                return;
                            }
                            ioBusy = false;
                            Path savedPath = selectedPath.get();
                            if (savesDocument && savedPath != null && documentGeneration.get() == generation) {
                                saved = captured;
                                unsavedImport = false;
                                path = savedPath;
                                lastSaveSucceeded = true;
                                onProjectSaved.accept(path);
                                onFileUsed.accept(savedPath);
                            }
                            onChanged.run();
                            result.complete(null);
                        });
                    });
        } catch (RuntimeException | LinkageError failure) {
            log.error("Export {} failed", exporterId, failure);
            finishIo(result, failure instanceof Exception exception ? exception : new RuntimeException(failure), false);
        }
        return result;
    }

    /** Headless convenience path; the loader stays alive through asynchronous completion. */
    public CompletableFuture<Void> importFile(
            java.util.function.Supplier<dev.voxelbrew.api.importer.ImporterProviderRegistry> importers,
            String format,
            Path source,
            Map<String, String> options) {
        try {
            var registry = importers.get().withDirectory(pluginDirectory);
            try {
                dev.voxelbrew.api.importer.ImportWindow window = new dev.voxelbrew.api.importer.ImportWindow() {
                    @Override
                    public <R> java.util.Optional<R> show(dev.voxelbrew.api.importer.ImportDialog<R> dialog) {
                        if (dialog instanceof dev.voxelbrew.api.importer.FileImportDialog file) {
                            if (!options.isEmpty()) {
                                throw new IllegalArgumentException("Unknown import options: " + options.keySet());
                            }
                            @SuppressWarnings("unchecked")
                            R settings = (R)
                                    file.buildSettings(Map.of(file.sourceInput().id(), source.toString()));
                            return java.util.Optional.of(settings);
                        }
                        throw new UnsupportedOperationException("Unsupported import dialog: "
                                + dialog.getClass().getName());
                    }
                };
                return importDocument(registry.require(format), window).whenComplete((value, failure) -> {
                    try {
                        registry.close();
                    } catch (java.io.IOException closeFailure) {
                        log.warn("Closing importer registry", closeFailure);
                    }
                });
            } catch (RuntimeException failure) {
                registry.close();
                throw failure;
            }
        } catch (java.io.IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Runs import on the file worker and dispatches dialog interactions to the UI thread. */
    public CompletableFuture<Void> importDocument(
            dev.voxelbrew.api.importer.ImporterProvider provider, dev.voxelbrew.api.importer.ImportWindow parent) {
        requireIdleIo();
        long generation = documentGeneration.get();
        ioBusy = true;
        onChanged.run();
        var result = new CompletableFuture<Void>();
        dev.voxelbrew.api.importer.ImportWindow interaction = new dev.voxelbrew.api.importer.ImportWindow() {
            @Override
            public <R> java.util.Optional<R> show(dev.voxelbrew.api.importer.ImportDialog<R> dialog) {
                var selection = new CompletableFuture<java.util.Optional<R>>();
                dispatch.accept(() -> {
                    try {
                        selection.complete(parent.show(dialog));
                    } catch (RuntimeException | LinkageError failure) {
                        selection.completeExceptionally(failure);
                    }
                });
                return selection.join();
            }
        };
        try {
            CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return provider.create().importFrom(interaction);
                                } catch (Exception failure) {
                                    throw new CompletionException(failure);
                                }
                            },
                            io)
                    .whenComplete((imported, failure) -> {
                        Throwable cause =
                                failure instanceof CompletionException completion && completion.getCause() != null
                                        ? completion.getCause()
                                        : failure;
                        if (cause != null) {
                            finishIo(
                                    result,
                                    cause instanceof Exception exception ? exception : new RuntimeException(cause));
                            return;
                        }
                        dispatch.accept(() -> {
                            if (closed || documentGeneration.get() != generation) {
                                result.cancel(false);
                                return;
                            }
                            ioBusy = false;
                            if (imported.isPresent()) {
                                replace(imported.orElseThrow(), null);
                                unsavedImport = true;
                            }
                            onChanged.run();
                            if (imported.isEmpty()) result.cancel(false);
                            else result.complete(null);
                        });
                    });
        } catch (RuntimeException | LinkageError failure) {
            finishIo(result, failure instanceof Exception exception ? exception : new RuntimeException(failure));
        }
        return result;
    }

    private void finishIo(CompletableFuture<Void> future, Exception failure) {
        finishIo(future, failure, true);
    }

    private void finishIo(CompletableFuture<Void> future, Exception failure, boolean logFailure) {
        if (logFailure && failure != null) log.error("Document file operation failed", failure);
        dispatch.accept(() -> {
            if (closed) {
                future.cancel(false);
                return;
            }
            ioBusy = false;
            if (failure != null) error = failure.getMessage();
            onChanged.run();
            if (failure == null) future.complete(null);
            else future.completeExceptionally(failure);
        });
    }

    private void requireIdleIo() {
        if (closed || ioBusy) throw new IllegalStateException("Wait for the current file operation");
        error = "";
    }

    private void schedule(CadDocument document, boolean isPreview) {
        if (closed) return;
        long ticket = sequence.incrementAndGet();
        log.debug("Queued rebuild {} for document {} (preview={})", ticket, document.id(), isPreview);
        rebuilding = true;
        error = "";
        synchronized (queueLock) {
            pending = new Request(ticket, documentGeneration.get(), session.revision(), document, isPreview);
            if (!running) {
                running = true;
                worker.execute(this::drain);
            }
        }
        onChanged.run();
    }

    private void drain() {
        while (true) {
            Request request;
            synchronized (queueLock) {
                request = pending;
                pending = null;
                if (request == null || closed) {
                    running = false;
                    return;
                }
            }
            Build result;
            String message = "";
            try {
                result = evaluator.apply(request.document());
            } catch (RuntimeException | LinkageError failure) {
                log.error(
                        "Rebuild {} failed for document {}",
                        request.sequence(),
                        request.document().id(),
                        failure);
                result = new Build(List.of(), List.of(), false);
                message = failure.getMessage();
            }
            Build complete = result;
            String failureMessage = message;
            dispatch.accept(() -> {
                var current = session.snapshot();
                if (closed
                        || request.sequence() != sequence.get()
                        || request.generation() != documentGeneration.get()
                        || request.revision() != current.revision()
                        || !request.document().id().equals(current.document().id())) {
                    log.debug("Discarding obsolete rebuild {}", request.sequence());
                    return;
                }
                if (!complete.valid() && !request.preview()) {
                    log.warn(
                            "Document {} revision {} has rebuild diagnostics; geometry is unavailable",
                            request.document().id(),
                            request.revision());
                }
                log.debug(
                        "Published rebuild {} with {} bodies",
                        request.sequence(),
                        complete.bodies().size());
                build = complete;
                rebuilding = false;
                displayedPreview = request.preview();
                error = failureMessage;
                onChanged.run();
            });
        }
    }

    public CompletableFuture<dev.voxelbrew.api.model.construction.ConstructionGeometry.Datum> previewConstruction(
            ConstructionFeature feature) {
        var captured = session.document();
        return CompletableFuture.supplyAsync(
                () -> {
                    var temporary = new ModelSession(captured);
                    if (captured.features().stream().anyMatch(f -> f.id().equals(feature.id()))) {
                        temporary.replace(feature);
                    } else {
                        temporary.add(feature);
                    }
                    try (var evaluation = Evaluator.evaluate(temporary.document(), pluginDirectory)) {
                        if (!evaluation.valid()) {
                            throw new IllegalArgumentException(evaluation.diagnostics().stream()
                                    .filter(d -> d.state() == Evaluation.State.FAILED
                                            || d.state() == Evaluation.State.BLOCKED)
                                    .map(Evaluation.Diagnostic::message)
                                    .collect(java.util.stream.Collectors.joining("; ")));
                        }
                        return evaluation.construction().get(feature.id());
                    }
                },
                worker);
    }

    @Override
    public void close() {
        if (closed) return;
        log.debug("Closing document controller for {}", session.document().id());
        closed = true;
        sequence.incrementAndGet();
        synchronized (queueLock) {
            pending = null;
        }
        worker.shutdown();
        io.shutdown();
        onDocumentEnd.run();
    }
}
