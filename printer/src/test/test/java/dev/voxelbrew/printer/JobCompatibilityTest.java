package dev.voxelbrew.printer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.printing.PrintArtifact;
import dev.voxelbrew.api.printer.driver.Driver;
import dev.voxelbrew.printer.bambulab.*;
import dev.voxelbrew.printer.moonraker.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobCompatibilityTest {
    @Test
    void rejectsWrongArtifactFormatsBeforeOpeningNetwork() throws Exception {
        var moon = new MoonrakerDriverGui()
                .buildConfiguration(Map.of(MoonrakerDriverGui.MOONRAKER_URL_ID, "http://localhost:1"));
        assertThat(new MoonrakerDriver().print(moon, PrintArtifact.of("gcode.3mf", new byte[] {1}, Map.of())))
                .isInstanceOf(Driver.PrintResult.Failure.class);
        var bambu = new BambuLabDriverGui()
                .buildConfiguration(Map.of(
                        BambuLabDriverGui.BAMBU_ADDRESS_ID,
                        "mqtts://localhost",
                        BambuLabDriverGui.BAMBU_SERIAL_ID,
                        "123",
                        BambuLabDriverGui.BAMBU_SECRET_ID,
                        "private"));
        assertThat(new BambuLabDriver().print(bambu, PrintArtifact.of("gcode", new byte[] {1}, Map.of())))
                .isInstanceOf(Driver.PrintResult.Failure.class);
    }

    @Test
    void rejectsCredentialUrlsAndMalformedAddresses() {
        var gui = new MoonrakerDriverGui();
        for (var url : java.util.List.of(
                "http://user:secret@host",
                "ftp://host",
                "http://host:0",
                "http://host/?x=y",
                "http://host/#fragment")) {
            assertThat(gui.validate(Map.of(MoonrakerDriverGui.MOONRAKER_URL_ID, url)))
                    .containsKey(MoonrakerDriverGui.MOONRAKER_URL_ID);
        }
    }
}
