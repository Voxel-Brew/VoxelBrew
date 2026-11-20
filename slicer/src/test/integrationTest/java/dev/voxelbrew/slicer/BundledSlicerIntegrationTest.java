package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.api.model.printing.Cancellation;
import dev.voxelbrew.engine.printing.ManufacturingModels;
import dev.voxelbrew.printer.DefaultPrinterProfileCatalog;
import dev.voxelbrew.printer.ProfileHardware;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BundledSlicerIntegrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"prusa-slicer, gcode", "orca, gcode.3mf"})
    void slicesUsingTheReadOnlyInstallationWithoutAnExecutableSetting(String providerId, String format)
            throws Exception {
        var store = new SlicerInstallationStore(
                new dev.voxelbrew.util.preferences.UserConfigServiceImpl(directory, "config.properties"));
        var installed = store.load();
        assumeTrue(!installed.isEmpty(), "This is a thin build");
        assertThat(installed).hasSize(2).allMatch(SlicerInstallation::bundled);
        var installation = installed.stream()
                .filter(entry -> entry.providerId().equals(providerId))
                .findFirst()
                .orElseThrow();
        assertThat(store.require(installation.id().toString())).isEqualTo(installation);
        var session = PrintingTest.reference();
        var setup = session.document().printSetup();
        if (providerId.equals("orca")) {
            var catalog = new DefaultPrinterProfileCatalog(directory.resolve("profiles")).load();
            setup = setup.withPrinter(ProfileHardware.resolve(catalog.require("bambu-lab-a1")));
        }
        session.setPrintSetup(setup.withProfile(providerId, setup.printerId(), setup.materialId(), setup.process()));
        var model = ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        try (var registry = new SlicerRegistry().withDirectory(directory.resolve("plugins"))) {
            var result = SliceService.slice(
                            registry.require(providerId),
                            model,
                            installation.executionWindow(Map.of()),
                            new Cancellation(),
                            System.out::println)
                    .orElseThrow();
            assertThat(result.artifact().format()).isEqualTo(format);
            assertThat(result.artifact().metadata().get("backend_version")).contains(installation.version());
            assertThat(result.job().modelLayers()).isNotEmpty();
        }
    }
}
