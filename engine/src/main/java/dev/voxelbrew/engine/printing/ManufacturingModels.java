package dev.voxelbrew.engine.printing;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.mesh.*;
import dev.voxelbrew.api.model.printing.ManufacturingModel;
import dev.voxelbrew.api.model.printing.PrintProfile;
import dev.voxelbrew.api.model.printing.PrintSetup;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.*;

/** Engine evaluation and tessellation adapters for detached manufacturing models. */
public final class ManufacturingModels {
    public static ManufacturingModel evaluate(CadDocument document, MeshOptions options) {
        return evaluate(document, options, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    public static ManufacturingModel evaluate(
            CadDocument document, MeshOptions options, java.nio.file.Path pluginDirectory) {
        try (var evaluation = Evaluator.evaluate(document, pluginDirectory)) {
            return from(document, evaluation, options);
        }
    }

    public static ManufacturingModel from(CadDocument document, Evaluation evaluation, MeshOptions options) {
        var meshes = new LinkedHashMap<String, MeshData>();
        var bodies = evaluation.requireCompleteDocument();
        var detached = dev.voxelbrew.util.concurrent.ParallelWork.map(
                bodies, body -> body.solid().tessellate(options));
        for (int i = 0; i < bodies.size(); i++) meshes.put(bodies.get(i).id(), detached.get(i));
        return fromMeshes(document.id(), DocumentCodec.fingerprint(document), document.printSetup(), meshes);
    }

    /** Prepare the active plate from current geometry, retaining the saved document revision. */
    public static ManufacturingModel fromMeshes(
            String documentId, String revision, PrintSetup setup, Map<String, MeshData> meshes) {
        var removed = new HashSet<String>();
        for (var instance : setup.instances()) {
            if (!meshes.containsKey(instance.bodyId())) removed.add(instance.id());
        }
        if (!removed.isEmpty()) {
            var supports = setup.supports().stream()
                    .filter(support -> !removed.contains(support.instanceId()))
                    .toList();
            setup = setup.withSupports(supports)
                    .withInstances(setup.instances().stream()
                            .filter(instance -> !removed.contains(instance.id()))
                            .toList());
        }
        return new ManufacturingModel(documentId, revision, setup, PrintProfile.resolve(setup), meshes);
    }

    private ManufacturingModels() {}
}
