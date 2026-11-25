package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/** Window-local modal controls keep keyboard focus within the document on WSLg. */
final class Dialogs {
    private static final java.util.Map<ButtonType, String> BUTTON_LABELS = java.util.Map.of(
            ButtonType.OK,
            "dialog.ok",
            ButtonType.CANCEL,
            "action.cancel",
            ButtonType.APPLY,
            "dialog.apply",
            ButtonType.CLOSE,
            "dialog.close",
            ButtonType.YES,
            "dialog.yes",
            ButtonType.NO,
            "dialog.no");

    record CanvasPick(Button start, Node canvas, java.util.function.Predicate<javafx.geometry.Point2D> choose) {
        Runnable install(StackPane host, Region shade, VBox panel, Node returnFocus, TranslationService translations) {
            var back = new Button(translations.translate("action.cancel"));
            var bar = new HBox(10, new Label(start.getText()), back);
            bar.setAlignment(Pos.CENTER);
            bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            bar.getStyleClass().addAll(panel.getStyleClass());
            bar.getStylesheets().setAll(panel.getStylesheets());
            StackPane.setAlignment(bar, Pos.TOP_CENTER);
            Runnable restore = () -> {
                host.getChildren().remove(bar);
                shade.setOnMouseClicked(null);
                shade.setCursor(null);
                shade.setOpacity(1);
                panel.setVisible(true);
                returnFocus.requestFocus();
            };
            back.setOnAction(event -> restore.run());
            start.setOnAction(event -> {
                panel.setVisible(false);
                shade.setOpacity(0);
                shade.setCursor(javafx.scene.Cursor.CROSSHAIR);
                host.getChildren().add(bar);
                back.requestFocus();
                shade.setOnMouseClicked(click -> {
                    var point = canvas.screenToLocal(click.getScreenX(), click.getScreenY());
                    if (click.getButton() == javafx.scene.input.MouseButton.PRIMARY
                            && click.isStillSincePress()
                            && canvas.contains(point)
                            && choose.test(point)) restore.run();
                    click.consume();
                });
            });
            return restore;
        }
    }

    static Optional<ButtonType> showStandalone(Alert alert) {
        alert.getDialogPane()
                .getStylesheets()
                .add(Dialogs.class.getResource("workbench.css").toExternalForm());
        return alert.showAndWait();
    }

    /** Give the action its full translated label width; the editable path takes the remainder. */
    static HBox pathRow(TextField path, Button browse) {
        path.setMinWidth(0);
        path.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(path, Priority.ALWAYS);
        browse.setMinWidth(Region.USE_PREF_SIZE);
        var row = new HBox(8, path, browse);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    static <T> Optional<T> show(Dialog<T> dialog, Window owner, TranslationService translations) {
        return show(dialog, owner, translations, null);
    }

    @SuppressWarnings("unchecked") // Dialog's default result type is ButtonType, matching Alert.
    static <T> Optional<T> show(Dialog<T> dialog, Window owner, TranslationService translations, CanvasPick picker) {
        var scene = owner.getScene();
        var content = WindowFrame.content(scene);
        var previousFocus = scene.getFocusOwner();
        boolean disabled = content.isDisable();
        var previousClose = owner.getOnCloseRequest();
        var pane = dialog.getDialogPane();
        var converter = dialog.getResultConverter();
        var buttons = translatedButtons(pane, translations);
        Node body = detachBody(pane);
        var title = new Label(pane.getHeaderText() == null ? dialog.getTitle() : pane.getHeaderText());
        title.getStyleClass().add("selection-title");
        var actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getChildren().addAll(buttons.values());
        var panel = new VBox(16, title, body, actions);
        panel.getStyleClass().add("dialog-overlay");
        panel.getStylesheets().setAll(content.getStylesheets());
        panel.getStyleClass().addAll(content.getStyleClass());
        double preferredWidth = pane.getPrefWidth() > 0 ? pane.getPrefWidth() : 650;
        panel.setPrefWidth(Math.min(preferredWidth, scene.getWidth() - 64));
        panel.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        var shade = new Region();
        shade.setStyle("-fx-background-color: rgba(0,0,0,0.38);");
        var host = new StackPane();
        WindowFrame.content(scene, host);
        host.getChildren().addAll(content, shade, panel);
        content.setDisable(true);

        Object loop = new Object();
        boolean[] completed = {false};
        Consumer<ButtonType> finish = button -> {
            if (completed[0]) return;
            T result = dialogResult(button, converter);
            completed[0] = true;
            Platform.exitNestedEventLoop(loop, result);
        };
        ButtonBase cancel = null, initial = null;
        for (var entry : buttons.entrySet()) {
            var type = entry.getKey();
            var button = entry.getValue();
            // Existing action filters still validate inputs before this handler runs.
            button.setOnAction(e -> finish.accept(type));
            if (type.getButtonData().isDefaultButton()) initial = button;
            if (type.getButtonData().isCancelButton() || type.getButtonData() == ButtonBar.ButtonData.NO) {
                cancel = button;
            }
        }
        ButtonBase cancelButton = cancel;
        Node initialFocus = focusTarget(initial, cancel, panel);
        Runnable stopPicking = picker == null ? null : picker.install(host, shade, panel, initialFocus, translations);
        EventHandler<KeyEvent> escape = modalKeys(host, panel, stopPicking, cancelButton);
        // MenuBarSkin retains a scene-level Escape filter even when its workbench is
        // disabled behind this overlay. Only the topmost modal owns this window filter.
        owner.addEventFilter(KeyEvent.KEY_PRESSED, escape);
        owner.setOnCloseRequest(e -> {
            e.consume();
            if (cancelButton != null) cancelButton.fire();
        });
        EventHandler<WindowEvent> hidden = e -> finish.accept(null);
        owner.addEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
        initialFocus.requestFocus();
        // A newly attached overlay may not yet be focusable until its first scene pulse.
        Platform.runLater(() -> {
            if (!completed[0] && !panel.isDisabled()) initialFocus.requestFocus();
        });
        try {
            return Optional.ofNullable((T) Platform.enterNestedEventLoop(loop));
        } finally {
            owner.removeEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
            owner.setOnCloseRequest(previousClose);
            owner.removeEventFilter(KeyEvent.KEY_PRESSED, escape);
            host.getChildren().clear();
            WindowFrame.content(scene, content);
            content.setDisable(disabled);
            if (previousFocus != null) previousFocus.requestFocus();
        }
    }

    private static EventHandler<KeyEvent> modalKeys(
            StackPane host, VBox panel, Runnable stopPicking, ButtonBase cancel) {
        return event -> {
            if (host.getScene() == null || WindowFrame.content(host.getScene()) != host) return;
            if (stopPicking != null && !panel.isVisible()) {
                event.consume();
                if (event.getCode() == KeyCode.ESCAPE || event.getCode() == KeyCode.ENTER) stopPicking.run();
            } else if (event.getCode() == KeyCode.ESCAPE && cancel != null) {
                event.consume();
                cancel.fire();
            }
        };
    }

    private static LinkedHashMap<ButtonType, ButtonBase> translatedButtons(
            DialogPane pane, dev.voxelbrew.api.i18n.TranslationService translations) {
        var buttons = new LinkedHashMap<ButtonType, ButtonBase>();
        for (var type : pane.getButtonTypes()) {
            buttons.put(type, (ButtonBase) pane.lookupButton(type));
            String key = BUTTON_LABELS.get(type);
            if (key != null) {
                buttons.get(type).setText(translations.translate(key));
            }
            buttons.get(type).setMinWidth(Region.USE_PREF_SIZE);
            UiHelp.dialogButton(buttons.get(type), type, translations);
        }
        return buttons;
    }

    @SuppressWarnings("unchecked") // JavaFX uses ButtonType when a dialog has no converter.
    private static <T> T dialogResult(ButtonType button, javafx.util.Callback<ButtonType, T> converter) {
        if (button == null) return null;
        return converter == null ? (T) button : converter.call(button);
    }

    private static Node focusTarget(Node initial, Node cancel, Node panel) {
        if (initial != null) return initial;
        return cancel != null ? cancel : panel;
    }

    private static Node detachBody(DialogPane pane) {
        Node body = pane.getContent();
        pane.setContent(null);
        pane.getButtonTypes().clear();
        if (body == null) {
            var label = new Label(pane.getContentText());
            label.setWrapText(true);
            body = label;
        }
        return body;
    }

    private Dialogs() {}
}
