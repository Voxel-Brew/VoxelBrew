import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.userconfig.UserConfigService;
import dev.voxelbrew.util.services.Services;
import dev.voxelbrew.util.update.*;
import java.nio.file.*;

/** Fresh-process package acceptance: uses installed utilities and the bundled JVM. */
public final class UpdateProbe {
    @SuppressWarnings("try")
    public static void main(String[] args) throws Exception {
        var component = ApplicationImage.Component.valueOf(args[0]);
        if (args[1].equals("write-document")) {
            verifyDocumentVersion(args[2], Path.of(args[3]));
            return;
        }
        if (args[1].equals("read-document")) {
            var document = dev.voxelbrew.importer.DocumentReader.load(Path.of(args[2]));
            System.out.println("DOCUMENT_READ_PASS " + document.id());
            return;
        }
        try (var running = ApplicationImage.running(component)) {
            if (args[1].equals("utilities")) {
                verifyUtilities(component);
            } else if (args[1].equals("hold")) {
                Files.writeString(Path.of(args[2]), "LOCKED");
                long deadline =
                        System.nanoTime() + java.time.Duration.ofMinutes(3).toNanos();
                while (!Files.exists(Path.of(args[3]))) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("Holder timed out");
                    Thread.sleep(100);
                }
            } else {
                var service = UpdateService.system(component);
                var cancellation = new UpdateCancellation();
                var selected = service.check(new UpdateService.Request(null, false), cancellation);
                try (var prepared = service.prepare(selected, cancellation, System.out::println)) {
                    if (args[1].equals("cancel")) {
                        System.out.println("Cancelled prepared update: " + prepared.statusFile());
                        return;
                    }
                    prepared.handOff();
                    System.out.println("Status: " + prepared.statusFile());
                }
            }
        }
    }

    private static void verifyUtilities(ApplicationImage.Component component) throws Exception {
        var polish = java.util.Locale.forLanguageTag("pl-PL");
        if (!"1,5".equals(java.text.NumberFormat.getInstance(polish).format(1.5)))
            throw new IllegalStateException("Bundled runtime is missing Polish locale data");
        verifyPolishTranslations();
        var configuration = Services.load(UserConfigService.class);
        configuration.saveUserConfig("verification.text", "Zażółć ☕");
        if (!"Zażółć ☕"
                .equals(Services.load(UserConfigService.class)
                        .readUserConfig("verification.text")
                        .orElse(""))) throw new IllegalStateException("Bundled configuration did not round-trip UTF-8");
        configuration.clearUserConfig("verification.text");
        dev.voxelbrew.util.logging.Logging.initialize(component.name().toLowerCase(java.util.Locale.ROOT));
        java.util.logging.Logger.getLogger("dev.voxelbrew.verification").info("UTILITY_RUNTIME_LOG");
        dev.voxelbrew.util.logging.Logging.close();
        Path activeLog = configuration.logsFolder().resolve("app.log");
        boolean logged =
                Files.isRegularFile(activeLog) && Files.readString(activeLog).contains("UTILITY_RUNTIME_LOG");
        if (!logged) throw new IllegalStateException("Bundled file logging is unavailable");
        System.out.println("PACKAGE_UTIL_PASS " + component);
    }

    private static void verifyDocumentVersion(String expectedVersion, Path path) throws Exception {
        if (!expectedVersion.equals(dev.voxelbrew.util.Version.version()))
            throw new IllegalStateException("Packaged Version does not match the installed release");
        var document = dev.voxelbrew.api.model.CadDocument.empty();
        dev.voxelbrew.exporter.DocumentWriter.save(document, path, false);
        byte[] bytes = Files.readAllBytes(path);
        var message = dev.voxelbrew.engine.persistence.proto.DocumentProto.Document.parseFrom(
                java.util.Arrays.copyOfRange(bytes, 44, bytes.length));
        if (!expectedVersion.equals(message.getApplicationVersion()))
            throw new IllegalStateException("Document writer version does not match Version.version()");
        if (!document.equals(dev.voxelbrew.importer.DocumentReader.load(path)))
            throw new IllegalStateException("Packaged document did not round-trip");
        System.out.println("DOCUMENT_VERSION_PASS " + expectedVersion);
    }

    private static void verifyPolishTranslations() throws java.io.IOException {
        Path home = Files.createTempDirectory("voxelbrew-translation-probe");
        Path preferences = home.resolve("config.properties");
        String previousHome = System.getProperty("voxelbrew.home");
        String previous = System.getProperty("voxelbrew.config");
        try {
            Files.writeString(preferences, "language=pl\n");
            System.setProperty("voxelbrew.home", home.toString());
            System.setProperty("voxelbrew.config", preferences.getFileName().toString());
            var messages = Services.load(TranslationService.class);
            if (!"Otwórz…".equals(messages.translate("action.open").orElse(null)))
                throw new IllegalStateException("Bundled translations are missing or incorrectly encoded");
        } finally {
            if (previous == null) System.clearProperty("voxelbrew.config");
            else System.setProperty("voxelbrew.config", previous);
            if (previousHome == null) System.clearProperty("voxelbrew.home");
            else System.setProperty("voxelbrew.home", previousHome);
            Files.deleteIfExists(preferences);
            Files.deleteIfExists(home.resolve("config.properties.lock"));
            Files.deleteIfExists(home);
        }
    }

    private UpdateProbe() {}
}
