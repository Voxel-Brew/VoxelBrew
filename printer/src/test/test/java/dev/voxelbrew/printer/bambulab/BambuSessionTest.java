package dev.voxelbrew.printer.bambulab;

import static org.assertj.core.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.voxelbrew.printer.internal.*;
import java.io.*;
import java.nio.file.*;
import java.util.function.Consumer;
import java.util.zip.*;
import org.apache.commons.net.ftp.FTPSClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BambuSessionTest {
    @TempDir
    Path directory;

    private static final class Peer implements MqttPeer {
        final IOException openFailure = new IOException("Connection rejected");
        Consumer<byte[]> reports;
        boolean connected = true, closed, reject, fail, failOpen, silent;
        RuntimeException closeFailure;
        int submissions;
        JsonObject command;

        @Override
        public void open(Consumer<byte[]> reports) throws IOException {
            if (failOpen) throw openFailure;
            this.reports = reports;
            if (silent) return;
            reports.accept("{\"print\":{\"gcode_state\":\"IDLE\",\"mc_percent\":25}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public boolean connected() {
            return connected;
        }

        @Override
        public void submit(JsonObject command) throws IOException {
            this.command = command;
            submissions++;
            if (reject) {
                reports.accept("{\"print\":{\"result\":\"FAIL\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (fail) throw new IOException("Connection lost");
        }

        @Override
        public void close() {
            closed = true;
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static final class Transfer extends FTPSClient {
        boolean connected, disconnected, accept = true;
        byte[] received;

        @Override
        public void connect(String host, int port) {
            connected = true;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean login(String username, String password) {
            return true;
        }

        @Override
        public void execPBSZ(long size) {}

        @Override
        public void execPROT(String protection) {}

        @Override
        public void enterLocalPassiveMode() {}

        @Override
        public boolean setFileType(int type) {
            return true;
        }

        @Override
        public boolean storeFile(String remote, InputStream input) throws IOException {
            received = input.readAllBytes();
            return accept;
        }

        @Override
        public void disconnect() {
            connected = false;
            disconnected = true;
        }
    }

    private Path artifact() throws IOException {
        var file = directory.resolve("fixture.gcode.3mf");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("Metadata/plate_1.gcode"));
            zip.write("G1 X10".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    private BambuSession open(Peer peer, Transfer transfer) throws IOException {
        return new BambuSession(new Connection("fixture.local", "serial", "private"), peer, () -> transfer);
    }

    @Test
    void transfersPackageAndSubmitsExpectedCommandWithBoundedResources() throws Exception {
        var peer = new Peer();
        var transfer = new Transfer();
        try (var session = open(peer, transfer)) {
            assertThat(session.status().canStart()).isTrue();
            var receipt = session.upload(artifact(), fraction -> {});
            assertThat(transfer.received).isNotEmpty();
            assertThat(transfer.disconnected).isTrue();
            assertThat(transfer.getConnectTimeout()).isEqualTo(10000);
            assertThat(transfer.getDataTimeout()).isEqualTo(java.time.Duration.ofMinutes(2));
            session.start(receipt);
            assertThat(peer.submissions).isEqualTo(1);
            assertThat(peer.command.get("param").getAsString()).isEqualTo("Metadata/plate_1.gcode");
            assertThat(peer.command.get("url").getAsString()).endsWith(receipt.filename());
        }
        assertThat(peer.closed).isTrue();
    }

    @Test
    void rejectedTransferDisconnectsFtpsAndClosesMqtt() throws Exception {
        var peer = new Peer();
        var transfer = new Transfer();
        transfer.accept = false;
        try (var session = open(peer, transfer)) {
            assertThatThrownBy(() -> session.upload(artifact(), fraction -> {})).isInstanceOf(IOException.class);
            assertThat(transfer.disconnected).isTrue();
            assertThat(peer.submissions).isZero();
        }
        assertThat(peer.closed).isTrue();
    }

    @Test
    void definiteRejectionAndUncertainSubmissionNeverRetry() throws Exception {
        for (boolean rejected : new boolean[] {true, false}) {
            var peer = new Peer();
            peer.reject = rejected;
            peer.fail = !rejected;
            try (var session = open(peer, new Transfer())) {
                assertThatThrownBy(() -> session.start(new DeviceTransport.Upload("job.gcode.3mf", "hash")))
                        .isInstanceOf(rejected ? JobRejectedException.class : IOException.class);
                assertThat(peer.submissions).isEqualTo(1);
            }
            assertThat(peer.closed).isTrue();
        }
    }

    @Test
    void monitoringFailureAndConnectionFailureReleaseMqtt() throws Exception {
        var peer = new Peer();
        try (var session = open(peer, new Transfer())) {
            peer.connected = false;
            assertThatThrownBy(session::status).isInstanceOf(IOException.class);
        }
        assertThat(peer.closed).isTrue();
        var broken = new Peer();
        broken.failOpen = true;
        assertThatThrownBy(() -> open(broken, new Transfer())).isInstanceOf(IOException.class);
        assertThat(broken.closed).isTrue();
    }

    @Test
    void connectionFailureKeepsItsIdentityWhenCleanupAlsoFails() {
        var peer = new Peer();
        peer.failOpen = true;
        peer.closeFailure = new IllegalStateException("Cleanup failed");
        assertThatThrownBy(() -> open(peer, new Transfer()))
                .isSameAs(peer.openFailure)
                .hasSuppressedException(peer.closeFailure);
        assertThat(peer.closed).isTrue();
    }

    @Test
    void interruptedMonitoringKeepsItsCauseAndInterruptFlag() throws Exception {
        var peer = new Peer();
        peer.silent = true;
        try (var session = open(peer, new Transfer())) {
            try {
                Thread.currentThread().interrupt();
                assertThatThrownBy(session::status)
                        .isInstanceOf(IOException.class)
                        .hasCauseInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
        assertThat(peer.closed).isTrue();
    }
}
