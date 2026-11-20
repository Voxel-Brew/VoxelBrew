package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.printer.*;
import dev.voxelbrew.api.printer.driver.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PrinterCallsTest {
    private static final class Fixture implements Driver {
        final CountDownLatch monitoringEntered = new CountDownLatch(1);
        final CountDownLatch releaseMonitoring = new CountDownLatch(1);
        final CountDownLatch submissionEntered = new CountDownLatch(1);
        final AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger(), prints = new AtomicInteger();

        @Override
        public String id() {
            return "fixture";
        }

        @Override
        public String name() {
            return "Fixture";
        }

        @Override
        public DriverGui gui() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DriverStatus readPrinterStatus(DriverConfiguration configuration) throws Exception {
            reads.incrementAndGet();
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            monitoringEntered.countDown();
            try {
                if (!releaseMonitoring.await(5, TimeUnit.SECONDS)) throw new AssertionError("Monitoring timed out");
                return DriverStatus.unknown("");
            } finally {
                active.decrementAndGet();
            }
        }

        @Override
        public PrintResult print(DriverConfiguration configuration, PrintArtifact artifact) {
            prints.incrementAndGet();
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            submissionEntered.countDown();
            active.decrementAndGet();
            return PrintResult.Success.SUCCESS;
        }
    }

    private Printer printer(String id) {
        var hardware = PrintProfile.resolve(PrintSetup.empty()).printer();
        var profile = new Profile(
                hardware.id(),
                hardware.name(),
                "Generic",
                hardware.bed(),
                hardware.excludedZones(),
                hardware.usableHeight(),
                hardware.nozzleMm(),
                1.75,
                1,
                hardware.firmware(),
                hardware.outputFormat(),
                true,
                null);
        return new Printer(
                id,
                id,
                "fixture",
                new DriverConfiguration(Map.of("endpoint", URI.create("http://localhost:7125"))),
                profile);
    }

    private PrintArtifact artifact(Printer printer, Map<String, String> changes) {
        var metadata = new HashMap<>(Map.of(
                "target_kind",
                "printer",
                "target_id",
                printer.id(),
                "target_profile",
                printer.profile().id()));
        metadata.putAll(changes);
        return PrintArtifact.of("gcode", new byte[] {1}, metadata);
    }

    @Test
    void invalidTargetsAreRejectedBeforeCallingTheDriver() throws Exception {
        var printer = printer("target");
        var driver = new Fixture();
        for (var artifact : Arrays.asList(
                null,
                artifact(printer, Map.of("target_kind", "profile")),
                artifact(printer, Map.of("target_id", "other")),
                artifact(printer, Map.of("target_profile", "changed")),
                artifact(printer, Map.of("printer", "other")),
                artifact(printer, Map.of("nozzle_mm", "0.8")))) {
            assertThat(PrinterPane.submitArtifact(driver, printer, artifact))
                    .isInstanceOf(Driver.PrintResult.Failure.class);
        }
        assertThat(driver.prints.get()).isZero();
        assertThat(driver.reads.get()).isZero();
    }

    @Test
    void aliasesOfOneEndpointSerializeMonitoringAndSubmissionAcrossHostInstances() throws Exception {
        var driver = new Fixture();
        var firstPrinter = printer("first");
        var alias = printer("alias");
        var attempted = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var first = workers.submit(() -> PrinterPane.readStatus(driver, firstPrinter));
                assertThat(driver.monitoringEntered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = workers.submit(() -> {
                    attempted.countDown();
                    return PrinterPane.submitArtifact(driver, alias, artifact(alias, Map.of()));
                });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(driver.submissionEntered.await(200, TimeUnit.MILLISECONDS))
                        .as("A submission must wait for monitoring of the same physical endpoint")
                        .isFalse();
                driver.releaseMonitoring.countDown();
                first.get(5, TimeUnit.SECONDS);
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(Driver.PrintResult.Success.SUCCESS);
            } finally {
                driver.releaseMonitoring.countDown();
            }
        }
        assertThat(driver.reads.get()).isEqualTo(1);
        assertThat(driver.prints.get()).isEqualTo(1);
        assertThat(driver.maximum.get()).isEqualTo(1);
    }
}
