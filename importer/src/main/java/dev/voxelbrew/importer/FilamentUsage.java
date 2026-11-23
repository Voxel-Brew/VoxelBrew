package dev.voxelbrew.importer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/** Slicer-reported consumption for one plate and its individual filament slots. */
public record FilamentUsage(OptionalDouble millimeters, OptionalDouble grams, List<Filament> filaments) {
    public record Filament(int slot, String color, OptionalDouble millimeters, OptionalDouble grams) {}

    public static FilamentUsage fromLines(List<String> lines) {
        List<OptionalDouble> lengths = List.of(), weights = List.of();
        List<String> colors = List.of(), extruderColors = List.of();
        var totalLength = OptionalDouble.empty();
        var totalWeight = OptionalDouble.empty();
        for (String line : lines) {
            if (!line.startsWith(";")) continue;
            String comment = line.substring(1).stripLeading();
            if (!comment.regionMatches(true, 0, "filament", 0, 8)
                    && !comment.regionMatches(true, 0, "total filament", 0, 14)
                    && !comment.regionMatches(true, 0, "extruder_col", 0, 12)) continue;
            int separator = comment.indexOf('=');
            if (separator < 0) separator = comment.indexOf(':');
            if (separator < 0) continue;
            String key = comment.substring(0, separator).strip().toLowerCase(Locale.ROOT);
            String value = comment.substring(separator + 1).strip();
            switch (key) {
                case "filament used [mm]", "filament length [mm]" -> lengths = amounts(value, 1);
                case "filament used [g]", "filament weight [g]" -> weights = amounts(value, 1);
                case "total filament used [g]", "total filament weight [g]" -> totalWeight = sum(amounts(value, 1));
                case "total filament used [mm]", "total filament length [mm]" -> totalLength = sum(amounts(value, 1));
                case "filament_colour", "filament_color" -> colors = Arrays.asList(value.split("[;,]", -1));
                case "extruder_colour", "extruder_color" -> extruderColors = Arrays.asList(value.split("[;,]", -1));
                case "filament used" -> {
                    if (value.matches("[+0-9.eE ,m-]+m")) {
                        boolean millimeters = value.endsWith("mm");
                        String unit = millimeters ? "mm" : "m";
                        lengths = amounts(value.replaceAll(unit + "(?=\\s*(?:,|$))", ""), millimeters ? 1 : 1000);
                    }
                }
                default -> {}
            }
        }
        var length = totalLength.isPresent() ? totalLength : sum(lengths);
        var weight = totalWeight.isPresent() ? totalWeight : sum(weights);
        return new FilamentUsage(length, weight, filaments(lengths, weights, colors, extruderColors, length, weight));
    }

    private static List<Filament> filaments(
            List<OptionalDouble> lengths,
            List<OptionalDouble> weights,
            List<String> colors,
            List<String> extruderColors,
            OptionalDouble length,
            OptionalDouble weight) {
        var filaments = new ArrayList<Filament>();
        int slots = Math.max(lengths.size(), weights.size());
        boolean single = Math.max(slots, Math.max(colors.size(), extruderColors.size())) == 1;
        for (int i = 0; i < (single ? 1 : slots); i++) {
            var mm = single && lengths.isEmpty() ? length : amountAt(lengths, i);
            var g = single && weights.isEmpty() ? weight : amountAt(weights, i);
            if (mm.orElse(0) == 0 && g.orElse(0) == 0) continue;
            var color = colorAt(colors, i);
            filaments.add(new Filament(i + 1, color.isEmpty() ? colorAt(extruderColors, i) : color, mm, g));
        }
        return filaments;
    }

    private static String colorAt(List<String> colors, int index) {
        if (index >= colors.size()) return "";
        String color = colors.get(index).strip();
        return color.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?") ? color : "";
    }

    private static OptionalDouble amountAt(List<OptionalDouble> values, int index) {
        return index < values.size() ? values.get(index) : OptionalDouble.empty();
    }

    private static List<OptionalDouble> amounts(String text, double scale) {
        var values = new ArrayList<OptionalDouble>();
        for (String part : text.split(",", -1)) {
            try {
                double value = Double.parseDouble(part.strip()) * scale;
                values.add(value >= 0 && Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty());
            } catch (NumberFormatException invalid) {
                values.add(OptionalDouble.empty());
            }
        }
        return values;
    }

    private static OptionalDouble sum(List<OptionalDouble> values) {
        if (values.isEmpty()) return OptionalDouble.empty();
        double total = 0;
        for (var value : values) {
            if (value.isEmpty()) return OptionalDouble.empty();
            total += value.getAsDouble();
        }
        return Double.isFinite(total) ? OptionalDouble.of(total) : OptionalDouble.empty();
    }

    public FilamentUsage {
        filaments = List.copyOf(filaments);
    }

    public FilamentUsage(OptionalDouble millimeters, OptionalDouble grams) {
        this(millimeters, grams, List.of());
    }
}
