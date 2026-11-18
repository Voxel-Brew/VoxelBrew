package dev.voxelbrew.printer.bambulab;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.function.Consumer;

/** Vendor protocol boundary, allowing deterministic packet/connection fixtures. */
interface MqttPeer extends AutoCloseable {
    void open(Consumer<byte[]> reports) throws IOException;

    boolean connected();

    void submit(JsonObject command) throws IOException;

    @Override
    void close();
}
