package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.engine.model.BodyPattern;
import java.util.Map;
import java.util.function.Consumer;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

final class BodyPatternDialog {
    static void show(
            Stage stage,
            String title,
            TranslationService tr,
            BodyPattern initial,
            Map<Feature.BodyRef, String> sources,
            Consumer<BodyPattern> accept) {
        var dialog = new Dialog<Void>();
        dialog.setTitle(title);
        var grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        var body = new ComboBox<Feature.BodyRef>();
        body.setId("pattern-source");
        body.getItems().setAll(sources.keySet());
        UiText.choices(body, sources::get);
        body.setValue(initial.source());
        grid.addRow(0, new Label(tr.translate("gui.input_body")), body);
        var axis = new ComboBox<BodyPattern.Axis>();
        axis.setId("pattern-axis");
        axis.getItems().setAll(BodyPattern.Axis.values());
        axis.setValue(initial.axis());
        boolean rectangular = initial.kind() == BodyPattern.Kind.RECTANGULAR;
        boolean circular = initial.kind() == BodyPattern.Kind.CIRCULAR;
        UiText.choices(
                axis,
                value -> circular
                        ? value.name()
                        : switch (value) {
                            case X -> "YZ";
                            case Y -> "XZ";
                            case Z -> "XY";
                        });
        grid.addRow(1, new Label(tr.translate(circular ? "pattern.axis" : "pattern.plane")), axis);
        var count = field("pattern-count", Integer.toString(initial.firstCount()));
        var rows = field("pattern-rows", Integer.toString(initial.secondCount()));
        var step = field("pattern-step", Double.toString(initial.firstStep()));
        var spacing = field("pattern-spacing", Double.toString(initial.secondStep()));
        if (rectangular) {
            var a = new Label();
            var b = new Label();
            var update = (Runnable) () -> {
                a.setText(axis.getValue() == BodyPattern.Axis.X ? "Y" : "X");
                b.setText(axis.getValue() == BodyPattern.Axis.Z ? "Y" : "Z");
            };
            update.run();
            axis.setOnAction(event -> update.run());
            grid.addRow(2, new Label(""), new Label(tr.translate("sketch.copy.count")), new Label("Δ (mm)"));
            grid.addRow(3, a, count, step);
            grid.addRow(4, b, rows, spacing);
        } else if (circular) {
            grid.addRow(2, new Label(tr.translate("sketch.copy.count")), count);
            grid.addRow(3, new Label(tr.translate("pattern.sweep") + " (°)"), step);
        } else {
            grid.addRow(2, new Label(tr.translate("gui.offset_mm") + " (mm)"), step);
        }
        var error = new Label();
        error.getStyleClass().add("error");
        grid.add(error, 0, 5, 3, 1);
        dialog.getDialogPane().setContent(grid);
        var apply = new ButtonType(tr.translate("gui.apply"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(apply);
        button.setId("pattern-apply");
        button.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                accept.accept(new BodyPattern(
                        initial.kind(),
                        body.getValue(),
                        axis.getValue(),
                        Integer.parseInt(count.getText().strip()),
                        Integer.parseInt(rows.getText().strip()),
                        Double.parseDouble(step.getText().strip()),
                        Double.parseDouble(spacing.getText().strip())));
            } catch (RuntimeException invalid) {
                error.setText(invalid.getMessage());
                event.consume();
            }
        });
        Dialogs.show(dialog, stage, tr);
    }

    private static TextField field(String id, String value) {
        var field = new TextField(value);
        field.setId(id);
        field.setPrefColumnCount(7);
        return field;
    }

    private BodyPatternDialog() {}
}
