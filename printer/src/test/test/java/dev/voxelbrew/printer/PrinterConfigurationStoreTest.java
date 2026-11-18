package dev.voxelbrew.printer;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.printer.*;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.util.config.UserConfiguration;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterConfigurationStoreTest {
    @TempDir
    Path directory;

    private Profile profile() {
        return new DefaultPrinterProfileCatalog(new PrinterResources(directory.resolve("profiles")))
                .load()
                .require("prusa-mk4");
    }

    @Test
    void roundTripsAllTypesAndEmbeddedProfileWithRestrictedCredentials() throws Exception {
        var store = new FilePrinterStore(directory);
        var values = Map.<String, Object>of(
                "text",
                "hello",
                "integer",
                42,
                "boolean",
                true,
                "decimal",
                new BigDecimal("1.2300"),
                "uri",
                URI.create("https://localhost"),
                "password",
                "secret".toCharArray());
        var original = new Printer("id", "Workshop", "uninstalled-plugin", new DriverConfiguration(values), profile());
        store.save(original);
        var loaded = store.load().devices().getFirst();
        assertThat(loaded.profile()).isEqualTo(original.profile());
        assertThat(loaded.driverId()).isEqualTo("uninstalled-plugin");
        assertThat(loaded.driverConfiguration().properties()).containsEntry("decimal", new BigDecimal("1.2300"));
        assertThat(loaded.driverConfiguration().requirePasswordProperty("password"))
                .isEqualTo("secret".toCharArray());
        assertThat(loaded.toString()).doesNotContain("secret");
        var posix = Files.getFileAttributeView(
                directory.resolve("id.json"), java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null)
            assertThat(posix.readAttributes().permissions())
                    .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        else {
            var acl = Files.getFileAttributeView(
                    directory.resolve("id.json"), java.nio.file.attribute.AclFileAttributeView.class);
            assertThat(acl.getAcl())
                    .allSatisfy(entry ->
                            assertThat(entry.principal()).isEqualTo(Files.getOwner(directory.resolve("id.json"))));
        }
        assertThatThrownBy(() -> store.save(
                        new Printer("other", "WORKSHOP", "external", new DriverConfiguration(Map.of()), profile())))
                .isInstanceOf(java.io.IOException.class);
        store.remove("id");
        assertThat(store.load().devices()).isEmpty();
    }

    @Test
    void storesKeepConstructorInjectedDirectoriesIndependent() throws Exception {
        Path firstDirectory = directory.resolve("first/printers");
        Path secondDirectory = directory.resolve("second");
        var first = new FilePrinterStore(new dev.voxelbrew.util.config.FileUserConfiguration(
                firstDirectory.resolveSibling("config.properties")));
        var second = new FilePrinterStore(secondDirectory);
        assertThat(first.load().devices()).isEmpty();
        assertThat(second.load().devices()).isEmpty();
        assertThat(firstDirectory).doesNotExist();
        assertThat(secondDirectory).doesNotExist();
        var firstPrinter = new Printer("id", "First", "external", new DriverConfiguration(Map.of()), profile());
        var secondPrinter = new Printer("id", "Second", "external", new DriverConfiguration(Map.of()), profile());
        first.save(firstPrinter);
        second.save(secondPrinter);
        assertThat(first.load().devices()).extracting(Printer::name).containsExactly("First");
        assertThat(second.load().devices()).extracting(Printer::name).containsExactly("Second");
        first.remove("id");
        assertThat(first.load().devices()).isEmpty();
        assertThat(second.load().devices()).extracting(Printer::name).containsExactly("Second");
    }

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void defaultConstructorUsesUserConfigurationAndDelegatesToTheSameDirectory() throws Exception {
        String previous = System.getProperty("voxelbrew.config");
        Path configurationFile = directory.resolve("user/config.properties");
        Path printers = configurationFile.resolveSibling("printers");
        try {
            System.setProperty("voxelbrew.config", configurationFile.toString());
            var store = new FilePrinterStore();
            assertThat(printers).doesNotExist();
            var profile = profile();
            store.save(new Printer("id", "Default", "external", new DriverConfiguration(Map.of()), profile));
            assertThat(new FilePrinterStore(UserConfiguration.user()).load().devices())
                    .extracting(Printer::name)
                    .containsExactly("Default");
            assertThat(new FilePrinterStore(printers).load().devices())
                    .extracting(Printer::name)
                    .containsExactly("Default");
            assertThat(configurationFile).doesNotExist();
        } finally {
            if (previous == null) System.clearProperty("voxelbrew.config");
            else System.setProperty("voxelbrew.config", previous);
        }
    }

    @Test
    void updatesAnExistingIdAndRejectsAnotherFileWithThatId() throws Exception {
        var store = new FilePrinterStore(directory);
        var config = new DriverConfiguration(Map.of("password", "sensitive".toCharArray()));
        var profile = profile();
        store.save(new Printer("id", "Original", "external", config, profile));
        store.save(new Printer("id", "Renamed", "external", config, profile));
        assertThat(store.load().devices()).extracting(Printer::name).containsExactly("Renamed");

        var duplicate = com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("id.json")))
                .getAsJsonObject();
        duplicate.addProperty("name", "Different name");
        Files.writeString(directory.resolve("z-duplicate.json"), duplicate.toString());

        var snapshot = store.load();
        assertThat(snapshot.devices()).extracting(Printer::id).containsExactly("id");
        assertThat(snapshot.devices()).extracting(Printer::name).containsExactly("Renamed");
        assertThat(snapshot.diagnostics())
                .singleElement()
                .asString()
                .contains("z-duplicate.json")
                .doesNotContain("sensitive");
    }

    @Test
    void malformedVersionsAndTypesHaveRedactedDiagnostics() throws Exception {
        Files.writeString(directory.resolve("broken.json"), "{\"version\":900,\"secret\":\"sensitive\"}");
        var snapshot = new FilePrinterStore(directory).load();
        assertThat(snapshot.devices()).isEmpty();
        assertThat(snapshot.diagnostics()).hasSize(1);
        assertThat(snapshot.diagnostics().toString()).doesNotContain("sensitive");
        assertThatThrownBy(() -> FilePrinterStore.decode(
                        com.google.gson.JsonParser.parseString("{\"x\":{\"type\":\"object\",\"value\":{}}}")
                                .getAsJsonObject()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedAtomicReplacementPreservesTargetAndCleansTemporary() throws Exception {
        Files.createDirectory(directory.resolve("id.json"));
        Files.writeString(directory.resolve("id.json/keep"), "original");
        assertThatThrownBy(() -> new FilePrinterStore(directory)
                        .save(new Printer("id", "Name", "x", new DriverConfiguration(Map.of()), profile())))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(directory.resolve("id.json/keep"))).isEqualTo("original");
        try (var files = Files.list(directory)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".printer-"));
        }
    }
}
