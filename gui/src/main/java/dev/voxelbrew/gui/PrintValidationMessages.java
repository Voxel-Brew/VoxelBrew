package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.engine.printing.PrintValidation;
import java.util.function.Function;
import java.util.stream.Collectors;

/** User-facing preflight explanations shared by the placement status and job controls. */
final class PrintValidationMessages {
    static String format(
            PrintValidation.Result result, TranslationService translations, Function<String, String> objectName) {
        return result.diagnostics().stream()
                .map(diagnostic -> {
                    String name = objectName.apply(diagnostic.objectId()) + " [" + diagnostic.objectId() + "]";
                    var mesh = result.meshes().get(diagnostic.objectId());
                    if (diagnostic.code().equals("MESH") && mesh != null) {
                        return translations.translate(
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

    private PrintValidationMessages() {}
}
