package dev.voxelbrew.gui;

import dev.voxelbrew.engine.printing.PrintValidation;
import dev.voxelbrew.util.i18n.Translations;
import java.util.function.Function;
import java.util.stream.Collectors;

/** User-facing preflight explanations shared by the placement status and job controls. */
final class PrintValidationMessages {
    private PrintValidationMessages() {}

    static String format(
            PrintValidation.Result result, Translations translations, Function<String, String> objectName) {
        return result.diagnostics().stream()
                .map(diagnostic -> {
                    String name = objectName.apply(diagnostic.objectId()) + " [" + diagnostic.objectId() + "]";
                    var mesh = result.meshes().get(diagnostic.objectId());
                    if (diagnostic.code().equals("MESH") && mesh != null) {
                        return translations.text(
                                "print.validation.mesh",
                                name,
                                mesh.degenerateTriangles(),
                                mesh.boundaryEdges(),
                                mesh.nonManifoldEdges(),
                                mesh.inconsistentEdges());
                    }
                    return name + ": " + diagnostic.message();
                })
                .collect(Collectors.joining("\n"));
    }
}
