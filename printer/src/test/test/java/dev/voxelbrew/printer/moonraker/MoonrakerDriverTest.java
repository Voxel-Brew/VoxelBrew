package dev.voxelbrew.printer.moonraker;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.printing.PrintArtifact;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.printer.internal.*;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.*;
import org.junit.jupiter.api.Test;

class MoonrakerDriverTest {
    private DriverConfiguration config() {
        return new MoonrakerDriverGui()
                .buildConfiguration(Map.of(MoonrakerDriverGui.MOONRAKER_URL_ID, "http://localhost:7125"));
    }

    private PrintArtifact artifact() {
        return PrintArtifact.of("gcode", new byte[] {1}, Map.of());
    }

    @Test
    void submitsOnceAndClosesOperationResources() throws Exception {
        var transport = new Fixture();
        var driver = new MoonrakerDriver(c -> transport);
        assertThat(driver.print(config(), artifact())).isEqualTo(Driver.PrintResult.Success.SUCCESS);
        assertThat(transport.calls).containsExactly("status", "upload", "start", "close");
        assertThat(transport.file).doesNotExist();
    }

    @Test
    void rejectsBusyJobsAndClosesResources() throws Exception {
        var transport = new Fixture();
        transport.busy = true;
        assertThat(new MoonrakerDriver(c -> transport).print(config(), artifact()))
                .isInstanceOf(Driver.PrintResult.Failure.class);
        assertThat(transport.calls).containsExactly("status", "close");
    }

    @Test
    void uncertainStartIsNeverRetriedAndCleansArtifact() {
        var transport = new Fixture();
        transport.failStart = true;
        assertThat(new MoonrakerDriver(configuration -> transport).print(config(), artifact()))
                .isInstanceOfSatisfying(Driver.PrintResult.Failure.class, failure -> {
                    assertThat(failure.submissionUncertain()).isTrue();
                    assertThat(failure.messages())
                            .anySatisfy(message -> assertThat(message).contains("uncertain", "Disconnected"));
                });
        assertThat(transport.calls).containsExactly("status", "upload", "start", "close");
        assertThat(transport.file).doesNotExist();
    }

    @Test
    void monitoringFailureReturnsErrorAndClosesResources() {
        var transport = new Fixture();
        transport.failStatus = true;
        var status = new MoonrakerDriver(configuration -> transport).readPrinterStatus(config());
        assertThat(status.state()).isEqualTo(DriverStatus.State.ERROR);
        assertThat(status.detail()).contains("Cannot read", "Monitoring unavailable");
        assertThat(status.canStart()).isFalse();
        assertThat(transport.calls).containsExactly("status", "close");
    }

    @Test
    void unreachablePrinterStatusFailuresLogAtDebugLevel() {
        var logger = Logger.getLogger(MoonrakerDriver.class.getName());
        var previousLevel = logger.getLevel();
        var records = new ArrayList<LogRecord>();
        var capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.setLevel(Level.FINE);
        logger.addHandler(capture);
        try {
            for (var failure : List.of(
                    new ConnectException(),
                    new NoRouteToHostException(),
                    new HttpConnectTimeoutException("Timed out"))) {
                var driver = new MoonrakerDriver(configuration -> {
                    throw failure;
                });
                var status = driver.readPrinterStatus(config());
                assertThat(status.state()).isEqualTo(DriverStatus.State.ERROR);
                assertThat(status.canStart()).isFalse();
            }
            var driver = new MoonrakerDriver(configuration -> {
                throw new IOException("Invalid Moonraker response");
            });
            assertThat(driver.readPrinterStatus(config()).state()).isEqualTo(DriverStatus.State.ERROR);
            assertThat(records)
                    .extracting(LogRecord::getLevel)
                    .containsExactly(Level.FINE, Level.FINE, Level.FINE, Level.WARNING);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void definiteRejectionIsReportedWithoutRetry() throws Exception {
        var transport = new Fixture();
        transport.reject = true;
        assertThat(new MoonrakerDriver(c -> transport).print(config(), artifact()))
                .isInstanceOf(Driver.PrintResult.Failure.class);
        assertThat(transport.calls).containsExactly("status", "upload", "start", "close");
        assertThat(transport.file).doesNotExist();
    }

    @Test
    void failedTransferReturnsFailureAndClosesResourcesAndCleansArtifact() {
        var transport = new Fixture();
        transport.failUpload = true;
        assertThat(new MoonrakerDriver(configuration -> transport).print(config(), artifact()))
                .isInstanceOfSatisfying(Driver.PrintResult.Failure.class, failure -> {
                    assertThat(failure.submissionUncertain()).isFalse();
                    assertThat(failure.messages())
                            .anySatisfy(message -> assertThat(message).contains("Transfer failed"));
                });
        assertThat(transport.calls).containsExactly("status", "upload", "close");
        assertThat(transport.file).doesNotExist();
    }

    @Test
    void monitoringAndTransferFailuresUseLocalizedMessages() {
        var cause = new IllegalStateException("Technical details") {
            private static final long serialVersionUID = 1L;

            @Override
            public String getLocalizedMessage() {
                return "Localized transport failure";
            }
        };
        var driver = new MoonrakerDriver(configuration -> {
            throw cause;
        });
        var status = driver.readPrinterStatus(config());
        assertThat(status.state()).isEqualTo(DriverStatus.State.ERROR);
        assertThat(status.detail()).contains("Localized transport failure").doesNotContain("Technical details");
        assertThat(driver.print(config(), artifact()))
                .isInstanceOfSatisfying(Driver.PrintResult.Failure.class, failure -> {
                    assertThat(failure.submissionUncertain()).isFalse();
                    assertThat(failure.messages())
                            .anySatisfy(message -> assertThat(message)
                                    .contains("Localized transport failure")
                                    .doesNotContain("Technical details"));
                });
    }

    @Test
    void uncertainRuntimeSubmissionReturnsFailureAndDoesNotRetry() {
        var transport = new Fixture();
        transport.runtimeStartFailure = new IllegalStateException("Connection lost during submission");
        assertThat(new MoonrakerDriver(configuration -> transport).print(config(), artifact()))
                .isInstanceOfSatisfying(Driver.PrintResult.Failure.class, failure -> {
                    assertThat(failure.submissionUncertain()).isTrue();
                    assertThat(failure.messages())
                            .anySatisfy(message ->
                                    assertThat(message).contains("uncertain", "Connection lost during submission"));
                });
        assertThat(transport.calls).containsExactly("status", "upload", "start", "close");
        assertThat(transport.file).doesNotExist();
    }

    @Test
    void invalidConfigurationReturnsFailureWithoutOpeningTransport() {
        var driver = new MoonrakerDriver(configuration -> {
            throw new AssertionError("Must validate first");
        });
        assertThat(driver.print(new DriverConfiguration(Map.of()), artifact()))
                .isInstanceOfSatisfying(Driver.PrintResult.Failure.class, failure -> {
                    assertThat(failure.submissionUncertain()).isFalse();
                    assertThat(failure.messages())
                            .anySatisfy(message -> assertThat(message).contains("Invalid printer configuration"));
                });
    }

    @Test
    void missingExceptionMessageHasMeaningfulFallback() {
        var driver = new MoonrakerDriver(configuration -> {
            throw new IOException();
        });
        assertThat(driver.readPrinterStatus(config()).detail()).contains("Cannot read");
        assertThat(driver.print(config(), artifact()))
                .isInstanceOfSatisfying(
                        Driver.PrintResult.Failure.class,
                        failure -> assertThat(failure.messages()).containsExactly("Moonraker print operation failed"));
    }

    @Test
    void cleanupFailureAfterAcceptedSubmissionDoesNotTurnItIntoAFailedJob() {
        var transport = new Fixture();
        transport.runtimeCloseFailure = new IllegalStateException("Cleanup failed");
        assertThat(new MoonrakerDriver(configuration -> transport).print(config(), artifact()))
                .isEqualTo(Driver.PrintResult.Success.SUCCESS);
        assertThat(transport.calls).containsExactly("status", "upload", "start", "close");
        assertThat(transport.file).doesNotExist();
    }

    private static final class Fixture implements DeviceTransport {
        final List<String> calls = new ArrayList<>();
        boolean busy, failStart, failStatus, reject, failUpload;
        Path file;
        final IOException startFailure = new IOException("Disconnected");
        RuntimeException runtimeStartFailure;
        RuntimeException runtimeCloseFailure;

        @Override
        public DriverStatus status() throws IOException {
            calls.add("status");
            if (failStatus) throw new IOException("Monitoring unavailable");
            return new DriverStatus(
                    busy ? DriverStatus.State.PRINTING : DriverStatus.State.IDLE,
                    "",
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    "");
        }

        @Override
        public Upload upload(Path artifact, java.util.function.DoubleConsumer progress) throws IOException {
            calls.add("upload");
            file = artifact;
            if (failUpload) throw new IOException("Transfer failed");
            return new Upload("job", "hash");
        }

        @Override
        public void start(Upload upload) throws IOException {
            calls.add("start");
            if (reject) throw new JobRejectedException("Rejected by printer");
            if (runtimeStartFailure != null) throw runtimeStartFailure;
            if (failStart) throw startFailure;
        }

        @Override
        public void close() {
            calls.add("close");
            if (runtimeCloseFailure != null) throw runtimeCloseFailure;
        }
    }
}
