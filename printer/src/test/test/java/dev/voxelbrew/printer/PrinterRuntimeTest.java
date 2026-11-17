package dev.voxelbrew.printer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.printer.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterRuntimeTest {
    @TempDir
    Path directory;

    @Test
    void configurationRoundTripDoesNotExposeSecrets() throws Exception {
        var runtime = new DefaultPrinterRuntime();
        var device = new Printer(
                "test",
                "Workshop",
                Printer.Kind.MOONRAKER,
                "generic-marlin-200-0.4",
                "http://localhost:7125",
                "",
                "",
                "secret-key");
        runtime.save(directory, device);
        assertThat(runtime.devices(directory).getFirst()).isEqualTo(device);
        var file = directory.resolve("test.properties");
        assertThat(Files.readString(file).contains("nozzle_mm")).isFalse();
        Files.writeString(file, "extra=ignored\n", StandardOpenOption.APPEND);
        assertThat(runtime.devices(directory).getFirst()).isEqualTo(device);
        assertThat(device.toString().contains("secret-key")).isFalse();
        runtime.remove(directory, device.id());
        assertThat(runtime.devices(directory).isEmpty()).isTrue();
    }

    @Test
    void rejectsTraversalAndEmbeddedCredentials() {
        var runtime = new DefaultPrinterRuntime();
        assertThatThrownBy(() -> runtime.remove(directory, "../other")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Printer(
                        "test",
                        "test",
                        Printer.Kind.MOONRAKER,
                        "generic-marlin-200-0.4",
                        "http://user:pass@host",
                        "",
                        "",
                        ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void printerNamesAreUniqueIgnoringCaseWhileUuidSurvivesRename() throws Exception {
        var runtime = new DefaultPrinterRuntime();
        var original = new Printer(
                "internal-uuid-1",
                "Lab Klipper",
                Printer.Kind.MOONRAKER,
                "generic-marlin-200-0.4",
                "http://localhost:7125",
                "",
                "",
                "key");
        runtime.save(directory, original);
        var duplicate = new Printer(
                "internal-uuid-2",
                "  LAB KLIPPER  ",
                Printer.Kind.MOONRAKER,
                original.profileId(),
                "http://localhost:7126",
                "",
                "",
                "key");
        assertThatThrownBy(() -> runtime.save(directory, duplicate)).isInstanceOf(java.io.IOException.class);
        assertThat(runtime.devices(directory)).isEqualTo(java.util.List.of(original));

        var renamed = new Printer(
                original.id(),
                "Workshop Printer",
                Printer.Kind.MOONRAKER,
                original.profileId(),
                original.address(),
                "",
                "",
                "key");
        runtime.save(directory, renamed);
        assertThat(runtime.devices(directory).getFirst().id()).isEqualTo(original.id());
        assertThat(runtime.devices(directory).getFirst().name()).isEqualTo("Workshop Printer");
        runtime.save(directory, duplicate);
        assertThat(runtime.devices(directory).size()).isEqualTo(2);
    }

    @Test
    void runtimeIsDiscoverable() {
        assertThat(PrinterRuntime.load()).isInstanceOf(DefaultPrinterRuntime.class);
    }
}
