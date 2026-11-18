package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.mesh.MeshOptions;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.printing.PrusaConfiguration;
import dev.voxelbrew.printer.ProfileHardware;
import dev.voxelbrew.slicer.prusa.PrusaSlicerBackend;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterPresetSlicingTest {
    @TempDir
    Path directory;

    @Test
    void selectedHardwareControlsRealSliceIncludingVendorPresets() throws Exception {
        var catalog = new dev.voxelbrew.printer.DefaultPrinterProfileCatalog(directory).load();
        var session = SlicerAcceptanceTest.cube();
        session.setPrintSetup(session.document()
                .printSetup()
                .withPrinter(ProfileHardware.resolve(catalog.require("generic-marlin-200-0.4"))
                        .rectangular(240, 230, 260)));
        var model = ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        assertThat(PrusaConfiguration.settings(model).get("bed_shape"))
                .isEqualTo("0.0x0.0,240.0x0.0,240.0x230.0,0.0x230.0");
        assertThat(PrusaConfiguration.settings(model).get("max_print_height")).isEqualTo("260.0");
        var target = directory.resolve("printer.gcode");
        var result = SliceService.slice(
                new PrusaSlicerBackend(),
                session.document(),
                model,
                PrusaSlicerBackend.defaultExecutable(),
                target,
                false,
                new Cancellation(),
                ignored -> {},
                dev.voxelbrew.api.slicer.Slicing.DIRECT);
        assertThat(result.job().modelLayers().isEmpty()).isFalse();
        for (String id : java.util.List.of("bambu-lab-a1-mini", "prusa-xl-5-tool")) {
            session.setPrintSetup(
                    session.document().printSetup().withPrinter(ProfileHardware.resolve(catalog.require(id))));
            var vendorModel = ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
            var settings = PrusaConfiguration.settings(vendorModel);
            assertThat(settings.get("gcode_flavor")).isEqualTo("marlin2");
            assertThat(settings.get("printer_settings_id")).isEqualTo(id);
            assertThat(settings.get("max_print_height"))
                    .isEqualTo(Double.toString(catalog.require(id).printer().usableHeight()));
            var sliced = SliceService.slice(
                    new PrusaSlicerBackend(),
                    session.document(),
                    vendorModel,
                    PrusaSlicerBackend.defaultExecutable(),
                    target,
                    true,
                    new Cancellation(),
                    ignored -> {},
                    dev.voxelbrew.api.slicer.Slicing.DIRECT);
            assertThat(sliced.job().modelLayers().isEmpty()).isFalse();
        }
        session.setPrintSetup(session.document()
                .printSetup()
                .withPrinter(ProfileHardware.resolve(catalog.require("generic-marlin-200-0.4"))
                        .rectangular(10, 10, 5)));
        assertThat(PrintValidation.validate(ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT))
                        .diagnostics()
                        .isEmpty())
                .isFalse();
    }
}
