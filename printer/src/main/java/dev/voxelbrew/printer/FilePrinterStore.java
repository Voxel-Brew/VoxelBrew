package dev.voxelbrew.printer;

import com.google.gson.*;
import dev.voxelbrew.api.printer.*;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.api.userconfig.UserConfigService;
import dev.voxelbrew.util.services.Services;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Atomic credential JSON persistence with owner-only file permissions. */
public final class FilePrinterStore implements PrinterStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path directory;

    static JsonObject encode(DriverConfiguration config) {
        var result = new JsonObject();
        config.properties().forEach((key, value) -> {
            var item = new JsonObject();
            String type;
            if (value instanceof String v) {
                type = "string";
                item.addProperty("value", v);
            } else if (value instanceof Integer v) {
                type = "integer";
                item.addProperty("value", v);
            } else if (value instanceof Boolean v) {
                type = "boolean";
                item.addProperty("value", v);
            } else if (value instanceof java.math.BigDecimal v) {
                type = "decimal";
                item.addProperty("value", v.toPlainString());
            } else if (value instanceof URI v) {
                type = "uri";
                item.addProperty("value", v.toString());
            } else if (value instanceof char[] v) {
                type = "password";
                item.addProperty("value", new String(v));
                Arrays.fill(v, '\0');
            } else {
                throw new IllegalArgumentException("Unsupported configuration value");
            }
            item.addProperty("type", type);
            result.add(key, item);
        });
        return result;
    }

    static DriverConfiguration decode(JsonObject json) {
        var values = new LinkedHashMap<String, Object>();
        json.entrySet().forEach(entry -> {
            var item = entry.getValue().getAsJsonObject();
            var value = item.get("value");
            Object decoded = switch (item.get("type").getAsString()) {
                case "string" -> value.getAsString();
                case "integer" -> value.getAsBigDecimal().intValueExact();
                case "boolean" -> {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                        throw new IllegalArgumentException("Invalid boolean");
                    }
                    yield value.getAsBoolean();
                }
                case "decimal" -> new java.math.BigDecimal(value.getAsString());
                case "uri" -> URI.create(value.getAsString());
                case "password" -> value.getAsString().toCharArray();
                default -> throw new IllegalArgumentException("Unsupported configuration type");
            };
            values.put(entry.getKey(), decoded);
        });
        return new DriverConfiguration(values);
    }

    private static void restrict(Path file, boolean directory) throws IOException {
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        else {
            var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl == null) throw new IOException("Cannot restrict device credential permissions");
            var entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(file))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(entry.build()));
        }
    }

    public FilePrinterStore() {
        this(Services.load(UserConfigService.class));
    }

    public FilePrinterStore(@lombok.NonNull UserConfigService configuration) {
        this(configuration.printersFolder());
    }

    public FilePrinterStore(@lombok.NonNull Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    @Override
    public Snapshot load() throws IOException {
        var printers = new ArrayList<Printer>();
        var diagnostics = new ArrayList<String>();
        if (!Files.exists(directory)) return new Snapshot(printers, diagnostics);
        try (var files = Files.list(directory)) {
            for (var file :
                    files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                try {
                    if (Files.size(file) > 1024 * 1024) throw new IOException("Printer file too large");
                    var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    if (json.get("version").getAsBigDecimal().intValueExact() != 1) {
                        throw new IOException("Unsupported printer version");
                    }
                    var printer = new Printer(
                            json.get("id").getAsString(),
                            json.get("name").getAsString(),
                            json.get("driverId").getAsString(),
                            decode(json.getAsJsonObject("configuration")),
                            JSON.fromJson(json.get("profile"), Profile.class));
                    if (printers.stream().anyMatch(p -> p.id().equals(printer.id()))) {
                        throw new IOException("Duplicate printer ID");
                    }
                    if (printers.stream().anyMatch(p -> p.name().equalsIgnoreCase(printer.name()))) {
                        throw new IOException("Duplicate printer name");
                    }
                    if (!(printer.id() + ".json").equals(java.util.Objects.toString(file.getFileName(), ""))) {
                        throw new IOException("Mismatched printer ID");
                    }
                    printers.add(printer);
                } catch (IOException | RuntimeException failure) {
                    diagnostics.add("Invalid printer file " + file.getFileName()
                            + ". Edit or recreate this printer configuration.");
                }
            }
        }
        return new Snapshot(printers, diagnostics);
    }

    @Override
    public synchronized void save(Printer printer) throws IOException {
        Files.createDirectories(directory);
        restrict(directory, true);
        for (var existing : load().devices()) {
            if (!existing.id().equals(printer.id()) && existing.name().equalsIgnoreCase(printer.name())) {
                throw new IOException("A printer with this name already exists");
            }
        }
        var json = new JsonObject();
        json.addProperty("version", 1);
        json.addProperty("id", printer.id());
        json.addProperty("name", printer.name());
        json.addProperty("driverId", printer.driverId());
        json.add("profile", JSON.toJsonTree(printer.profile()));
        json.add("configuration", encode(printer.driverConfiguration()));
        var temporary = Files.createTempFile(directory, ".printer-", ".tmp");
        try {
            restrict(temporary, false);
            Files.writeString(temporary, JSON.toJson(json));
            Files.move(
                    temporary, file(printer.id()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void remove(String id) throws IOException {
        Files.deleteIfExists(file(id));
    }

    private Path file(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid printer ID");
        return directory.resolve(id + ".json");
    }
}
