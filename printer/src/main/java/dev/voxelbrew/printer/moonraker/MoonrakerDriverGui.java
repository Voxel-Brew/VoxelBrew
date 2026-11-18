package dev.voxelbrew.printer.moonraker;

import dev.voxelbrew.api.model.vui.*;
import dev.voxelbrew.api.printer.driver.*;
import java.net.URI;
import java.util.*;

public final class MoonrakerDriverGui implements DriverGui {
    public static final String MOONRAKER_URL_ID = "moonraker.url";
    public static final String MOONRAKER_SERIAL_ID = "moonraker.serial";
    public static final String MOONRAKER_API_KEY_ID = "moonraker.apiKey";

    @Override
    public Layout layout() {
        return new VerticalLayout(
                new TextField(MOONRAKER_URL_ID, "Moonraker URL", "http://localhost:7125"),
                new TextField(MOONRAKER_SERIAL_ID, "Serial number (optional)", ""),
                new PasswordField(MOONRAKER_API_KEY_ID, "Moonraker API Key", ""));
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
                    || uri.getPort() > 65535) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            errors.put(MOONRAKER_URL_ID, "Enter a valid HTTP(S) address without credentials, query, or fragment");
        }
        return Map.copyOf(errors);
    }

    @Override
    public DriverConfiguration buildConfiguration(Map<String, String> values) {
        var errors = validate(values);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid configuration: " + errors);
        return new DriverConfiguration(Map.of(
                MOONRAKER_URL_ID, URI.create(values.get(MOONRAKER_URL_ID)),
                MOONRAKER_SERIAL_ID, values.getOrDefault(MOONRAKER_SERIAL_ID, ""),
                MOONRAKER_API_KEY_ID,
                        values.getOrDefault(MOONRAKER_API_KEY_ID, "").toCharArray()));
    }
}
