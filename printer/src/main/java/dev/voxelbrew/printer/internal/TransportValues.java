package dev.voxelbrew.printer.internal;

import com.google.gson.JsonObject;
import java.util.OptionalDouble;

@lombok.experimental.UtilityClass
public class TransportValues {

    public static OptionalDouble number(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return OptionalDouble.empty();
        double v = object.get(key).getAsDouble();
        return Double.isFinite(v) ? OptionalDouble.of(v) : OptionalDouble.empty();
    }
}
