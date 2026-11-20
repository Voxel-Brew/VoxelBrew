package dev.voxelbrew.printer.moonraker;

import com.google.gson.*;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.printer.internal.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.DoubleConsumer;

final class MoonrakerSession implements DeviceTransport {
    private final Connection device;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;

    static OptionalDouble number(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return OptionalDouble.empty();
        double value = object.get(key).getAsDouble();
        return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    private static HttpRequest.BodyPublisher progress(HttpRequest.BodyPublisher source, DoubleConsumer progress) {
        return new HttpRequest.BodyPublisher() {
            @Override
            public long contentLength() {
                return source.contentLength();
            }

            @Override
            public void subscribe(java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer> subscriber) {
                source.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                    private long sent;

                    @Override
                    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                        subscriber.onSubscribe(subscription);
                    }

                    @Override
                    public void onNext(java.nio.ByteBuffer buffer) {
                        sent += buffer.remaining();
                        subscriber.onNext(buffer);
                        if (contentLength() > 0) progress.accept(Math.min(1, (double) sent / contentLength()));
                    }

                    @Override
                    public void onError(Throwable error) {
                        subscriber.onError(error);
                    }

                    @Override
                    public void onComplete() {
                        subscriber.onComplete();
                    }
                });
            }
        };
    }

    MoonrakerSession(Connection device) {
        this.device = device;
        base = device.address().replaceAll("/+$", "");
    }

    private JsonObject request(String endpoint, HttpRequest.BodyPublisher body) throws IOException {
        var builder = HttpRequest.newBuilder(URI.create(base + endpoint)).timeout(Duration.ofSeconds(60));
        if (!device.secret().isEmpty()) builder.header("X-Api-Key", device.secret());
        if (body == null) builder.GET();
        else {
            builder.POST(body);
        }
        return send(builder.build());
    }

    private JsonObject send(HttpRequest request) throws IOException {
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 == 4) {
                throw new JobRejectedException("Moonraker rejected the request (HTTP " + response.statusCode() + ")");
            }
            if (response.statusCode() / 100 != 2) throw new IOException("Moonraker HTTP " + response.statusCode());
            var json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("error")) throw new JobRejectedException("Moonraker rejected the request");
            return json;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Device request interrupted", e);
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Invalid Moonraker response", e);
        }
    }

    @Override
    public DriverStatus status() throws IOException {

        JsonObject root = request("/printer/objects/query?print_stats&virtual_sdcard&extruder&heater_bed", null)
                .getAsJsonObject("result")
                .getAsJsonObject("status");
        if (root == null || !root.has("print_stats")) throw new IOException("Moonraker did not report printer state");
        var print = root.getAsJsonObject("print_stats");
        String state = print.has("state") ? print.get("state").getAsString() : "unknown";
        var mapped = switch (state) {
            case "standby" -> DriverStatus.State.IDLE;
            case "printing" -> DriverStatus.State.PRINTING;
            case "paused" -> DriverStatus.State.PAUSED;
            case "complete", "cancelled" -> DriverStatus.State.COMPLETE;
            case "error" -> DriverStatus.State.ERROR;
            default -> DriverStatus.State.UNKNOWN;
        };
        return new DriverStatus(
                mapped,
                print.has("filename") ? print.get("filename").getAsString() : "",
                number(root.getAsJsonObject("virtual_sdcard"), "progress"),
                number(root.getAsJsonObject("extruder"), "temperature"),
                number(root.getAsJsonObject("heater_bed"), "temperature"),
                OptionalDouble.empty(),
                state);
    }

    @Override
    public Upload upload(Path artifact, DoubleConsumer progress) throws IOException {
        if (!artifact.toString().endsWith(".gcode")) throw new IOException("Moonraker requires text G-code");
        String name = "voxelbrew-" + UUID.randomUUID() + ".gcode";
        String boundary = "VoxelBrew" + UUID.randomUUID();
        String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"root\"\r\n\r\ngcodes\r\n--"
                + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
        var body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(prefix),
                progress(HttpRequest.BodyPublishers.ofFile(artifact), progress),
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
        var builder = HttpRequest.newBuilder(URI.create(base + "/server/files/upload"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body);
        if (!device.secret().isEmpty()) builder.header("X-Api-Key", device.secret());
        progress.accept(0);
        var result = send(builder.build()).getAsJsonObject("result");
        progress.accept(1);
        String remote = result.getAsJsonObject("item").get("path").getAsString();
        return new Upload(remote, ArtifactChecks.sha256(artifact));
    }

    @Override
    public void start(Upload upload) throws IOException {
        if (!status().canStart()) throw new IOException("Printer is not idle");
        request(
                "/printer/print/start?filename=" + URLEncoder.encode(upload.filename(), StandardCharsets.UTF_8),
                HttpRequest.BodyPublishers.noBody());
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
