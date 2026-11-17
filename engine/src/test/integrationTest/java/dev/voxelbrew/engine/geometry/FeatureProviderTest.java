package dev.voxelbrew.engine.geometry;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.geometry.Bounds;
import dev.voxelbrew.engine.feature.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeatureProviderTest {
    @TempDir
    Path directory;

    private Path plugins() throws Exception {
        var plugins = Files.createDirectory(directory.resolve("feature plugins with spaces"));
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("external features.jar"));
        return plugins;
    }

    private static double expected(double height) {
        return Math.PI * (100 - 4) * height;
    }

    private Path providers(String... names) throws Exception {
        var plugins = Files.createDirectory(directory.resolve("broken feature plugins with spaces"));
        String service = "META-INF/services/" + FeatureProvider.class.getName();
        try (var source = new JarFile(System.getProperty("voxelbrew.test.plugin"));
                var target = new JarOutputStream(Files.newOutputStream(plugins.resolve("providers.jar")))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().equals(service)) continue;
                target.putNextEntry(new JarEntry(entry.getName()));
                if (!entry.isDirectory()) {
                    try (var input = source.getInputStream(entry)) {
                        input.transferTo(target);
                    }
                }
                target.closeEntry();
            }
            target.putNextEntry(new JarEntry(service));
            target.write((String.join("\n", names) + "\n").getBytes(StandardCharsets.UTF_8));
            target.closeEntry();
        }
        return plugins;
    }

    @Test
    void descriptorAssertionDoesNotPreventDiscoveringAHealthyExternalProvider() throws Exception {
        var plugins = providers("consumer.BrokenFeatureProviders$Descriptor", "consumer.SpacerProvider");
        try (var registry = new FeatureRegistry(plugins)) {
            assertThat(registry.descriptors().stream()
                            .map(FeatureDescriptor::key)
                            .toList())
                    .isEqualTo(List.of("example.spacer/spacer"));
            assertThat(registry.diagnostics().stream().anyMatch(d -> d.contains("Descriptor assertion")))
                    .isTrue();
            var spacer = registry.create("spacer", "example.spacer", "spacer", Map.of());
            try (var solid = registry.evaluate(spacer)) {
                assertThat(solid.volume()).isCloseTo(expected(8), org.assertj.core.data.Offset.offset(.01));
            }
        }
    }

    @Test
    void codecAndEvaluationErrorsPreserveEditsPayloadsAndNativeOwnership() throws Exception {
        int handles = NativeKernel.liveHandles();
        var plugins = providers(
                "consumer.SpacerProvider",
                "consumer.BrokenFeatureProviders$Encoder",
                "consumer.BrokenFeatureProviders$Decoder",
                "consumer.BrokenFeatureProviders$Evaluation",
                "consumer.BrokenFeatureProviders$NestedServices");
        try (var registry = new FeatureRegistry(plugins)) {
            var good = registry.create("good", "example.spacer", "spacer", Map.of());
            assertThatThrownBy(() -> registry.create("new-feature", "example.assert.encode", "spacer", Map.of()))
                    .isInstanceOf(ModelException.class);
            var encoder = new Feature.Custom("encoder", "example.assert.encode", "spacer", 1, good.payload());
            var session = new ModelSession(new CadDocument("codec-errors", List.of(encoder), 1));
            var before = session.snapshot();
            var editFailure = catchThrowable(() -> session.editDimension("encoder", "height", 12, plugins));
            assertThat(editFailure).isInstanceOf(ModelException.class);
            assertThat(editFailure.getMessage().contains("encoder")).isTrue();
            assertThat(session.snapshot()).isEqualTo(before);
            assertThat(session.canUndo()).isFalse();
            for (String provider :
                    List.of("example.assert.decode", "example.assert.evaluate", "example.service.evaluate")) {
                var broken = new Feature.Custom("broken", provider, "spacer", 1, good.payload());
                var split = new Feature.SplitBody(
                        "dependent", new Feature.BodyRef("broken", "body"), new Feature.SplitPlane(1, 0, 0, 0));
                var document = new CadDocument("provider-errors", List.of(good, broken, split), 3);
                try (var result = Evaluator.evaluate(document, plugins)) {
                    assertThat(result.diagnostics().stream()
                                    .map(Evaluation.Diagnostic::state)
                                    .toList())
                            .isEqualTo(List.of(Evaluation.State.OK, Evaluation.State.FAILED, Evaluation.State.BLOCKED));
                    assertThat(result.diagnostics().get(1).featureId()).isEqualTo("broken");
                    assertThatThrownBy(result::bodies).isInstanceOf(IllegalStateException.class);
                    assertThatThrownBy(result::requireCompleteDocument).isInstanceOf(IllegalStateException.class);
                }
                Path file = directory.resolve(provider + ".vxb");
                DocumentWriter.save(document, file, false);
                assertThat(DocumentReader.load(file)).isEqualTo(document);
                assertThat(((Feature.Custom)
                                        DocumentReader.load(file).features().get(1))
                                .payload())
                        .containsExactly(good.payload());
                assertThat(NativeKernel.liveHandles()).isEqualTo(handles);
            }
        }
        assertThat(NativeKernel.liveHandles()).isEqualTo(handles);
    }

    @Test
    void spacerRoundTripsEditsAndConsumesNamedOutputsWithoutLeakingSolids() throws Exception {
        int handles = NativeKernel.liveHandles();
        Path plugins = plugins();
        var session = new ModelSession(CadDocument.empty());
        try (var registry = new FeatureRegistry(plugins)) {
            session.add(registry.create("spacer", "example.spacer", "spacer", Map.of()));
        }
        try (var result = session.evaluate(plugins)) {
            var body = result.bodies().getFirst();
            assertThat(body.id()).isEqualTo("spacer.body");
            assertThat(body.solid().volume()).isCloseTo(expected(8), org.assertj.core.data.Offset.offset(0.01));
            assertThat(body.solid().bounds()).isEqualTo(new Bounds(-10, -10, 0, 10, 10, 8));
            assertThat(body.solid().contains(0, 0, 4)).isFalse();
            assertThat(body.solid().contains(5, 0, 4)).isTrue();
        }
        Path file = directory.resolve("spacer.vxb");
        DocumentWriter.save(session.document(), file, false);
        session = new ModelSession(DocumentReader.load(file));
        var original = session.document();
        session.editDimension("spacer", "height", 12, plugins);
        try (var result = session.evaluate(plugins)) {
            assertThat(result.bodies().getFirst().solid().volume())
                    .isCloseTo(expected(12), org.assertj.core.data.Offset.offset(0.01));
        }
        assertThat(session.undo()).isTrue();
        assertThat(session.document()).isEqualTo(original);
        assertThat(session.redo()).isTrue();
        session.add(new Feature.SplitBody(
                "split", new Feature.BodyRef("spacer", "body"), new Feature.SplitPlane(1, 0, 0, 0)));
        try (var result = session.evaluate(plugins)) {
            assertThat(result.bodies().size()).isEqualTo(2);
            assertThat(result.bodies().stream()
                            .mapToDouble(b -> b.solid().volume())
                            .sum())
                    .isCloseTo(expected(12), org.assertj.core.data.Offset.offset(0.01));
        }
        session.setCursor(0);
        try (var result = session.evaluate(plugins)) {
            assertThat(result.bodies().isEmpty()).isTrue();
            assertThatThrownBy(result::requireCompleteDocument).isInstanceOf(IllegalStateException.class);
        }
        assertThat(NativeKernel.liveHandles()).isEqualTo(handles);
    }

    @Test
    void missingPluginPreservesExactPayloadAndReinstallationRepairsSameIdentities() throws Exception {
        Path plugins = plugins(),
                jar = plugins.resolve("external features.jar"),
                hidden = directory.resolve("removed provider.jar");
        var session = new ModelSession(CadDocument.empty());
        try (var registry = new FeatureRegistry(plugins)) {
            session.add(registry.create("spacer", "example.spacer", "spacer", Map.of("height", 12.0)));
        }
        session.add(new Feature.SplitBody(
                "split", new Feature.BodyRef("spacer", "body"), new Feature.SplitPlane(1, 0, 0, 0)));
        var before = session.document();
        Path file = directory.resolve("repairable.vxb");
        DocumentWriter.save(before, file, false);
        Files.move(jar, hidden);
        var missing = DocumentReader.load(file);
        try (var result = Evaluator.evaluate(missing, plugins)) {
            assertThat(result.diagnostics().stream()
                            .map(Evaluation.Diagnostic::state)
                            .toList())
                    .isEqualTo(List.of(Evaluation.State.FAILED, Evaluation.State.BLOCKED));
            assertThat(result.diagnostics().getFirst().message().contains("Missing feature provider"))
                    .isTrue();
            assertThatThrownBy(result::bodies).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(result::requireCompleteDocument).isInstanceOf(IllegalStateException.class);
        }
        DocumentWriter.save(missing, file, true);
        var preserved = DocumentReader.load(file);
        assertThat(preserved).isEqualTo(before);
        assertThat(((Feature.Custom) preserved.features().getFirst()).payload())
                .containsExactly(((Feature.Custom) before.features().getFirst()).payload());
        Files.move(hidden, jar);
        try (var result = Evaluator.evaluate(preserved, plugins)) {
            result.requireCompleteDocument();
            assertThat(result.bodies().stream().map(Evaluation.Body::id).toList())
                    .isEqualTo(List.of("split.negative", "split.positive"));
            assertThat(result.bodies().stream()
                            .mapToDouble(b -> b.solid().volume())
                            .sum())
                    .isCloseTo(expected(12), org.assertj.core.data.Offset.offset(0.01));
        }
    }

    @Test
    void futureOrCorruptPayloadsRemainSavableAndProviderFailuresAreIsolated() throws Exception {
        int handles = NativeKernel.liveHandles();
        var plugins = plugins();
        try (var registry = new FeatureRegistry(plugins)) {
            assertThat(registry.descriptors().size()).isEqualTo(2);
            for (var word : List.of("Duplicate", "Incompatible", "MissingFeatureProvider"))
                assertThat(registry.diagnostics().stream().anyMatch(d -> d.contains(word)))
                        .as(registry.diagnostics().toString())
                        .isTrue();
            var session = new ModelSession(CadDocument.empty());
            session.add(registry.create("good", "example.spacer", "spacer", Map.of()));
            var before = session.snapshot();
            assertThatThrownBy(() -> session.editDimension("good", "innerDiameter", 30, plugins))
                    .isInstanceOf(ModelException.class);
            assertThat(session.snapshot()).isEqualTo(before);
            assertThatThrownBy(() -> registry.create("bad", "example.spacer", "spacer", Map.of("unknown", 3.0)))
                    .isInstanceOf(ModelException.class);
            session.add(registry.create("failed", "example.failed", "spacer", Map.of()));
            try (var result = session.evaluate(plugins)) {
                assertThat(result.diagnostics().getFirst().state()).isEqualTo(Evaluation.State.OK);
                assertThat(result.diagnostics().getLast().state()).isEqualTo(Evaluation.State.FAILED);
                assertThat(result.diagnostics().getLast().message().contains("Deliberate"))
                        .isTrue();
                assertThatThrownBy(result::bodies).isInstanceOf(IllegalStateException.class);
            }
        }
        for (int version : new int[] {1, 999}) {
            var custom = new Feature.Custom("opaque", "example.spacer", "spacer", version, new byte[] {0, -1, 13, 10});
            var document = new CadDocument("future", List.of(custom), 1);
            Path file = directory.resolve("payload-" + version + ".vxb");
            DocumentWriter.save(document, file, false);
            try (var result = Evaluator.evaluate(DocumentReader.load(file), plugins)) {
                assertThat(result.valid()).isFalse();
            }
            DocumentWriter.save(DocumentReader.load(file), file, true);
            assertThat(DocumentReader.load(file)).isEqualTo(document);
        }
        assertThat(NativeKernel.liveHandles()).isEqualTo(handles);
    }
}
