package dev.voxelbrew.printer.moonraker;

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
import java.net.http.HttpConnectTimeoutException;
import java.nio.file.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

/** Stateless integration; every operation closes its own connection. */
@Slf4j
public final class MoonrakerDriver implements Driver {
    private final TranslationService translations;
    private final TransportFactory transports;

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
                config.requireUriProperty(MoonrakerDriverGui.MOONRAKER_URL_ID).toString(),
                config.getStringProperty(MoonrakerDriverGui.MOONRAKER_SERIAL_ID).orElse(""),
                new String(config.requirePasswordProperty(MoonrakerDriverGui.MOONRAKER_API_KEY_ID)));
    }

    public MoonrakerDriver() {
        this(Services.load(TranslationService.class));
    }

    public MoonrakerDriver(@lombok.NonNull TranslationService translations) {
        this(translations, config -> new MoonrakerSession(connection(config)));
    }

    MoonrakerDriver(@lombok.NonNull TransportFactory transports) {
        this(Services.load(TranslationService.class), transports);
    }

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The injected translation service is intentionally shared.")
    public MoonrakerDriver(
            @lombok.NonNull TranslationService translations, @lombok.NonNull TransportFactory transports) {
        this.translations = translations;
        this.transports = transports;
    }

    @Override
    public String id() {
        return "moonraker";
    }

    @Override
    public String name() {
        return translations.translate("printer.moonraker.name").orElseThrow();
    }

    @Override
    public DriverGui gui() {
        return new MoonrakerDriverGui(translations);
    }

    private void validate(DriverConfiguration config) {
        var form = gui();
        var values = config.toInput();
        var errors = form.validate(values);
        if (!errors.isEmpty())
            throw new IllegalArgumentException(
                    translations.translate("printer.invalid", errors).orElseThrow());
    }

    @Override
    public DriverStatus readPrinterStatus(DriverConfiguration config) {
        try {
            validate(config);
            try (var session = transports.open(config)) {
                return session.status();
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ConnectException
                    || failure instanceof NoRouteToHostException
                    || failure instanceof HttpConnectTimeoutException) {
                log.debug("Cannot connect to Moonraker for printer status", failure);
            } else {
                log.warn("Cannot read Moonraker printer status", failure);
            }
            return new DriverStatus(
                    DriverStatus.State.ERROR,
                    "",
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    OptionalDouble.empty(),
                    message(translations.translate("printer.moonraker.status").orElseThrow(), failure));
        }
    }

    @Override
    public PrintResult print(DriverConfiguration config, PrintArtifact artifact) {
        Path temporary = null;
        boolean submissionAttempted = false;
        boolean submitted = false;
        try {
            validate(config);
            if (!artifact.format().equals("gcode"))
                return new PrintResult.Failure(Set.of(
                        translations.translate("printer.moonraker.format").orElseThrow()));
            temporary = Files.createTempFile("voxelbrew-job-", ".gcode");
            Files.write(temporary, artifact.bytes());
            try (var session = transports.open(config)) {
                if (!session.status().canStart())
                    return new PrintResult.Failure(Set.of(
                            translations.translate("printer.moonraker.busy").orElseThrow()));
                var uploaded = session.upload(temporary, progress -> {});
                submissionAttempted = true;
                session.start(uploaded);
                submitted = true;
                return PrintResult.Success.SUCCESS;
            }
        } catch (JobRejectedException rejection) {
            return failed(translations.translate("printer.moonraker.rejected").orElseThrow(), rejection, false);
        } catch (IOException | RuntimeException failure) {
            if (submitted) {
                log.warn("Moonraker accepted the submission but transport cleanup failed", failure);
                return PrintResult.Success.SUCCESS;
            }
            return failed(
                    submissionAttempted
                            ? translations
                                    .translate("printer.moonraker.uncertain")
                                    .orElseThrow()
                            : translations.translate("printer.moonraker.failed").orElseThrow(),
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
}
