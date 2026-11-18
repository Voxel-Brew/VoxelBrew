package dev.voxelbrew.printer.bambulab;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.printing.PrintArtifact;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.printer.internal.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class BambuLabDriverTest {
    private DriverConfiguration config() {
        return new BambuLabDriverGui()
                .buildConfiguration(Map.of(
                        BambuLabDriverGui.BAMBU_ADDRESS_ID,
                        "mqtts://localhost:8883",
                        BambuLabDriverGui.BAMBU_SERIAL_ID,
                        "123",
                        BambuLabDriverGui.BAMBU_SECRET_ID,
                        "private"));
    }

    private PrintArtifact artifact() {
        return PrintArtifact.of("gcode.3mf", new byte[] {1}, Map.of("backend", "orca", "tool", "0"));
    }

    @Test
    void submitsOnceAndClosesOperationResources() throws Exception {
        var transport = new Fixture();
        var driver = new BambuLabDriver(c -> transport);
        assertThat(driver.print(config(), artifact())).isEqualTo(Driver.PrintResult.Success.SUCCESS);
        assertThat(transport.calls).containsExactly("status", "upload", "start", "close");
        assertThat(transport.file).doesNotExist();
    }

    @Test
    void rejectsBusyJobsAndClosesResources() throws Exception {
        var transport = new Fixture();
        transport.busy = true;
        assertThat(new BambuLabDriver(c -> transport).print(config(), artifact()))
                .isInstanceOf(Driver.PrintResult.Failure.class);
        assertThat(transport.calls).containsExactly("status", "close");
    }

    @Test
    void uncertainStartIsNeverRetriedAndCleansArtifact() {
        var transport = new Fixture();
        transport.failStart = true;
        assertThat(new BambuLabDriver(configuration -> transport).print(config(), artifact()))
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
        var status = new BambuLabDriver(configuration -> transport).readPrinterStatus(config());
        assertThat(status.state()).isEqualTo(DriverStatus.State.ERROR);
        assertThat(status.detail()).contains("Cannot read", "Monitoring unavailable");
        assertThat(status.canStart()).isFalse();
        assertThat(transport.calls).containsExactly("status", "close");
    }

    @Test
    void definiteRejectionIsReportedWithoutRetry() throws Exception {
        var transport = new Fixture();
        transport.reject = true;
        assertThat(new BambuLabDriver(c -> transport).print(config(), artifact()))
                .isInstanceOf(Driver.PrintResult.Failure.class);
        assertThat(transport.calls).containsExactly("status", "upload", "start", "close");
        assertThat(transport.file).doesNotExist();
    }

    @Test
    void failedTransferReturnsFailureAndClosesResourcesAndCleansArtifact() {
        var transport = new Fixture();
        transport.failUpload = true;
        assertThat(new BambuLabDriver(configuration -> transport).print(config(), artifact()))
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
        var driver = new BambuLabDriver(configuration -> {
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
        assertThat(new BambuLabDriver(configuration -> transport).print(config(), artifact()))
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
        var driver = new BambuLabDriver(configuration -> {
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
        var driver = new BambuLabDriver(configuration -> {
            throw new IOException();
        });
        assertThat(driver.readPrinterStatus(config()).detail()).contains("Cannot read");
        assertThat(driver.print(config(), artifact()))
                .isInstanceOfSatisfying(
                        Driver.PrintResult.Failure.class,
                        failure -> assertThat(failure.messages()).containsExactly("BambuLab print operation failed"));
    }

    @Test
    void cleanupFailureAfterAcceptedSubmissionDoesNotTurnItIntoAFailedJob() {
        var transport = new Fixture();
        transport.runtimeCloseFailure = new IllegalStateException("Cleanup failed");
        assertThat(new BambuLabDriver(configuration -> transport).print(config(), artifact()))
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
