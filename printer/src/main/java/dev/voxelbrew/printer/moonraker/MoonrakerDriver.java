package dev.voxelbrew.printer.moonraker;

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
public final class MoonrakerDriver implements Driver {
    private final TransportFactory transports;

    public MoonrakerDriver() {
        transports = config -> new MoonrakerSession(connection(config));
    }

    MoonrakerDriver(@lombok.NonNull TransportFactory transports) {
        this.transports = transports;
    }

    @Override
    public String id() {
        return "moonraker";
    }

    @Override
    public String name() {
        return "Moonraker Configuration";
    }

    @Override
    public DriverGui gui() {
        return new MoonrakerDriverGui();
    }

    private Connection connection(DriverConfiguration config) {
        var form = gui();
        var values = config.toInput();
        var errors = form.validate(values);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid printer configuration: " + errors);
        return new Connection(
                config.requireUriProperty(MoonrakerDriverGui.MOONRAKER_URL_ID).toString(),
                config.getStringProperty(MoonrakerDriverGui.MOONRAKER_SERIAL_ID).orElse(""),
                new String(config.requirePasswordProperty(MoonrakerDriverGui.MOONRAKER_API_KEY_ID)));
    }

    @Override
    public DriverStatus readPrinterStatus(DriverConfiguration config) {
        try (var session = transports.open(config)) {
            return session.status();
        } catch (IOException | RuntimeException failure) {
            log.warn("Cannot read Moonraker printer status", failure);
            return new DriverStatus(
                    DriverStatus.State.ERROR,
                    "",
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    message("Cannot read Moonraker printer status", failure));
        }
    }

    @Override
    public PrintResult print(DriverConfiguration config, PrintArtifact artifact) {
        Path temporary = null;
        boolean submissionAttempted = false;
        boolean submitted = false;
        try {
            connection(config);
            if (!artifact.format().equals("gcode"))
                return new PrintResult.Failure(Set.of("Moonraker requires text G-code"));
            temporary = Files.createTempFile("voxelbrew-job-", ".gcode");
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
            return failed("Moonraker rejected print operation", rejection, false);
        } catch (IOException | RuntimeException failure) {
            if (submitted) {
                log.warn("Moonraker accepted the submission but transport cleanup failed", failure);
                return PrintResult.Success.SUCCESS;
            }
            return failed(
                    submissionAttempted
                            ? "Print submission is uncertain. Check the printer before manually submitting again"
                            : "Moonraker print operation failed",
                    failure,
                    submissionAttempted);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | RuntimeException cleanupFailure) {
                    log.warn("Cannot remove temporary Moonraker print artifact", cleanupFailure);
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
