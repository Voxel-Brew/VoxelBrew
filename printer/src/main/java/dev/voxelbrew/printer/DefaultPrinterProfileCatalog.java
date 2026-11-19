package dev.voxelbrew.printer;

import com.google.gson.*;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.printer.PrinterProfileCatalog;
import dev.voxelbrew.api.printer.Profile;
import dev.voxelbrew.util.config.ApplicationPaths;
import java.nio.file.Path;
import java.util.*;
import lombok.NonNull;

/** Validated, reloadable catalog. Saved documents never depend on this service. */
public final class DefaultPrinterProfileCatalog implements PrinterProfileCatalog {
    private final PrinterResources resources;

    public DefaultPrinterProfileCatalog() {
        this(dev.voxelbrew.util.services.Services.load(ApplicationPaths.class).profiles());
    }

    public DefaultPrinterProfileCatalog(@NonNull Path directory) {
        this(new PrinterResources(directory));
    }

    public DefaultPrinterProfileCatalog(@NonNull PrinterResources resources) {
        this.resources = resources;
    }

    @Override
    public String fallbackImage() {
        var url = PrinterResources.class.getResource(PrinterResources.ROOT + "generic.png");
        return url == null ? null : url.toExternalForm();
    }

    @Override
    public java.nio.file.Path directory() {
        return resources.directory();
    }

    /** Creates editable copies without overwriting existing user presets or images. */
    @Override
    public int exportDefaults(Set<String> selectedIds) throws java.io.IOException {
        selectedIds = Set.copyOf(selectedIds);
        if (selectedIds.isEmpty()) return 0;
        java.nio.file.Files.createDirectories(directory());
        int exported = 0;
        for (var source : resources.load().sources()) {
            if (!source.bundled()) continue;
            var json = JsonParser.parseString(source.json()).getAsJsonObject();
            if (!selectedIds.contains(json.get("id").getAsString())) continue;
            String stem = "custom-" + json.get("id").getAsString();
            var target = directory().resolve(stem + ".json");
            if (java.nio.file.Files.exists(target)) continue;
            String image = json.has("image") ? json.get("image").getAsString() : null;
            if (image != null) {
                String resource = PrinterResources.image(source, image);
                if (resource != null) {
                    var destination = directory().resolve(stem + ".png");
                    if (!java.nio.file.Files.exists(destination)) {
                        try (var input = java.net.URI.create(resource).toURL().openStream()) {
                            copyNew(input, destination);
                        }
                    }
                }
            }
            json.addProperty("id", stem);
            json.remove("image");
            try (var input = new java.io.ByteArrayInputStream(
                    (new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                if (copyNew(input, target)) exported++;
            }
        }
        for (var name : List.of("PHOTO-LICENSE.txt", "photo-sources.json")) {
            try (var input = PrinterResources.class.getResourceAsStream(PrinterResources.ROOT + name)) {
                if (input != null) copyNew(input, directory().resolve(name.replace(".json", ".txt")));
            }
        }
        return exported;
    }

    private static boolean copyNew(java.io.InputStream input, java.nio.file.Path target) throws java.io.IOException {
        if (java.nio.file.Files.exists(target)) return false;
        var parent = target.getParent();
        if (parent == null) throw new java.io.IOException("Expected a profile destination file: " + target);
        var temporary = java.nio.file.Files.createTempFile(parent, ".printer-export-", ".tmp");
        try {
            java.nio.file.Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                java.nio.file.Files.move(temporary, target);
                return true;
            } catch (java.nio.file.FileAlreadyExistsException ex) {
                return false;
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temporary);
        }
    }

    @Override
    public Result load() {
        var loaded = resources.load();
        var errors = new ArrayList<>(loaded.diagnostics());
        var entries = new LinkedHashMap<String, Entry>();
        var duplicateCustom = new HashSet<String>();
        var bundled = new HashSet<String>();
        for (var source : loaded.sources()) {
            try {
                var json = JsonParser.parseString(source.json()).getAsJsonObject();
                if (json.get("version").getAsBigDecimal().intValueExact() != 1)
                    throw new IllegalArgumentException("Unsupported printer JSON version");
                var parsed = parse(json);
                String reference = PrinterResources.image(
                        source, json.has("image") ? json.get("image").getAsString() : null);
                var p = new Profile(
                        parsed.id(),
                        parsed.name(),
                        parsed.manufacturer(),
                        parsed.bed(),
                        parsed.excludedZones(),
                        parsed.usableHeight(),
                        parsed.nozzleDiameter(),
                        parsed.filamentDiameter(),
                        parsed.toolCount(),
                        parsed.firmware(),
                        parsed.outputFormat(),
                        parsed.genericGcode(),
                        reference == null ? null : java.net.URI.create(reference));
                String id = p.printer().id();
                if (entries.containsKey(id) || duplicateCustom.contains(id)) {
                    if (!bundled.contains(id)) {
                        entries.remove(id);
                        duplicateCustom.add(id);
                    }
                    throw new IllegalArgumentException("Duplicate printer ID: " + id);
                }
                entries.put(
                        id,
                        new Entry(
                                p,
                                PrinterResources.image(
                                        source,
                                        source.bundled() && json.has("image")
                                                ? json.get("image").getAsString()
                                                : null),
                                source.name(),
                                source.bundled()));
                if (source.bundled()) bundled.add(id);
            } catch (RuntimeException ex) {
                errors.add(source.name() + ": " + ex.getMessage());
            }
        }
        return new Result(
                entries.values().stream()
                        .sorted(Comparator.comparing(Entry::bundled).thenComparing(Entry::name))
                        .toList(),
                errors);
    }

    private static Profile parse(JsonObject p) {
        var zones = new ArrayList<List<PrintProfile.Point>>();
        for (var zone : p.getAsJsonArray("excludedZones")) zones.add(polygon(zone.getAsJsonArray()));
        return new Profile(
                p.get("id").getAsString(),
                p.get("name").getAsString(),
                p.get("manufacturer").getAsString(),
                polygon(p.getAsJsonArray("bed")),
                zones,
                p.get("usableHeightMm").getAsDouble(),
                p.get("nozzleMm").getAsDouble(),
                p.get("filamentDiameterMm").getAsDouble(),
                p.get("toolCount").getAsBigDecimal().intValueExact(),
                p.get("firmware").getAsString(),
                p.get("outputFormat").getAsString(),
                p.get("genericGcode").getAsBoolean(),
                null);
    }

    @Override
    public Profile save(Profile profile, java.nio.file.Path image) throws java.io.IOException {
        if (!profile.id().matches("[a-zA-Z0-9.-]{1,128}")) throw new IllegalArgumentException("Invalid profile ID");
        if (load().entries().stream()
                .anyMatch(e -> e.bundled() && e.definition().id().equals(profile.id())))
            throw new IllegalArgumentException("Bundled profiles are read-only; choose a new ID");
        java.nio.file.Files.createDirectories(directory());
        if (image == null
                && profile.imageReference() != null
                && "jar".equals(profile.imageReference().getScheme())) {
            boolean bundledImage = load().entries().stream()
                    .anyMatch(e -> e.bundled()
                            && profile.imageReference().equals(e.definition().imageReference()));
            if (!bundledImage) throw new IllegalArgumentException("Choose a local image or installed bundled image");
            var temporaryImage = java.nio.file.Files.createTempFile(directory(), ".profile-image-", ".png");
            try (var input = profile.imageReference().toURL().openStream()) {
                java.nio.file.Files.copy(input, temporaryImage, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                for (var notice : List.of("PHOTO-LICENSE.txt", "photo-sources.json")) {
                    try (var license = PrinterResources.class.getResourceAsStream(PrinterResources.ROOT + notice)) {
                        if (license != null) copyNew(license, directory().resolve(notice.replace(".json", ".txt")));
                    }
                }
                return save(profile, temporaryImage);
            } finally {
                java.nio.file.Files.deleteIfExists(temporaryImage);
            }
        }
        String imageName = null;
        if (image != null) {
            if (java.nio.file.Files.size(image) > 5 * 1024 * 1024)
                throw new java.io.IOException("Profile image exceeds 5 MiB");
            try (var input = java.nio.file.Files.newInputStream(image)) {
                if (javax.imageio.ImageIO.read(input) == null)
                    throw new java.io.IOException("Unsupported profile image");
            }
            String extension = java.util.Objects.toString(image.getFileName(), "")
                    .replaceFirst("^.*\\.", "")
                    .toLowerCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("png", "jpg", "jpeg", "gif", "bmp").contains(extension))
                throw new java.io.IOException("Unsupported profile image type");
            imageName = profile.id() + "-" + java.util.UUID.randomUUID() + "." + extension;
            java.nio.file.Files.copy(image, directory().resolve(imageName));
        }
        var json = new JsonObject();
        json.addProperty("version", 1);
        json.addProperty("id", profile.id());
        json.addProperty("name", profile.name());
        json.addProperty("manufacturer", profile.manufacturer());
        json.addProperty("nozzleMm", profile.nozzleDiameter());
        json.addProperty("filamentDiameterMm", profile.filamentDiameter());
        json.addProperty("usableHeightMm", profile.usableHeight());
        json.addProperty("toolCount", profile.toolCount());
        json.addProperty("firmware", profile.firmware());
        json.addProperty("outputFormat", profile.outputFormat());
        json.addProperty("genericGcode", profile.genericGcode());
        var gson = new GsonBuilder().setPrettyPrinting().create();
        json.add("bed", points(profile.bed()));
        var zones = new JsonArray();
        for (var zone : profile.excludedZones()) zones.add(points(zone));
        json.add("excludedZones", zones);
        if (imageName != null) json.addProperty("image", imageName);
        else if (profile.imageReference() != null
                && "file".equals(profile.imageReference().getScheme())) {
            var path = java.nio.file.Path.of(profile.imageReference());
            var filename = path.getFileName();
            if (filename != null && directory().equals(path.getParent()))
                json.addProperty("image", filename.toString());
        }
        var temporary = java.nio.file.Files.createTempFile(directory(), ".profile-", ".tmp");
        try {
            java.nio.file.Files.writeString(temporary, gson.toJson(json));
            java.nio.file.Files.move(
                    temporary,
                    directory().resolve(profile.id() + ".json"),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException failure) {
            if (imageName != null)
                java.nio.file.Files.deleteIfExists(directory().resolve(imageName));
            throw failure;
        } finally {
            java.nio.file.Files.deleteIfExists(temporary);
        }
        return load().require(profile.id());
    }

    private static JsonArray points(List<PrintProfile.Point> points) {
        var json = new JsonArray();
        for (var point : points) {
            var pair = new JsonArray();
            pair.add(point.x());
            pair.add(point.y());
            json.add(pair);
        }
        return json;
    }

    private static List<PrintProfile.Point> polygon(JsonArray points) {
        if (points.size() > 1024) throw new IllegalArgumentException("Too many polygon points");
        var result = new ArrayList<PrintProfile.Point>();
        for (var point : points) {
            var pair = point.getAsJsonArray();
            if (pair.size() != 2) throw new IllegalArgumentException("Expected [x, y] point");
            result.add(new PrintProfile.Point(
                    pair.get(0).getAsDouble(), pair.get(1).getAsDouble()));
        }
        return List.copyOf(result);
    }
}
