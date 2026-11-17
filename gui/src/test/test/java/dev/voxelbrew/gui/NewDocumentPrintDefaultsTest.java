package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.engine.persistence.DocumentCodec;
import org.junit.jupiter.api.Test;

class NewDocumentPrintDefaultsTest {
    @Test
    void newDesktopProjectsStartWithSupportsDisabledAndPersistTheChoice() throws Exception {
        var document = DocumentController.newDesktopDocument();
        assertThat(document.printSetup().supportsDisabled()).isTrue();
        assertThat(DocumentCodec.decode(DocumentCodec.encode(document))).isEqualTo(document);
        assertThat(document.printSetup().withSupportsDisabled(false).supportsDisabled())
                .isFalse();
    }
}
