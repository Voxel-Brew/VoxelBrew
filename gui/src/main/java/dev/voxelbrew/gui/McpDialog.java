package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.userconfig.UserConfigService;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

final class McpDialog {
    static void show(Window owner, McpServerHost server, UserConfigService store, TranslationService text) {
        var dialog = new Dialog<Void>();
        dialog.getDialogPane().setPrefWidth(600);
        dialog.setTitle(text.translate("mcp.title").orElseThrow());
        var auto = new CheckBox(text.translate("mcp.autoStart").orElseThrow());
        var port = new TextField("0");
        var connections = new TextField("4");
        var idle = new TextField("30");
        var history = new TextField("100");
        var endpoint = new TextField(server.endpoint());
        endpoint.setEditable(false);
        var token = new RevealPasswordField("mcp-token", server.credential(), false, text);
        var status = new Label();
        status.setWrapText(true);
        var activity = new TextArea(
                server.activity().stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n")));
        activity.visibleProperty().bind(activity.textProperty().isNotEmpty());
        activity.managedProperty().bind(activity.visibleProperty());
        activity.setEditable(false);
        activity.setPrefRowCount(3);
        try {
            var settings = McpSettings.load(store);
            auto.setSelected(settings.autoStart());
            port.setText(Integer.toString(settings.port()));
            connections.setText(Integer.toString(settings.maxConnections()));
            idle.setText(Integer.toString(settings.idleTimeoutMinutes()));
            history.setText(Integer.toString(settings.activityHistorySize()));
        } catch (Exception failure) {
            status.setText(failure.getMessage());
        }
        var apply = new ButtonType(text.translate("mcp.apply").orElseThrow(), ButtonBar.ButtonData.APPLY);
        dialog.getDialogPane().getButtonTypes().setAll(apply, ButtonType.CLOSE);
        var start = (Button) dialog.getDialogPane().lookupButton(apply);
        start.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            try {
                var settings = new McpSettings(
                        auto.isSelected(),
                        Integer.parseInt(port.getText()),
                        Integer.parseInt(connections.getText()),
                        Integer.parseInt(idle.getText()),
                        Integer.parseInt(history.getText()));
                settings.save(store);
                server.start(settings);
                status.setText("");
            } catch (Exception failure) {
                status.setText(failure.getMessage());
            } finally {
                endpoint.setText(server.endpoint());
            }
        });
        var stop = new Button(text.translate("mcp.stop").orElseThrow());
        stop.setOnAction(event -> {
            server.close();
            endpoint.clear();
            status.setText("");
        });
        var copy = new Button(text.translate("mcp.copy").orElseThrow());
        copy.setOnAction(event -> {
            var clipboard = new ClipboardContent();
            clipboard.putString("{\"url\":\"" + server.endpoint() + "\",\"headers\":{\"Authorization\":\"Bearer "
                    + server.credential() + "\"}}");
            Clipboard.getSystemClipboard().setContent(clipboard);
        });
        var rotate = new Button(text.translate("mcp.rotate").orElseThrow());
        rotate.setOnAction(event -> {
            server.regenerateToken();
            token.setText(server.credential());
            endpoint.clear();
            status.setText("");
        });
        copy.disableProperty().bind(endpoint.textProperty().isEmpty());
        stop.disableProperty().bind(endpoint.textProperty().isEmpty());
        var settings = new GridPane();
        settings.setHgap(12);
        settings.setVgap(8);
        settings.addRow(0, new Label(text.translate("mcp.port").orElseThrow()), port);
        settings.addRow(1, new Label(text.translate("mcp.connections").orElseThrow()), connections);
        settings.addRow(2, new Label(text.translate("mcp.idle").orElseThrow()), idle);
        settings.addRow(3, new Label(text.translate("mcp.history").orElseThrow()), history);
        settings.addRow(4, new Label(text.translate("mcp.endpoint").orElseThrow()), endpoint);
        settings.addRow(5, new Label(text.translate("mcp.token").orElseThrow()), token);
        GridPane.setHgrow(token, Priority.ALWAYS);
        status.visibleProperty().bind(status.textProperty().isNotEmpty());
        status.managedProperty().bind(status.visibleProperty());
        dialog.getDialogPane()
                .setContent(new VBox(12, auto, settings, new HBox(8, copy, rotate, stop), status, activity));
        Dialogs.show(dialog, owner, text);
    }

    private McpDialog() {}
}
