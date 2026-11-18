package dev.voxelbrew.printer.bambulab;

import dev.voxelbrew.api.model.printing.PrintArtifact;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.printer.internal.JobRejectedException;
import dev.voxelbrew.printer.internal.TransportFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

/** Stateless integration; every operation closes its own connection. */
@Slf4j
public final class BambuLabDriver implements Driver {
    private final TransportFactory transports;

    public BambuLabDriver() {
        transports = config -> new BambuSession(connection(config));
    }

    BambuLabDriver(@lombok.NonNull TransportFactory transports) {
        this.transports = transports;
    }

    @Override
    public String id() {
        return "bambulab";
    }

    @Override
    public String name() {
        return "BambuLab Configuration";
    }

    @Override
    public DriverGui gui() {
        return new BambuLabDriverGui();
    }

    private Connection connection(DriverConfiguration config) {
        var form = gui();
        var values = config.toInput();
        var errors = form.validate(values);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid printer configuration: " + errors);
        return new Connection(
                config.requireUriProperty(BambuLabDriverGui.BAMBU_ADDRESS_ID).getHost(),
                config.requireStringProperty(BambuLabDriverGui.BAMBU_SERIAL_ID),
                new String(config.requirePasswordProperty(BambuLabDriverGui.BAMBU_SECRET_ID)));
    }

    @Override
    public DriverStatus readPrinterStatus(DriverConfiguration config) {
        try (var session = transports.open(config)) {
            return session.status();
        } catch (IOException | RuntimeException failure) {
            log.warn("Cannot read BambuLab printer status", failure);
            return new DriverStatus(
                    DriverStatus.State.ERROR,
                    "",
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    message("Cannot read BambuLab printer status", failure));
        }
    }

    @Override
    public PrintResult print(DriverConfiguration config, PrintArtifact artifact) {
        Path temporary = null;
        boolean submissionAttempted = false;
        boolean submitted = false;
        try {
            connection(config);
            if (!artifact.format().equals("gcode.3mf"))
                return new PrintResult.Failure(Set.of("BambuLab requires a sliced gcode.3mf package"));
            if (!"orca".equals(artifact.metadata().get("backend"))
                    || !"0".equals(artifact.metadata().get("tool")))
                return new PrintResult.Failure(Set.of("Bambu requires an OrcaSlicer single-material tool 0 package"));
            temporary = Files.createTempFile("voxelbrew-job-", ".gcode.3mf");
            Files.write(temporary, artifact.bytes());
            try (var session = transports.open(config)) {
                if (!session.status().canStart())
                    return new PrintResult.Failure(Set.of("Printer is not idle; inspect its current job"));
                var uploaded = session.upload(temporary, progress -> {});
                submissionAttempted = true;
                session.start(uploaded);
                submitted = true;
                return PrintResult.Success.SUCCESS;
            }
        } catch (JobRejectedException rejection) {
            return failed("BambuLab rejected print operation", rejection, false);
        } catch (IOException | RuntimeException failure) {
            if (submitted) {
                log.warn("BambuLab accepted the submission but transport cleanup failed", failure);
                return PrintResult.Success.SUCCESS;
            }
            return failed(
                    submissionAttempted
                            ? "Print submission is uncertain. Check the printer before manually submitting again"
                            : "BambuLab print operation failed",
                    failure,
                    submissionAttempted);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | RuntimeException cleanupFailure) {
                    log.warn("Cannot remove temporary BambuLab print artifact", cleanupFailure);
                }
            }
        }
    }

    private static PrintResult.Failure failed(String context, Exception failure, boolean submissionUncertain) {
        log.warn(context, failure);
        return new PrintResult.Failure(Set.of(message(context, failure)), submissionUncertain);
    }

    private static String message(String context, Exception failure) {
        String detail = failure.getLocalizedMessage();
        return detail == null || detail.isBlank() ? context : context + ": " + detail;
    }
}
