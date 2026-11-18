package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.printer.PrinterProfileCatalog;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.printer.PrinterResources;
import dev.voxelbrew.printer.ProfileHardware;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterProfileCatalogTest {
    @TempDir
    Path directory;

    private PrinterProfileCatalog catalog() {
        return new dev.voxelbrew.printer.DefaultPrinterProfileCatalog(directory);
    }

    @Test
    void exportsEditableDefaultsWithMatchingImagesWithoutOverwritingUserFiles() throws Exception {
        assertThat(catalog().exportDefaults(Set.of())).isEqualTo(0);
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isEqualTo(0);
        }
        var selected = Set.of("bambu-lab-a1-mini", "prusa-mk4");
        assertThat(catalog().exportDefaults(selected)).isEqualTo(2);
        var loaded = catalog().load();
        assertThat(loaded.diagnostics().isEmpty())
                .as(loaded.diagnostics().toString())
                .isTrue();
        assertThat(loaded.entries().size()).isEqualTo(64);
        assertThat(Files.exists(directory.resolve("custom-bambu-lab-a1.json"))).isFalse();
        assertThat(Files.exists(directory.resolve("custom-bambu-lab-a1.png"))).isFalse();
        var copy = loaded.entries().stream()
                .filter(entry -> entry.definition().printer().id().equals("custom-bambu-lab-a1-mini"))
                .findFirst()
                .orElseThrow();
        assertThat(copy.bundled()).isFalse();
        assertThat(copy.image())
                .isEqualTo(directory
                        .resolve("custom-bambu-lab-a1-mini.png")
                        .toUri()
                        .toString());
        assertThat(copy.definition().printer().bed())
                .isEqualTo(loaded.require("bambu-lab-a1-mini").printer().bed());
        var json = directory.resolve("custom-bambu-lab-a1-mini.json");
        Files.writeString(json, Files.readString(json).replace("A1 mini", "My edited printer"));
        var edited = Files.readAllBytes(json);
        var picture = directory.resolve("custom-bambu-lab-a1-mini.png");
        Files.write(picture, new byte[] {1, 2, 3});
        assertThat(catalog().exportDefaults(selected)).isEqualTo(0);
        assertThat(Files.readAllBytes(json)).containsExactly(edited);
        assertThat(Files.readAllBytes(picture)).containsExactly(new byte[] {1, 2, 3});
        assertThat(Files.exists(directory.resolve("PHOTO-LICENSE.txt"))).isTrue();
        assertThat(Files.exists(directory.resolve("photo-sources.txt"))).isTrue();
    }

    @Test
    void bundledCatalogIsCompleteAndHasImages() throws Exception {
        try (var image = java.net.URI.create(catalog().fallbackImage()).toURL().openStream()) {
            assertThat(image.readAllBytes().length > 0).isTrue();
        }
        var result = catalog().load();
        assertThat(result.diagnostics()).isEqualTo(List.of());
        assertThat(result.entries().size()).isEqualTo(62);
        for (var entry : result.entries()) {
            assertThat(entry.bundled()).isTrue();
            assertThat(entry.image()).as(entry.name()).isNotNull();
            try (var image = new java.net.URI(entry.image()).toURL().openStream()) {
                assertThat(image.readAllBytes().length > 0).isTrue();
            }
        }
        assertThat(result.require("bambu-lab-a1-mini").printer().usableHeight()).isEqualTo(180);
        assertThat(result.require("snapmaker-u1").toolCount()).isEqualTo(4);
        assertThat(result.require("prusa-pro-ht90").printer().bed().size()).isEqualTo(128);
    }

    @Test
    void snapshotSurvivesCatalogRemovalAllPlateEditsAndRoundTrips() throws Exception {
        var preset = catalog().load().require("bambu-lab-a1-mini");
        var setup = PrintSetup.empty()
                .withPrinter(ProfileHardware.resolve(preset))
                .withInstances(List.of(new BodyInstance("part", "body", Transform.IDENTITY)))
                .addPlate("second")
                .renamePlate("second", "Second")
                .selectPlate("plate-1")
                .moveInstance("part", "second")
                .withProfile("prusa-slicer", preset.printer().id(), "PETG", ProcessSettings.DEFAULT);
        assertThat(setup.printerSnapshot()).isEqualTo(ProfileHardware.resolve(preset));
        var doc = new CadDocument("doc", List.of(), 0, setup);
        assertThat(DocumentCodec.decode(DocumentCodec.encode(doc))).isEqualTo(doc);
        assertThat(DocumentCodec.decodeJson(DocumentCodec.encodeJson(doc))).isEqualTo(doc);
        assertThat(setup.removeActivePlate().printerSnapshot()).isEqualTo(ProfileHardware.resolve(preset));
        assertThat(PrintProfile.resolve(setup).printer().usableHeight()).isEqualTo(180);
        var session = new ModelSession(doc);
        session.setPrintSetup(setup.withPrinter(ProfileHardware.resolve(preset).rectangular(220, 240, 260)));
        assertThat(PrintProfile.resolve(session.document().printSetup())
                        .printer()
                        .usableHeight())
                .isEqualTo(260);
        session.undo();
        assertThat(session.document().printSetup()).isEqualTo(setup);
        assertThat(PrintProfile.resolve(PrintSetup.empty()).printer().usableHeight())
                .isEqualTo(200);
    }

    private String custom(String id) throws Exception {
        try (var stream =
                PrinterResources.class.getResourceAsStream(PrinterResources.ROOT + "generic-marlin-200-0.4.json")) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("generic-marlin-200-0.4", id);
        }
    }

    @Test
    void badFilesAndDuplicateIdsAreIsolatedAndReloadDoesNotChangeSnapshots() throws Exception {
        Files.writeString(directory.resolve("custom.json"), custom("my-printer"));
        assertThat(catalog().load().entries().stream()
                        .filter(entry -> entry.definition().printer().id().equals("my-printer"))
                        .findFirst()
                        .orElseThrow()
                        .bundled())
                .isFalse();
        var saved = PrintSetup.empty()
                .withPrinter(ProfileHardware.resolve(catalog().load().require("my-printer")));
        Files.writeString(directory.resolve("broken.json"), "not json");
        Files.writeString(directory.resolve("collision.json"), custom("generic-marlin-200-0.4"));
        var loaded = catalog().load();
        assertThat(loaded.diagnostics().size()).isEqualTo(2);
        assertThat(loaded.entries().getFirst().definition().printer().id()).isEqualTo("my-printer");
        assertThat(ProfileHardware.resolve(loaded.require("my-printer"))).isEqualTo(saved.printerSnapshot());
        Files.writeString(directory.resolve("duplicate.json"), custom("my-printer"));
        assertThatThrownBy(() -> catalog().load().require("my-printer")).isInstanceOf(IllegalArgumentException.class);
        Files.delete(directory.resolve("custom.json"));
        Files.delete(directory.resolve("duplicate.json"));
        assertThat(PrintProfile.resolve(saved).printer().usableHeight()).isEqualTo(200);
        assertThat(DocumentCodec.decode(DocumentCodec.encode(new CadDocument("doc", List.of(), 0, saved)))
                        .printSetup())
                .isEqualTo(saved);
    }

    @Test
    void customProfilesCopyValidatedImagesAndKeepPrinterSnapshotsStable() throws Exception {
        var original = catalog().load().require("prusa-mk4");
        var image = directory.resolve("source.png");
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png",
                image.toFile());
        var custom = new dev.voxelbrew.api.printer.Profile(
                "custom-test",
                "My printer",
                original.manufacturer(),
                original.bed(),
                original.excludedZones(),
                250,
                0.6,
                1.75,
                1,
                "marlin2",
                original.outputFormat(),
                true,
                null);
        var bundledCopy = new dev.voxelbrew.api.printer.Profile(
                "copy-prusa",
                original.name(),
                original.manufacturer(),
                original.bed(),
                original.excludedZones(),
                original.usableHeight(),
                original.nozzleDiameter(),
                original.filamentDiameter(),
                original.toolCount(),
                original.firmware(),
                original.outputFormat(),
                original.genericGcode(),
                original.imageReference());
        var copied = catalog().save(bundledCopy, null);
        assertThat(copied.imageReference().getScheme()).isEqualTo("file");
        var stored = catalog().save(custom, image);
        assertThat(stored.imageReference()).isNotNull();
        assertThat(Path.of(stored.imageReference())).exists();
        Files.delete(image);
        assertThat(Path.of(stored.imageReference())).exists();
        var snapshot = ProfileHardware.resolve(stored);
        var revised = new dev.voxelbrew.api.printer.Profile(
                stored.id(),
                stored.name(),
                stored.manufacturer(),
                stored.bed(),
                stored.excludedZones(),
                300,
                0.8,
                1.75,
                1,
                stored.firmware(),
                stored.outputFormat(),
                true,
                stored.imageReference());
        catalog().save(revised, null);
        assertThat(snapshot.printer().usableHeight()).isEqualTo(250);
        assertThat(catalog().load().require(stored.id()).usableHeight()).isEqualTo(300);
        assertThatThrownBy(() -> catalog().save(original, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only");
        Files.writeString(image, "not an image");
        assertThatThrownBy(() -> catalog().save(custom, image)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void invalidHardwareIsRejected() throws Exception {
        Files.writeString(
                directory.resolve("bad.json"),
                custom("bad").replace("\"usableHeightMm\": 200", "\"usableHeightMm\": -1"));
        assertThat(catalog().load().diagnostics().size()).isEqualTo(1);
        assertThatThrownBy(() -> catalog().load().require("bad")).isInstanceOf(IllegalArgumentException.class);
    }
}
