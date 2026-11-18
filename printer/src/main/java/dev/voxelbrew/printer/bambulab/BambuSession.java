package dev.voxelbrew.printer.bambulab;

import com.google.gson.*;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.printer.internal.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.DoubleConsumer;
import java.util.zip.ZipFile;
import org.apache.commons.net.ftp.*;

/** LAN device commands use JBambuAPI; file transfer is a separate FTPS operation. */
final class BambuSession implements DeviceTransport {
    private final Connection device;
    private final MqttPeer peer;
    private final java.util.function.Supplier<FTPSClient> transfers;
    private final JsonObject report = new JsonObject();
    private volatile long lastReport;
    private volatile String commandFailure = "";

    BambuSession(Connection device) throws IOException {
        this(device, new LibraryMqttPeer(device), () -> new FTPSClient(true));
    }

    BambuSession(Connection device, MqttPeer peer, java.util.function.Supplier<FTPSClient> transfers)
            throws IOException {
        this.device = device;
        this.peer = peer;
        this.transfers = transfers;
        try {
            peer.open(bytes -> {
                try {
                    var message = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    if (!message.has("print")) return;
                    synchronized (report) {
                        var delta = message.getAsJsonObject("print");
                        delta.entrySet().forEach(e -> report.add(e.getKey(), e.getValue()));
                        if (delta.has("result")
                                && "FAIL".equalsIgnoreCase(delta.get("result").getAsString()))
                            commandFailure = "Printer rejected command; check LAN / Developer Mode and device display";
                        lastReport = System.nanoTime();
                    }
                } catch (JsonParseException | IllegalStateException ignored) {
                    /* Ignore non-status packets. */
                }
            });
        } catch (IOException | RuntimeException failure) {
            try {
                peer.close();
            } catch (RuntimeException cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public DriverStatus status() throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (lastReport == 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Monitoring interrupted", failure);
            }
        }
        if (!peer.connected()) throw new IOException("Bambu device disconnected");
        synchronized (report) {
            if (lastReport == 0
                    || System.nanoTime() - lastReport > Duration.ofSeconds(30).toNanos())
                return DriverStatus.unknown("Waiting for current printer status");
            String state = string("gcode_state");
            var mapped = switch (state) {
                case "IDLE" -> DriverStatus.State.IDLE;
                case "RUNNING", "PREPARE" -> DriverStatus.State.PRINTING;
                case "PAUSE" -> DriverStatus.State.PAUSED;
                case "FINISH" -> DriverStatus.State.COMPLETE;
                case "FAILED" -> DriverStatus.State.ERROR;
                default -> DriverStatus.State.UNKNOWN;
            };
            var percent = dev.voxelbrew.printer.internal.TransportValues.number(report, "mc_percent");
            var minutes = dev.voxelbrew.printer.internal.TransportValues.number(report, "mc_remaining_time");
            return new DriverStatus(
                    mapped,
                    string("gcode_file"),
                    percent.isPresent() ? OptionalDouble.of(percent.getAsDouble() / 100) : percent,
                    dev.voxelbrew.printer.internal.TransportValues.number(report, "nozzle_temper"),
                    dev.voxelbrew.printer.internal.TransportValues.number(report, "bed_temper"),
                    minutes.isPresent() ? OptionalDouble.of(minutes.getAsDouble() * 60) : minutes,
                    commandFailure.isEmpty() ? state : commandFailure);
        }
    }

    private String string(String key) {
        return report.has(key) && !report.get(key).isJsonNull()
                ? report.get(key).getAsString()
                : "";
    }

    @Override
    public Upload upload(Path artifact, DoubleConsumer progress) throws IOException {
        if (!artifact.toString().endsWith(".gcode.3mf"))
            throw new IOException("Bambu requires a compatible sliced .gcode.3mf package");
        try (var zip = new ZipFile(artifact.toFile())) {
            if (zip.getEntry("Metadata/plate_1.gcode") == null)
                throw new IOException("Package does not contain plate 1 G-code");
        }
        var ftp = transfers.get();
        long size = Files.size(artifact);
        ftp.setCopyStreamListener(new org.apache.commons.net.io.CopyStreamAdapter() {
            @Override
            public void bytesTransferred(long total, int bytes, long streamSize) {
                progress.accept(size == 0 ? 0 : Math.min(1, (double) total / size));
            }
        });
        ftp.setConnectTimeout(10000);
        ftp.setDefaultTimeout(30000);
        ftp.setDataTimeout(Duration.ofMinutes(2));
        String name = "voxelbrew-" + UUID.randomUUID() + ".gcode.3mf";
        try {
            ftp.connect(device.address(), 990);
            if (!ftp.login("bblp", device.secret())) throw new IOException("Bambu FTPS authentication failed");
            ftp.execPBSZ(0);
            ftp.execPROT("P");
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            progress.accept(0);
            try (var input = Files.newInputStream(artifact)) {
                if (!ftp.storeFile(name, input)) throw new IOException("Bambu upload failed");
            }
            progress.accept(1);
            return new Upload(name, ArtifactChecks.sha256(artifact));
        } finally {
            if (ftp.isConnected()) ftp.disconnect();
        }
    }

    @Override
    public void start(Upload upload) throws IOException {
        if (!status().canStart()) throw new IOException("Printer is not idle");
        commandFailure = "";
        var print = new JsonObject();
        print.addProperty("command", "project_file");
        print.addProperty("param", "Metadata/plate_1.gcode");
        print.addProperty("url", "ftp:///" + upload.filename());
        print.addProperty("subtask_name", upload.filename());
        print.addProperty("use_ams", false);
        print.addProperty("bed_leveling", true);
        print.addProperty("flow_cali", false);
        print.addProperty("vibration_cali", true);
        print.addProperty("layer_inspect", false);
        print.addProperty("timelapse", false);
        print.addProperty("profile_id", "0");
        print.addProperty("project_id", "0");
        print.addProperty("task_id", "0");
        print.addProperty("subtask_id", "0");
        peer.submit(print);
        if (!commandFailure.isEmpty()) throw new JobRejectedException(commandFailure);
    }

    @Override
    public void close() {
        peer.close();
    }
}
