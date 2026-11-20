package dev.voxelbrew.printer.moonraker;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.vui.*;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.util.services.Services;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.util.*;

public final class MoonrakerDriverGui implements DriverGui {
    public static final String MOONRAKER_URL_ID = "moonraker.url";
    public static final String MOONRAKER_SERIAL_ID = "moonraker.serial";
    public static final String MOONRAKER_API_KEY_ID = "moonraker.apiKey";

    private final TranslationService translations;

    public MoonrakerDriverGui() {
        this(Services.load(TranslationService.class));
    }

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The injected translation service is intentionally shared.")
    public MoonrakerDriverGui(@lombok.NonNull TranslationService translations) {
        this.translations = translations;
    }

    @Override
    public Layout layout() {
        return new VerticalLayout(
                new TextField(
                        MOONRAKER_URL_ID,
                        translations.translate("printer.moonraker.url").orElseThrow(),
                        "http://localhost:7125"),
                new TextField(
                        MOONRAKER_SERIAL_ID,
                        translations.translate("printer.moonraker.serial").orElseThrow(),
                        ""),
                new PasswordField(
                        MOONRAKER_API_KEY_ID,
                        translations.translate("printer.moonraker.key").orElseThrow(),
                        ""));
    }

    @Override
    public Map<String, String> validate(Map<String, String> values) {
        var errors = new LinkedHashMap<String, String>();
        String address = values.getOrDefault(MOONRAKER_URL_ID, "");
        try {
            var uri = URI.create(address);
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getQuery() != null
                    || !Set.of("http", "https").contains(uri.getScheme())
                    || uri.getPort() == 0
                    || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            errors.put(
                    MOONRAKER_URL_ID,
                    translations.translate("printer.moonraker.invalidAddress").orElseThrow());
        }
        return Map.copyOf(errors);
    }

    @Override
    public DriverConfiguration buildConfiguration(Map<String, String> values) {
        var errors = validate(values);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    translations.translate("printer.invalid", errors).orElseThrow());
        }
        return new DriverConfiguration(Map.of(
                MOONRAKER_URL_ID, URI.create(values.get(MOONRAKER_URL_ID)),
                MOONRAKER_SERIAL_ID, values.getOrDefault(MOONRAKER_SERIAL_ID, ""),
                MOONRAKER_API_KEY_ID,
                        values.getOrDefault(MOONRAKER_API_KEY_ID, "").toCharArray()));
    }
}
