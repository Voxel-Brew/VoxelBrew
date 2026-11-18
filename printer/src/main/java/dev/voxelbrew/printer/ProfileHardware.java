package dev.voxelbrew.printer;

import dev.voxelbrew.api.model.printing.HardwareSettings;
import dev.voxelbrew.api.printer.Profile;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

/** Converts printer definitions into the application's portable document hardware snapshot. */
@UtilityClass
public class ProfileHardware {
    public static HardwareSettings resolve(@NonNull Profile profile) {
        return new HardwareSettings(
                profile.printer(),
                profile.manufacturer(),
                profile.filamentDiameter(),
                profile.toolCount(),
                profile.genericGcode(),
                false);
    }
}
