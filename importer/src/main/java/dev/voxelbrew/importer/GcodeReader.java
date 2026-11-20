package dev.voxelbrew.importer;

import dev.voxelbrew.api.model.printing.GcodeJob;
import dev.voxelbrew.api.model.printing.GcodeJob.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Independent motion parser: layers come from deposition, not comment counts. */
public final class GcodeReader {
    private static final Pattern PARAMETER =
            Pattern.compile("(?:^|\\s)([XYZEFIJR])([-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?)");

    private static final class Parser {
        final List<String> lines = new ArrayList<String>();
        final List<Segment> segments = new ArrayList<Segment>();
        final SortedSet<Double> layers = new TreeSet<Double>();
        final Map<String, String> report = new LinkedHashMap<String, String>();
        OptionalDouble filament = OptionalDouble.empty();
        Optional<String> time = Optional.empty();
        double x = 0, y = 0, z = 0, e = 0, width = 0.45, height = 0.2;
        boolean absolute = true, absoluteE = true;
        Role role = Role.UNKNOWN;

        private static double coordinate(Map<String, Double> values, String axis, double previous, boolean absolute) {
            var value = values.get(axis);
            if (value == null) return previous;
            return value + (absolute ? 0 : previous);
        }

        private void metadata(String line) {
            if (line.startsWith(";TYPE:")) role = role(line.substring(6).strip());
            else if (line.startsWith("; FEATURE: ")) {
                role = role(line.substring(11).strip());
            } else if (line.startsWith("; LINE_WIDTH: ")) {
                width = number(line.substring(14));
            } else if (line.startsWith("; LAYER_HEIGHT: ")) {
                height = number(line.substring(16));
            } else if (line.startsWith(";WIDTH:")) {
                width = number(line.substring(7));
            } else if (line.startsWith(";HEIGHT:")) {
                height = number(line.substring(8));
            } else if (line.startsWith("; filament used [mm] = ")) {
                filament = OptionalDouble.of(number(line.substring(line.indexOf('=') + 1)));
            } else if (line.startsWith("; estimated printing time (normal mode) = ")) {
                time = Optional.of(line.substring(line.indexOf('=') + 1).strip());
            } else if (line.startsWith("; VOXELBREW ")) {
                int equals = line.indexOf('=', 12);
                if (equals > 0) {
                    report.put(
                            line.substring(12, equals).strip(),
                            line.substring(equals + 1).strip());
                }
            }
        }

        private void line(String line) throws IOException {
            lines.add(line);
            metadata(line);
            int comment = line.indexOf(';');
            String command = (comment < 0 ? line : line.substring(0, comment)).strip();
            if (command.isEmpty()) return;
            String code = command.split("\\s+", 2)[0];
            if (code.equals("G20")) {
                throw new IOException("Inch G-code is unsupported; printer job must use millimeters");
            }
            if (code.equals("G90")) {
                absolute = true;
                return;
            }
            if (code.equals("G91")) {
                absolute = false;
                return;
            }
            if (code.equals("M82")) {
                absoluteE = true;
                return;
            }
            if (code.equals("M83")) {
                absoluteE = false;
                return;
            }
            if (code.equals("G18") || code.equals("G19") || code.equals("G90.1")) {
                throw new IOException("Only XY arcs with relative I/J centers are supported");
            }
            move(code, command);
        }

        private void move(String code, String command) throws IOException {
            boolean arc = code.equals("G2") || code.equals("G3");
            if (!arc && !code.equals("G0") && !code.equals("G1") && !code.equals("G92")) return;
            var values = new HashMap<String, Double>();
            var matcher = PARAMETER.matcher(command);
            while (matcher.find()) values.put(matcher.group(1), number(matcher.group(2)));
            if (code.equals("G92")) {
                x = values.getOrDefault("X", x);
                y = values.getOrDefault("Y", y);
                z = values.getOrDefault("Z", z);
                e = values.getOrDefault("E", e);
                return;
            }
            double nx = coordinate(values, "X", x, absolute);
            double ny = coordinate(values, "Y", y, absolute);
            double nz = coordinate(values, "Z", z, absolute);
            double ne = coordinate(values, "E", e, absoluteE);
            double extrusion = ne - e;
            if (arc) {
                arc(code, values, nx, ny, nz, extrusion);
            } else if (Math.hypot(nx - x, ny - y) > 1e-8) {
                var segment = new Segment(
                        x, y, z, nx, ny, nz, extrusion, width, height, extrusion > 1e-8 ? role : Role.TRAVEL);
                addSegment(segment, nz);
            }
            x = nx;
            y = ny;
            z = nz;
            e = ne;
            if (segments.size() > 5_000_000) throw new IOException("Job exceeds preview limit of 5 million moves");
        }

        private void arc(String code, Map<String, Double> values, double nx, double ny, double nz, double extrusion)
                throws IOException {
            if (values.containsKey("R") || (!values.containsKey("I") && !values.containsKey("J"))) {
                throw new IOException("Arc requires relative I/J center");
            }
            double cx = x + values.getOrDefault("I", 0.0), cy = y + values.getOrDefault("J", 0.0);
            double radius = Math.hypot(x - cx, y - cy);
            if (radius < 1e-8 || Math.abs(Math.hypot(nx - cx, ny - cy) - radius) > 0.1) {
                throw new IOException("Invalid arc radius");
            }
            double from = Math.atan2(y - cy, x - cx), angle = Math.atan2(ny - cy, nx - cx) - from;
            if (code.equals("G2")) {
                if (angle >= -1e-10) angle -= 2 * Math.PI;
            } else if (angle <= 1e-10) {
                angle += 2 * Math.PI;
            }
            int count = Math.max(1, (int) Math.ceil(Math.abs(angle)
                    / Math.min(Math.PI / 18, 2 * Math.acos(Math.max(-1, 1 - 0.01 / radius)))));
            if (count > 50000) throw new IOException("Arc exceeds preview subdivision limit");
            double px = x, py = y, pz = z;
            for (int step = 1; step <= count; step++) {
                double fraction = (double) step / count;
                double qx = step == count ? nx : cx + radius * Math.cos(from + angle * fraction);
                double qy = step == count ? ny : cy + radius * Math.sin(from + angle * fraction);
                double qz = z + (nz - z) * fraction;
                var segment = new Segment(
                        px,
                        py,
                        pz,
                        qx,
                        qy,
                        qz,
                        extrusion / count,
                        width,
                        height,
                        extrusion > 1e-8 ? role : Role.TRAVEL);
                addSegment(segment, qz);
                px = qx;
                py = qy;
                pz = qz;
            }
        }

        private void addSegment(Segment segment, double layerZ) {
            segments.add(segment);
            if (segment.deposits() && segment.role().model()) layers.add(Math.rint(layerZ * 1e6) / 1e6);
        }

        private GcodeJob job() throws IOException {
            if (segments.stream().noneMatch(Segment::deposits)) {
                throw new IOException("Slicer output contains no deposition moves");
            }
            return new GcodeJob(segments, new ArrayList<>(layers), filament, time, report, lines);
        }
    }

    public static GcodeJob read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return read(input);
        }
    }

    /** Reads UTF-8 toolpaths; closing a provider's borrowed stream remains under host control. */
    public static GcodeJob read(InputStream input) throws IOException {
        var parser = new Parser();
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parser.line(line);
            }
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid numeric G-code", failure);
        }
        return parser.job();
    }

    private static double number(String text) {
        double number = Double.parseDouble(text.strip());
        if (!Double.isFinite(number)) throw new NumberFormatException("Nonfinite G-code value");
        return number;
    }

    private static Role role(String value) {
        return switch (value) {
            case "Perimeter", "WALL-INNER", "Inner wall" -> Role.PERIMETER;
            case "External perimeter", "WALL-OUTER", "Outer wall" -> Role.EXTERNAL_PERIMETER;
            case "Internal infill", "FILL", "Sparse infill" -> Role.INFILL;
            case "Solid infill", "Top solid infill", "SKIN", "Internal solid infill", "Top surface", "Bottom surface" ->
                Role.SOLID_INFILL;
            case "Bridge infill", "Overhang perimeter" -> Role.BRIDGE;
            case "Gap fill" -> Role.GAP_FILL;
            case "Support material", "SUPPORT", "Support" -> Role.SUPPORT;
            case "Support material interface", "SUPPORT-INTERFACE", "Support interface" -> Role.INTERFACE;
            case "Skirt/Brim", "SKIRT" -> Role.SKIRT_BRIM;
            case "Ironing", "IRONING" -> Role.IRONING;
            default -> Role.UNKNOWN;
        };
    }

    private GcodeReader() {}
}
