package dev.voxelbrew.printer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.printer.bambulab.BambuLabDriverGui;
import dev.voxelbrew.printer.moonraker.MoonrakerDriverGui;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrinterProviderTest {
    @Test
    void discoversBothDriversWithoutConnecting() throws Exception {
        try (var registry =
                java.util.ServiceLoader.load(DriverRegistry.class).findFirst().orElseThrow()) {
            assertThat(registry.drivers()).extracting(Driver::id).containsExactly("bambulab", "moonraker");
            for (var driver : registry.drivers()) assertThat(driver.gui()).isNotNull();
        }
    }

    @Test
    void driversUseInjectedPolishCatalogForNamesAndForms(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        var translations =
                dev.voxelbrew.util.i18n.TranslationCatalogs.forLocale(java.util.Locale.forLanguageTag("pl"), directory);
        var bambu = new dev.voxelbrew.printer.bambulab.BambuLabDriver(translations);
        var moonraker = new dev.voxelbrew.printer.moonraker.MoonrakerDriver(translations);
        assertThat(bambu.name()).isEqualTo("Konfiguracja BambuLab");
        assertThat(moonraker.name()).isEqualTo("Konfiguracja Moonraker");
        assertThat(bambu.gui().validate(Map.of()))
                .containsEntry(BambuLabDriverGui.BAMBU_SERIAL_ID, "Wprowadź numer seryjny drukarki");
        assertThat(moonraker.gui().validate(Map.of()))
                .containsEntry(
                        MoonrakerDriverGui.MOONRAKER_URL_ID,
                        "Wprowadź poprawny adres HTTP(S) bez danych logowania, zapytania i fragmentu");
    }

    @Test
    void formsValidateAndRoundTripSecrets() {
        var gui = new dev.voxelbrew.printer.bambulab.BambuLabDriverGui();
        assertThat(gui.validate(Map.of()))
                .containsKeys(
                        BambuLabDriverGui.BAMBU_ADDRESS_ID,
                        BambuLabDriverGui.BAMBU_SERIAL_ID,
                        BambuLabDriverGui.BAMBU_SECRET_ID);
        var values = Map.of(
                BambuLabDriverGui.BAMBU_ADDRESS_ID,
                "mqtts://localhost:8883",
                BambuLabDriverGui.BAMBU_SERIAL_ID,
                "123",
                BambuLabDriverGui.BAMBU_SECRET_ID,
                "private");
        var config = gui.buildConfiguration(values);
        assertThat(config.toInput()).containsAllEntriesOf(values);
        assertThat(config.toString()).doesNotContain("private");
        var moon = new dev.voxelbrew.printer.moonraker.MoonrakerDriverGui();
        assertThat(moon.validate(Map.of(MoonrakerDriverGui.MOONRAKER_URL_ID, "http://user:private@host")))
                .containsKey(MoonrakerDriverGui.MOONRAKER_URL_ID);
        var moonValues = Map.of(
                MoonrakerDriverGui.MOONRAKER_URL_ID,
                "http://localhost:7125",
                MoonrakerDriverGui.MOONRAKER_SERIAL_ID,
                "KLIPPER-123",
                MoonrakerDriverGui.MOONRAKER_API_KEY_ID,
                "private");
        assertThat(moon.buildConfiguration(moonValues).toInput()).containsExactlyInAnyOrderEntriesOf(moonValues);
        var minimal = moon.buildConfiguration(Map.of(MoonrakerDriverGui.MOONRAKER_URL_ID, "http://localhost:7125"));
        assertThat(minimal.requirePasswordProperty(MoonrakerDriverGui.MOONRAKER_API_KEY_ID))
                .isEmpty();
        assertThat(minimal.requireStringProperty(MoonrakerDriverGui.MOONRAKER_SERIAL_ID))
                .isEmpty();
    }
}
