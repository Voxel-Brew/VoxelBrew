package dev.voxelbrew.engine.model;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.sketch.SketchGeometry.Entity;
import dev.voxelbrew.engine.geometry.Solid;
import java.util.List;
import java.util.Map;

/** Owns all native replay results. Callers must close the evaluation, not its borrowed solids. */
public final class Evaluation implements AutoCloseable {
    private final CadDocument document;

    private final List<Diagnostic> diagnostics;

    private final List<Body> bodies;

    private final List<Solid> owned;
    private final List<Feature> resolvedFeatures;

    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    private Map<String, List<Entity>> projections = Map.of();

    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    private Map<Feature.BodyRef, String> bodyIds = Map.of();

    private boolean closed;
    private java.util.Map<String, dev.voxelbrew.api.model.construction.ConstructionGeometry.Datum> construction =
            java.util.Map.of();

    public enum State {
        OK,
        FAILED,
        BLOCKED,
        ROLLED_BACK
    }

    public record Diagnostic(String featureId, State state, String message) {}

    public record Body(String id, Feature.BodyRef output, Solid solid) {}

    Evaluation(CadDocument document, List<Diagnostic> diagnostics, List<Body> bodies, List<Solid> owned) {
        this(document, diagnostics, bodies, owned, document.features());
    }

    Evaluation(
            CadDocument document,
            List<Diagnostic> diagnostics,
            List<Body> bodies,
            List<Solid> owned,
            List<Feature> resolvedFeatures) {
        this.document = document;
        this.diagnostics = List.copyOf(diagnostics);
        this.bodies = List.copyOf(bodies);
        this.owned = List.copyOf(owned);
        this.resolvedFeatures = List.copyOf(resolvedFeatures);
    }

    void construction(java.util.Map<String, dev.voxelbrew.api.model.construction.ConstructionGeometry.Datum> values) {
        construction = java.util.Map.copyOf(values);
    }

    void projections(Map<String, List<Entity>> values) {
        projections = Map.copyOf(values);
    }

    void indexBodyOutputs(Map<Feature.BodyRef, Body> outputs) {
        bodyIds = outputs.values().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Body::output, Body::id));
    }

    public java.util.Map<String, dev.voxelbrew.api.model.construction.ConstructionGeometry.Datum> construction() {
        requireOpen();
        if (!valid()) throw new IllegalStateException("Construction geometry unavailable after failed rebuild");
        return construction;
    }

    /**
     * Detached numeric definitions for the evaluated revision; expressions remain in the source
     * document.
     */
    public List<Feature> resolvedFeatures() {
        return resolvedFeatures;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public boolean valid() {
        return diagnostics.stream().noneMatch(d -> d.state() == State.FAILED || d.state() == State.BLOCKED);
    }

    public List<Body> bodies() {
        requireOpen();
        if (!valid()) {
            throw new IllegalStateException("Rebuild failed; partial or stale geometry is unavailable. " + diagnostics);
        }
        return bodies;
    }

    /**
     * Required by full-part export: a rolled-back or broken document cannot be exported as
     * complete.
     */
    public List<Body> requireCompleteDocument() {
        if (document.cursor() != document.features().size()) {
            throw new IllegalStateException("Return timeline cursor to the end before full-part export");
        }
        return bodies();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            owned.forEach(Solid::close);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Evaluation is closed");
        }
    }
}
