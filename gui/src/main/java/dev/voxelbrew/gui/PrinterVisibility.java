package dev.voxelbrew.gui;

import dev.voxelbrew.api.printer.PrinterProfileCatalog;
import dev.voxelbrew.api.userconfig.UserConfigService;
import java.util.*;

/** User preference only: hidden presets remain available to saved projects and exports. */
final class PrinterVisibility {
    private static final String KEY = "printers.hiddenDefaults";
    private final UserConfigService configuration;

    PrinterVisibility(UserConfigService configuration) {
        this.configuration = configuration;
    }

    Set<String> hidden() {
        var ids = new HashSet<>(
                Arrays.asList(configuration.readUserConfig(KEY).orElse("").split(",")));
        ids.remove("");
        return Set.copyOf(ids);
    }

    void saveVisible(List<PrinterProfileCatalog.Entry> entries, Set<String> visible) {
        var hidden = new TreeSet<>(hidden());
        for (var entry : entries) {
            if (!entry.bundled()) continue;
            String id = entry.definition().printer().id();
            if (visible.contains(id)) hidden.remove(id);
            else {
                hidden.add(id);
            }
        }
        configuration.saveUserConfig(KEY, String.join(",", hidden));
    }
}
