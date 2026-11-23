package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;

class McpServerHostTest {
    private static Thread newSweeper(java.util.Set<Thread> existing) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !existing.contains(thread)
                        && thread.getName().startsWith("streamable-http-server-transport-session-sweeper-"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void stoppingOrRestartingListenerTerminatesItsSessionSweeper() throws Exception {
        try (var document = new DocumentController(
                Runnable::run, doc -> new DocumentController.Build(java.util.List.of(), java.util.List.of(), true))) {
            var settings = new McpSettings(false, 0, 4, 30, 100);
            var existing = Thread.getAllStackTraces().keySet();
            Thread second;
            try (var server = new McpServerHost(new McpAutomation(document, () -> false, Runnable::run))) {
                server.start(settings);
                var first = newSweeper(existing);
                server.start(settings);
                assertThat(first.join(java.time.Duration.ofSeconds(3))).isTrue();
                second = newSweeper(existing);
            }
            assertThat(second.join(java.time.Duration.ofSeconds(3))).isTrue();
        }
    }

    @Test
    void listenerRequiresTokenAndRejectsForeignOrigin() throws Exception {
        try (var document = new DocumentController(
                        Runnable::run,
                        doc -> new DocumentController.Build(java.util.List.of(), java.util.List.of(), true));
                var server = new McpServerHost(new McpAutomation(document, () -> false, Runnable::run));
                var client = HttpClient.newHttpClient()) {
            server.start(new McpSettings(false, 0, 4, 30, 100));
            var endpoint = URI.create(server.endpoint());
            assertThat(client.send(HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(401);
            assertThat(client.send(
                                    HttpRequest.newBuilder(endpoint)
                                            .header("Origin", "https://example.org")
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(403);
            var request = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + server.credential())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                        """))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body().contains("voxelbrew")).isTrue();
            String session = response.headers().firstValue("Mcp-Session-Id").orElseThrow();
            var inspect = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + server.credential())
                    .header("Mcp-Session-Id", session)
                    .header("MCP-Protocol-Version", "2025-11-25")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"document_inspect","arguments":{}}}
                        """))
                    .build();
            var inspected = client.send(inspect, HttpResponse.BodyHandlers.ofString());
            assertThat(inspected.statusCode()).isEqualTo(200);
            assertThat(inspected.body().contains("documentId"))
                    .as(inspected.body())
                    .isTrue();
        }
    }
}
