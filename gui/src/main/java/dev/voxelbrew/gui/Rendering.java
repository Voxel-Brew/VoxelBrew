package dev.voxelbrew.gui;

import dev.voxelbrew.util.config.UserConfiguration;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

/** Window rendering preference, deliberately outside the CAD document. */
@Slf4j
@lombok.RequiredArgsConstructor
final class Rendering {
    private final UserConfiguration configuration;

    enum Style {
        SHADED,
        TECHNICAL
    }

    Style load() {
        try {
            var style = Style.valueOf(configuration.get("rendering.style", "SHADED"));
            log.info("Selected CAD rendering style {}", style);
            return style;
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            log.warn("Cannot load CAD rendering preference; using shaded rendering", failure);
            return Style.SHADED;
        }
    }

    void save(Style style) {
        try {
            configuration.set("rendering.style", style.name());
            log.info("Saved CAD rendering style {}", style);
        } catch (IOException | SecurityException failure) {
            log.warn("Cannot save CAD rendering preference", failure);
        }
    }
}
