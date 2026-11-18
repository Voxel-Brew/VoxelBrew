package dev.voxelbrew.printer.bambulab;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;
import pl.grzeslowski.jbambuapi.mqtt.*;

/** Owns JBambuAPI state for exactly one driver operation, with reconnect disabled. */
final class LibraryMqttPeer implements MqttPeer {
    private final PrinterClientConfig config;
    private PrinterClient client;

    LibraryMqttPeer(Connection device) {
        config = new PrinterClientConfig(
                java.net.URI.create("ssl://" + device.address() + ":8883"),
                "voxelbrew-" + UUID.randomUUID(),
                "bblp",
                device.serial(),
                device.secret().toCharArray(),
                10,
                20,
                false);
    }

    @Override
    public void open(Consumer<byte[]> reports) throws IOException {
        try {
            client = new PrinterClient(config);
            client.subscribe((topic, bytes) -> reports.accept(bytes));
            client.connect();
            client.getChannel().sendCommand(PrinterClient.Channel.PushingCommand.defaultPushingCommand());
        } catch (Exception failure) {
            try {
                close();
            } catch (RuntimeException cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw new IOException("Cannot connect to Bambu device; check host, serial and LAN access code", failure);
        }
    }

    @Override
    public boolean connected() {
        return client != null && client.isConnected();
    }

    @Override
    public void submit(JsonObject command) throws IOException {
        try {
            client.getChannel().sendCommand(new PrinterClient.Channel.RawStringCommand() {
                @Override
                public String topic() {
                    return "device/" + config.serial() + "/request";
                }

                @Override
                public String buildRawStringCommand(long sequence) {
                    var print = command.deepCopy();
                    print.addProperty("sequence_id", Long.toString(sequence));
                    var root = new JsonObject();
                    root.add("print", print);
                    return root.toString();
                }
            });
        } catch (RuntimeException failure) {
            throw new IOException("Bambu submission connection failed", failure);
        }
    }

    @Override
    public void close() {
        try {
            if (client != null) {
                client.close();
                client = null;
            }
        } finally {
            config.close();
        }
    }
}
