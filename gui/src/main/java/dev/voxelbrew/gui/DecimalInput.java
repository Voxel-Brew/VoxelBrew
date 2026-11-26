package dev.voxelbrew.gui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/** Converts desktop input to the model's locale-independent number and expression syntax. */
@UtilityClass
class DecimalInput {
    private static final Set<String> MULTI_ARGUMENT_FUNCTIONS =
            Set.of("min", "max", "clamp", "convertUnit", "formatUnit");
    private static final Pattern TOKEN = Pattern.compile(
            "\"(?:\"\"|[^\"])*+(?:\"|$)|([A-Za-z][A-Za-z._]*+)\\p{javaWhitespace}*+\\(|[A-Za-z][A-Za-z._]*+|[();]|,(?=[0-9])|(?<=[0-9]),");

    private static final class Group {
        final List<Integer> decimalCandidates = new ArrayList<>();
        final boolean multiArgument;
        boolean semicolon;

        Group(String function) {
            multiArgument = function != null && MULTI_ARGUMENT_FUNCTIONS.contains(function);
        }
    }

    static double parse(String input) {
        return Double.parseDouble(input.strip().replace(',', '.'));
    }

    static String expression(String input) {
        if (input.indexOf(',') < 0 && input.indexOf(';') < 0) return input;
        var result = new StringBuilder(input);
        var tokens = TOKEN.matcher(input);
        var groups = new ArrayDeque<Group>();
        while (tokens.find()) {
            var group = groups.peek();
            int index = tokens.start();
            if (tokens.group(1) != null || input.charAt(index) == '(') {
                groups.push(new Group(tokens.group(1)));
            } else {
                switch (input.charAt(index)) {
                    case ')' -> groups.poll();
                    case ';' -> {
                        if (group != null && group.multiArgument) {
                            // A semicolon selects decimal commas for this argument list only.
                            group.semicolon = true;
                            for (int candidate : group.decimalCandidates) result.setCharAt(candidate, '.');
                            group.decimalCandidates.clear();
                            result.setCharAt(index, ',');
                        }
                    }
                    case ',' -> {
                        if (group == null || !group.multiArgument || group.semicolon) result.setCharAt(index, '.');
                        else group.decimalCandidates.add(index);
                    }
                    default -> {
                        // Quoted strings retain their exact contents.
                    }
                }
            }
        }
        return result.toString();
    }
}
