package dev.voxelbrew.printer.bambulab;

import dev.voxelbrew.api.model.vui.*;
import dev.voxelbrew.api.printer.driver.*;
import java.net.URI;
import java.util.*;

public final class BambuLabDriverGui implements DriverGui {
    public static final String BAMBU_ADDRESS_ID = "bambu.address";
    public static final String BAMBU_SERIAL_ID = "bambu.serial";
    public static final String BAMBU_SECRET_ID = "bambu.secret";

    @Override
    public Layout layout() {
        return new VerticalLayout(
                new TextField(BAMBU_ADDRESS_ID, "Bambu LAN address", "mqtts://printer.local:8883"),
                new TextField(BAMBU_SERIAL_ID, "Serial number", ""),
                new PasswordField(BAMBU_SECRET_ID, "LAN access code", ""));
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
                    || (!uri.getPath().isEmpty() && !uri.getPath().equals("/"))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            errors.put(BAMBU_ADDRESS_ID, "Enter a valid MQTTS address without credentials, query, or fragment");
        }
        if (values.getOrDefault(BAMBU_SERIAL_ID, "").isBlank())
            errors.put(BAMBU_SERIAL_ID, "Enter the printer serial number");
        if (values.getOrDefault(BAMBU_SECRET_ID, "").isBlank())
            errors.put(BAMBU_SECRET_ID, "Enter the LAN access code");
        return Map.copyOf(errors);
    }

    @Override
    public DriverConfiguration buildConfiguration(Map<String, String> values) {
        var errors = validate(values);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid configuration: " + errors);
        return new DriverConfiguration(Map.of(
                BAMBU_ADDRESS_ID,
                URI.create(values.get(BAMBU_ADDRESS_ID)),
                BAMBU_SERIAL_ID,
                values.get(BAMBU_SERIAL_ID),
                BAMBU_SECRET_ID,
                values.getOrDefault(BAMBU_SECRET_ID, "").toCharArray()));
    }
}
