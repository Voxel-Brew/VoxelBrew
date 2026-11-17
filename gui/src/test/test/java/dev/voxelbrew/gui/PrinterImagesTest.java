package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterImagesTest {
    @TempDir
    Path directory;

    @Test
    void decodesWebpWithUppercaseExtension() throws Exception {
        var path = directory.resolve("printer.WEBP");
        try (var source = getClass().getResourceAsStream("webp/small_1x1.webp")) {
            Files.copy(source, path);
        }
        var image = PrinterImages.load(path.toUri().toString());
        assertThat(image).isNotNull();
        assertThat(image.isError()).isFalse();
        assertThat(image.getWidth()).isEqualTo(1);
        assertThat(image.getHeight()).isEqualTo(1);
        assertThat(image.getPixelReader()).isNotNull();
    }

    @Test
    void preservesLosslessWebpTransparency() {
        var image = PrinterImages.load(
                getClass().getResource("webp/lossless.transparent.webp").toExternalForm());
        assertThat(image).isNotNull();
        boolean transparent = false;
        boolean visible = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getPixelReader().getArgb(x, y) >>> 24;
                transparent |= alpha == 0;
                visible |= alpha > 0;
            }
        }
        assertThat(transparent).isTrue();
        assertThat(visible).isTrue();
    }

    @Test
    void corruptAndMissingWebpAllowTheDefaultIllustration() throws Exception {
        var path = directory.resolve("broken.webp");
        Files.writeString(path, "not an image");
        assertThat(PrinterImages.load(path.toUri().toString())).isNull();
        assertThat(PrinterImages.load(directory.resolve("missing.webp").toUri().toString()))
                .isNull();
    }
}
