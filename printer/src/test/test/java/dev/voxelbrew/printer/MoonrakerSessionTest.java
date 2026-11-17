package dev.voxelbrew.printer;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.voxelbrew.printer.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoonrakerSessionTest {
    @TempDir
    Path directory;

    @Test
    void uploadDoesNotStartUntilExplicitRequestAndBusyPrinterRejectsStart() throws Exception {
        var calls = Collections.synchronizedList(new ArrayList<String>());
        var state = new java.util.concurrent.atomic.AtomicReference<>("standby");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/websocket")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            calls.add(exchange.getRequestURI().getPath());
            assertThat(exchange.getRequestHeaders().getFirst("X-Api-Key")).isEqualTo("key");
            String response;
            if (exchange.getRequestURI().getPath().endsWith("query"))
                response = "{\"result\":{\"status\":{\"print_stats\":{\"state\":\"" + state.get()
                        + "\"},\"virtual_sdcard\":{\"progress\":0.25}}}}";
            else if (exchange.getRequestURI().getPath().endsWith("upload")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(body.contains("G1 X10")).isTrue();
                assertThat(body.contains("name=\"print\"")).isFalse();
                response = "{\"result\":{\"item\":{\"path\":\"stored.gcode\"}}}";
            } else response = "{\"result\":\"ok\"}";
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) {
                output.write(data);
            }
        });
        server.start();
        try (var session = new MoonrakerSession(new Printer(
                "local",
                "Local",
                Printer.Kind.MOONRAKER,
                "generic-marlin-200-0.4",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "",
                "",
                "key"))) {
            Path file = directory.resolve("job.gcode");
            Files.writeString(file, "G1 X10\n");
            var receipt = session.upload(file, fraction -> {});
            assertThat(calls).isEqualTo(List.of("/server/files/upload"));
            session.start(receipt);
            assertThat(calls.contains("/printer/print/start")).isTrue();
            state.set("printing");
            assertThatThrownBy(() -> session.start(receipt)).isInstanceOf(java.io.IOException.class);
            session.command(PrinterSession.Command.PAUSE);
            assertThat(calls.getLast()).isEqualTo("/printer/print/pause");
            assertThat(session.status().progress().orElseThrow()).isEqualTo(0.25);
        } finally {
            server.stop(0);
        }
    }
}
