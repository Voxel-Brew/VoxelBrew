package dev.voxelbrew.gui;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

final class OperationIcons {
    static Group icon(String command) {
        return icon(command, Color.web("#658dcc"));
    }

    static Group icon(String command, Color stroke) {
        var shape = new SVGPath();
        shape.setContent(
                switch (command) {
                    case "constrained-sketch" -> "M2 2H13V6 M2 2V18H13V14 M7 13L9 9L16 2L19 5L12 12Z";
                    case "extrude" ->
                        "M2 11L10 7L18 11V16L10 20L2 16Z M2 11L10 15L18 11 M10 15V20 M10 10V1 M6 5L10 1L14 5";
                    case "cut" -> "M2 8L10 4L18 8V16L10 20L2 16Z M2 8L10 12L18 8 M10 12V20 M10 1V10 M6 6L10 10L14 6";
                    case "new-body" -> "M2 6L10 2L18 6V15L10 19L2 15Z M2 6L10 10L18 6 M10 10V19";
                    case "join" ->
                        "M1 8L7 5L13 8V11L19 14V17L13 20L1 14Z M1 8L7 11L13 8 M7 11V14L13 17L19 14 M13 17V20 M17 1V7 M14 4H20";
                    case "fillet" -> "M2 19V9Q2 2 9 2H19 M6 19V10Q6 6 10 6H19 M2 19H6 M19 2V6";
                    case "rectangular" -> "M2 2H8V8H2Z M12 2H18V8H12Z M2 12H8V18H2Z M12 12H18V18H12Z";
                    case "mirror" -> "M2 16L7 5V16Z M18 16L13 5V16Z M10 1V5 M10 8V12 M10 15V19";
                    case "circular" ->
                        "M10 1L13 4L10 7L7 4Z M19 10L16 13L13 10L16 7Z M10 19L7 16L10 13L13 16Z M1 10L4 7L7 10L4 13Z";
                    case "chamfer" -> "M2 19V9L9 2H19 M6 19V11L11 6H19 M2 19H6 M19 2V6";
                    case "shell" -> "M2 6L10 2L18 6V15L10 19L2 15Z M2 6L10 10L18 6 M10 10V19 M6 6L10 4L14 6L10 8Z";
                    case "split-body" -> "M1 6L7 3L7 17L1 14Z M13 3L19 6V14L13 17Z M10 1V5 M10 8V12 M10 15V19";
                    default -> throw new IllegalArgumentException("Unknown operation: " + command);
                });
        shape.setFill(Color.TRANSPARENT);
        shape.setStroke(stroke);
        shape.setStrokeWidth(1.5);
        var group = new Group(shape);
        group.setMouseTransparent(true);
        return group;
    }

    private OperationIcons() {}
}
