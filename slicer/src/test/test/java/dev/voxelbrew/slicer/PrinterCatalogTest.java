package dev.voxelbrew.slicer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.slicer.PrinterProfileCatalog;
import dev.voxelbrew.api.slicer.SlicerRuntime;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterProfileCatalogTest {
    @TempDir
    Path directory;

    private PrinterProfileCatalog catalog() {
        return SlicerRuntime.load().printers(directory);
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
            assertThat(entry.image()).as(entry.toString()).isNotNull();
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
                .withPrinter(preset)
                .withInstances(List.of(new BodyInstance("part", "body", Transform.IDENTITY)))
                .addPlate("second")
                .renamePlate("second", "Second")
                .selectPlate("plate-1")
                .moveInstance("part", "second")
                .withProfile("prusa-slicer", preset.printer().id(), "PETG", ProcessSettings.DEFAULT);
        assertThat(setup.printerSnapshot()).isEqualTo(preset);
        var doc = new CadDocument("doc", List.of(), 0, setup);
        assertThat(DocumentCodec.decode(DocumentCodec.encode(doc))).isEqualTo(doc);
        assertThat(DocumentCodec.decodeJson(DocumentCodec.encodeJson(doc))).isEqualTo(doc);
        assertThat(setup.removeActivePlate().printerSnapshot()).isEqualTo(preset);
        assertThat(PrintProfile.resolve(setup).printer().usableHeight()).isEqualTo(180);
        var session = new ModelSession(doc);
        session.setPrintSetup(setup.withPrinter(preset.rectangular(220, 240, 260)));
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
        var saved = PrintSetup.empty().withPrinter(catalog().load().require("my-printer"));
        Files.writeString(directory.resolve("broken.json"), "not json");
        Files.writeString(directory.resolve("collision.json"), custom("generic-marlin-200-0.4"));
        var loaded = catalog().load();
        assertThat(loaded.diagnostics().size()).isEqualTo(2);
        assertThat(loaded.entries().getFirst().definition().printer().id()).isEqualTo("my-printer");
        assertThat(loaded.require("my-printer")).isEqualTo(saved.printerSnapshot());
        Files.writeString(directory.resolve("duplicate.json"), custom("my-printer"));
        assertThatThrownBy(() -> catalog().load().require("my-printer")).isInstanceOf(IllegalArgumentException.class);
        Files.delete(directory.resolve("custom.json"));
        Files.delete(directory.resolve("duplicate.json"));
        assertThat(PrintProfile.resolve(saved).printer().usableHeight()).isEqualTo(200);
        assertThat(DocumentCodec.decode(DocumentCodec.encode(new CadDocument("doc", List.of(), 0, saved)))
                        .printSetup())
                .isEqualTo(saved);
    }

    private String customImage() {
        return catalog().load().entries().stream()
                .filter(e -> e.definition().printer().id().equals("pictured"))
                .findFirst()
                .orElseThrow()
                .image();
    }

    @Test
    void customImagesFollowJsonFilenameAndFormatPriorityOnReload() throws Exception {
        Files.writeString(directory.resolve("custom.printer.json"), custom("pictured"));
        Files.write(directory.resolve("generic.png"), new byte[] {1});
        Files.write(directory.resolve("pictured.png"), new byte[] {1});
        assertThat(customImage())
                .as("Neither the JSON image field nor the printer ID selects the image")
                .isNull();
        var extensions = List.of("PNG", "jpg", "jpeg", "webp", "gif", "bmp");
        for (var extension : extensions) Files.write(directory.resolve("custom.printer." + extension), new byte[] {1});
        for (var extension : extensions) {
            var expected = directory.resolve("custom.printer." + extension);
            assertThat(customImage()).isEqualTo(expected.toUri().toString());
            Files.delete(expected);
        }
        Files.write(directory.resolve("custom.printer.svg"), new byte[] {1});
        assertThat(customImage())
                .as("Unsupported formats retain the default illustration")
                .isNull();
    }

    @Test
    void customImageFieldIsIgnoredAndOversizeCandidatesDoNotHideJpeg() throws Exception {
        Files.writeString(directory.resolve("foo.json"), custom("pictured").replace("\"generic.png\"", "{}"));
        assertThat(catalog().load().diagnostics()).isEqualTo(List.of());
        Files.write(directory.resolve("foo.png"), new byte[5 * 1024 * 1024 + 1]);
        Files.write(directory.resolve("foo.jpg"), new byte[] {1});
        assertThat(customImage()).isEqualTo(directory.resolve("foo.jpg").toUri().toString());
        Files.delete(directory.resolve("foo.jpg"));
        assertThat(customImage()).isNull();
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
