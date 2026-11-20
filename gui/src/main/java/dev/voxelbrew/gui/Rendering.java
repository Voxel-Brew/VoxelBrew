package dev.voxelbrew.gui;

import dev.voxelbrew.api.userconfig.UserConfigException;
import dev.voxelbrew.api.userconfig.UserConfigService;
import lombok.extern.slf4j.Slf4j;

/** Window rendering preference, deliberately outside the CAD document. */
@Slf4j
@lombok.RequiredArgsConstructor
final class Rendering {
    private final UserConfigService configuration;

    enum Style {
        SHADED,
        TECHNICAL
    }

    Style load() {
        try {
            var style = Style.valueOf(
                    configuration.readUserConfig("rendering.style").orElse("SHADED"));
            log.info("Selected CAD rendering style {}", style);
            return style;
        } catch (UserConfigException | IllegalArgumentException | SecurityException failure) {
            log.warn("Cannot load CAD rendering preference; using shaded rendering", failure);
            return Style.SHADED;
        }
    }

    void save(Style style) {
        try {
            configuration.saveUserConfig("rendering.style", style.name());
            log.info("Saved CAD rendering style {}", style);
        } catch (UserConfigException | SecurityException failure) {
            log.warn("Cannot save CAD rendering preference", failure);
        }
    }
}
