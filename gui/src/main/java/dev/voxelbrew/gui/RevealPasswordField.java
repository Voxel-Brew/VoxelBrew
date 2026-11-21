package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** A masked input with a trailing visibility button and one synchronized value. */
final class RevealPasswordField extends StackPane {
    private final PasswordField masked = new PasswordField();

    RevealPasswordField(String id, String value, boolean editable, TranslationService translations) {
        var visible = new TextField();
        masked.setId(id);
        masked.setText(value);
        masked.setEditable(editable);
        visible.setId(id + "-visible");
        visible.setEditable(editable);
        visible.textProperty().bindBidirectional(masked.textProperty());
        for (var field : java.util.List.of(masked, visible)) {
            field.setMinWidth(0);
            field.setMaxWidth(Double.MAX_VALUE);
            field.accessibleHelpProperty().bind(accessibleHelpProperty());
        }
        getStyleClass().add("reveal-password");
        setMinWidth(0);
        var eye = new SVGPath();
        eye.getStyleClass().add("password-eye");
        eye.setMouseTransparent(true);
        var toggle = new Button();
        toggle.setId(id + "-toggle");
        toggle.getStyleClass().add("password-toggle");
        toggle.setGraphic(eye);
        toggle.setMnemonicParsing(false);
        StackPane.setAlignment(toggle, Pos.CENTER_RIGHT);
        getChildren().setAll(masked, toggle);
        updateToggle(toggle, eye, false, translations);
        toggle.setOnAction(event -> {
            boolean revealed = getChildren().getFirst() == masked;
            TextField previous = revealed ? masked : visible;
            TextField next = revealed ? visible : masked;
            getChildren().set(0, next);
            next.selectRange(previous.getAnchor(), previous.getCaretPosition());
            updateToggle(toggle, eye, revealed, translations);
        });
    }

    StringProperty textProperty() {
        return masked.textProperty();
    }

    String getText() {
        return masked.getText();
    }

    void setText(String value) {
        masked.setText(value);
    }

    @Override
    public void requestFocus() {
        getChildren().getFirst().requestFocus();
    }

    private void updateToggle(Button toggle, SVGPath eye, boolean revealed, TranslationService translations) {
        var title = translations
                .translate(revealed ? "password.hide" : "password.show")
                .orElseThrow();
        toggle.setAccessibleText(title);
        UiHelp.describe(toggle, title);
        eye.setContent("M1 8 Q8 -1 15 8 Q8 17 1 8 Z M10.5 8 A2.5 2.5 0 1 0 5.5 8 A2.5 2.5 0 1 0 10.5 8 Z"
                + (revealed ? " M2 2 L14 14" : ""));
    }
}
