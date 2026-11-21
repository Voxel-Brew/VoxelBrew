package dev.voxelbrew.printer.bambulab;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.vui.*;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.util.services.Services;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.util.*;

public final class BambuLabDriverGui implements DriverGui {
    public static final String BAMBU_ADDRESS_ID = "bambu.address";
    public static final String BAMBU_SERIAL_ID = "bambu.serial";
    public static final String BAMBU_SECRET_ID = "bambu.secret";

    private final TranslationService translations;

    public BambuLabDriverGui() {
        this(Services.load(TranslationService.class));
    }

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The injected translation service is intentionally shared.")
    public BambuLabDriverGui(@lombok.NonNull TranslationService translations) {
        this.translations = translations;
    }

    @Override
    public Layout layout() {
        return new VerticalLayout(
                new TextField(
                        BAMBU_ADDRESS_ID,
                        translations.translate("printer.bambulab.address"),
                        "mqtts://printer.local:8883"),
                new TextField(BAMBU_SERIAL_ID, translations.translate("printer.bambulab.serial"), ""),
                new PasswordField(BAMBU_SECRET_ID, translations.translate("printer.bambulab.access"), ""));
    }

    @Override
    public Map<String, String> validate(Map<String, String> values) {
        var errors = new LinkedHashMap<String, String>();
        String address = values.getOrDefault(BAMBU_ADDRESS_ID, "");
        try {
            var uri = URI.create(address);
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getQuery() != null
                    || !Objects.equals("mqtts", uri.getScheme())
                    || uri.getPort() == 0
                    || uri.getPort() > 65535
                    || (uri.getPort() != -1 && uri.getPort() != 8883)
                    || (!uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            errors.put(BAMBU_ADDRESS_ID, translations.translate("printer.bambulab.invalidAddress"));
        }
        if (values.getOrDefault(BAMBU_SERIAL_ID, "").isBlank()) {
            errors.put(BAMBU_SERIAL_ID, translations.translate("printer.bambulab.missingSerial"));
        }
        if (values.getOrDefault(BAMBU_SECRET_ID, "").isBlank()) {
            errors.put(BAMBU_SECRET_ID, translations.translate("printer.bambulab.missingCode"));
        }
        return Map.copyOf(errors);
    }

    @Override
    public DriverConfiguration buildConfiguration(Map<String, String> values) {
        var errors = validate(values);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(translations.translate("printer.invalid", errors));
        }
        return new DriverConfiguration(Map.of(
                BAMBU_ADDRESS_ID,
                URI.create(values.get(BAMBU_ADDRESS_ID)),
                BAMBU_SERIAL_ID,
                values.get(BAMBU_SERIAL_ID),
                BAMBU_SECRET_ID,
                values.getOrDefault(BAMBU_SECRET_ID, "").toCharArray()));
    }
}
