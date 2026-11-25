package dev.voxelbrew.gui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** A wrapping two-column property sheet that follows the inspector divider. */
final class InspectorDetails extends GridPane {
    static VBox field(Label label, Node input) {
        label.getStyleClass().add("inspector-caption");
        label.setWrapText(true);
        if (input instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
        return new VBox(4, label, input);
    }

    InspectorDetails() {
        var caption = new ColumnConstraints();
        caption.setPercentWidth(40);
        var value = new ColumnConstraints();
        value.setPercentWidth(60);
        getColumnConstraints().addAll(caption, value);
        getStyleClass().add("inspector-details");
    }

    void row(String caption, String value) {
        var label = new Label(value);
        label.setWrapText(true);
        label.setMinWidth(0);
        row(caption, label);
    }

    void row(String caption, Node value) {
        var label = new Label(caption);
        label.setMinWidth(0);
        label.setWrapText(true);
        label.getStyleClass().add("inspector-caption");
        addRow(getRowCount(), label, value);
    }
}
