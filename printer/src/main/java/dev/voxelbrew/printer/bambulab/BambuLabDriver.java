package dev.voxelbrew.printer.bambulab;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.printing.PrintArtifact;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.printer.internal.JobRejectedException;
import dev.voxelbrew.printer.internal.TransportFactory;
import dev.voxelbrew.util.services.Services;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.nio.file.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

/** Stateless integration; every operation closes its own connection. */
@Slf4j
public final class BambuLabDriver implements Driver {
    private final TranslationService translations;
    private final TransportFactory transports;

    private static boolean isConnectionFailure(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof NoRouteToHostException
                    || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static PrintResult.Failure failed(String context, Exception failure, boolean submissionUncertain) {
        log.warn(context, failure);
        return new PrintResult.Failure(Set.of(message(context, failure)), submissionUncertain);
    }

    private static String message(String context, Exception failure) {
        String detail = failure.getLocalizedMessage();
        return detail == null || detail.isBlank() ? context : context + ": " + detail;
    }

    private static Connection connection(DriverConfiguration config) {
        return new Connection(
                config.requireUriProperty(BambuLabDriverGui.BAMBU_ADDRESS_ID).getHost(),
                config.requireStringProperty(BambuLabDriverGui.BAMBU_SERIAL_ID),
                new String(config.requirePasswordProperty(BambuLabDriverGui.BAMBU_SECRET_ID)));
    }

    public BambuLabDriver() {
        this(Services.load(TranslationService.class));
    }

    public BambuLabDriver(@lombok.NonNull TranslationService translations) {
        this(translations, config -> new BambuSession(connection(config)));
    }

    BambuLabDriver(@lombok.NonNull TransportFactory transports) {
        this(Services.load(TranslationService.class), transports);
    }

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The injected translation service is intentionally shared.")
    public BambuLabDriver(
            @lombok.NonNull TranslationService translations, @lombok.NonNull TransportFactory transports) {
        this.translations = translations;
        this.transports = transports;
    }

    @Override
    public String id() {
        return "bambulab";
    }

    @Override
    public String name() {
        return translations.translate("printer.bambulab.name");
    }

    @Override
    public DriverGui gui() {
        return new BambuLabDriverGui(translations);
    }

    private void validate(DriverConfiguration config) {
        var form = gui();
        var values = config.toInput();
        var errors = form.validate(values);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(translations.translate("printer.invalid", errors));
        }
    }

    @Override
    public DriverStatus readPrinterStatus(DriverConfiguration config) {
        try {
            validate(config);
            try (var session = transports.open(config)) {
                return session.status();
            }
        } catch (IOException | RuntimeException failure) {
            if (isConnectionFailure(failure)) {
                log.debug("Cannot connect to BambuLab for printer status", failure);
            } else {
                log.warn("Cannot read BambuLab printer status", failure);
            }
            return new DriverStatus(
                    DriverStatus.State.ERROR,
                    "",
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    message(translations.translate("printer.bambulab.status"), failure));
        }
    }

    @Override
    public PrintResult print(DriverConfiguration config, PrintArtifact artifact) {
        Path temporary = null;
        boolean submissionAttempted = false;
        boolean submitted = false;
        try {
            validate(config);
            if (!artifact.format().equals("gcode.3mf")) {
                return new PrintResult.Failure(Set.of(translations.translate("printer.bambulab.format")));
            }
            if (!"orca".equals(artifact.metadata().get("backend"))
                    || !"0".equals(artifact.metadata().get("tool"))) {
                return new PrintResult.Failure(Set.of(translations.translate("printer.bambulab.tool")));
            }
            temporary = Files.createTempFile("voxelbrew-job-", ".gcode.3mf");
            Files.write(temporary, artifact.bytes());
            try (var session = transports.open(config)) {
                if (!session.status().canStart()) {
                    return new PrintResult.Failure(Set.of(translations.translate("printer.bambulab.busy")));
                }
                var uploaded = session.upload(temporary, progress -> {});
                submissionAttempted = true;
                session.start(uploaded);
                submitted = true;
                return PrintResult.Success.SUCCESS;
            }
        } catch (JobRejectedException rejection) {
            return failed(translations.translate("printer.bambulab.rejected"), rejection, false);
        } catch (IOException | RuntimeException failure) {
            if (submitted) {
                log.warn("BambuLab accepted the submission but transport cleanup failed", failure);
                return PrintResult.Success.SUCCESS;
            }
            return failed(
                    submissionAttempted
                            ? translations.translate("printer.bambulab.uncertain")
                            : translations.translate("printer.bambulab.failed"),
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
}
