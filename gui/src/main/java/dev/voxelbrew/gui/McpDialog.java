package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.userconfig.UserConfigService;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

final class McpDialog {
    static void show(Window owner, McpServerHost server, UserConfigService store, TranslationService text) {
        var dialog = new Dialog<Void>();
        dialog.initOwner(owner);
        dialog.setTitle(text.translate("mcp.title").orElseThrow());
        var auto = new CheckBox(text.translate("mcp.autoStart").orElseThrow());
        var port = new TextField("0");
        var connections = new TextField("4");
        var idle = new TextField("30");
        var history = new TextField("100");
        var endpoint = new TextField(server.endpoint());
        endpoint.setEditable(false);
        var token = new PasswordField();
        token.setText(server.credential());
        token.setEditable(false);
        var status = new Label();
        var activity = new TextArea(server.activity().toString());
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
        var start = new Button(text.translate("mcp.apply").orElseThrow());
        start.setOnAction(event -> {
            try {
                var settings = new McpSettings(
                        auto.isSelected(),
                        Integer.parseInt(port.getText()),
                        Integer.parseInt(connections.getText()),
                        Integer.parseInt(idle.getText()),
                        Integer.parseInt(history.getText()));
                settings.save(store);
                server.start(settings);
                endpoint.setText(server.endpoint());
                status.setText(server.endpoint());
            } catch (Exception failure) {
                status.setText(failure.getMessage());
            }
        });
        var stop = new Button(text.translate("mcp.stop").orElseThrow());
        stop.setOnAction(event -> {
            server.close();
            endpoint.clear();
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
        });
        dialog.getDialogPane()
                .setContent(new VBox(
                        8,
                        auto,
                        new Label(text.translate("mcp.port").orElseThrow()),
                        port,
                        new Label(text.translate("mcp.connections").orElseThrow()),
                        connections,
                        new Label(text.translate("mcp.idle").orElseThrow()),
                        idle,
                        new Label(text.translate("mcp.history").orElseThrow()),
                        history,
                        endpoint,
                        token,
                        start,
                        stop,
                        copy,
                        rotate,
                        status,
                        activity));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private McpDialog() {}
}
