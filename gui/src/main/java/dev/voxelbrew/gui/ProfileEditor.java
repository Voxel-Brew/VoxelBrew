package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.printing.PrintProfile.Point;
import dev.voxelbrew.api.printer.*;
import java.nio.file.Path;
import java.util.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

/** Edits custom hardware definitions; bundled definitions can only be copied. */
final class ProfileEditor {
    static Optional<Profile> show(
            Window owner, TranslationService text, PrinterProfileCatalog catalog, Profile source) {
        var dialog = new Dialog<Profile>();
        dialog.setTitle(source == null ? "New printer profile" : "Edit / copy printer profile");
        dialog.getDialogPane().setId("profile-editor");
        var fields = new LinkedHashMap<String, TextField>();
        var content = new VBox(8);
        content.setId("profile-editor");
        boolean bundled = source != null
                && catalog.load().entries().stream()
                        .anyMatch(e -> e.bundled() && e.definition().id().equals(source.id()));
        String[] keys = {"id", "name", "manufacturer", "height", "nozzle", "filament", "tools", "firmware", "format"};
        String[] values = source == null
                ? new String[] {
                    "custom-" + UUID.randomUUID(),
                    "New profile",
                    "Custom",
                    "200",
                    "0.4",
                    "1.75",
                    "1",
                    "marlin2",
                    "gcode"
                }
                : new String[] {
                    bundled ? "custom-" + source.id() : source.id(),
                    source.name(),
                    source.manufacturer(),
                    "" + source.usableHeight(),
                    "" + source.nozzleDiameter(),
                    "" + source.filamentDiameter(),
                    "" + source.toolCount(),
                    source.firmware(),
                    source.outputFormat()
                };
        String[] labels = {
            "Profile ID",
            "Name",
            "Manufacturer",
            "Usable height (mm)",
            "Nozzle diameter (mm)",
            "Filament diameter (mm)",
            "Tool count",
            "Firmware",
            "Output format"
        };
        for (int i = 0; i < keys.length; i++) {
            var input = new TextField(values[i]);
            input.setId("profile-" + keys[i]);
            fields.put(keys[i], input);
            content.getChildren().addAll(new Label(labels[i]), input);
        }
        var bed = new TextArea(source == null ? "0,0 200,0 200,200 0,200" : polygon(source.bed()));
        bed.setId("profile-bed");
        bed.setPrefRowCount(3);
        var exclusions = new TextArea(
                source == null
                        ? ""
                        : String.join(
                                "\n",
                                source.excludedZones().stream()
                                        .map(ProfileEditor::polygon)
                                        .toList()));
        exclusions.setId("profile-exclusions");
        exclusions.setPrefRowCount(3);
        var generic = new CheckBox("Supports generic G-code");
        generic.setId("profile-generic");
        generic.setSelected(source == null || source.genericGcode());
        var image = new TextField(
                source != null && source.imageReference() != null
                        ? source.imageReference().toString()
                        : "");
        image.setEditable(false);
        image.setId("profile-image");
        var chosen = new Path[1];
        var browse = new Button("Choose picture");
        browse.setId("profile-picture");
        browse.setOnAction(e -> {
            var chooser = new FileChooser();
            chooser.getExtensionFilters()
                    .add(new FileChooser.ExtensionFilter("Pictures", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
            var file = chooser.showOpenDialog(owner);
            if (file != null) {
                chosen[0] = file.toPath();
                image.setText(file.toString());
            }
        });
        var error = new Label();
        error.setWrapText(true);
        content.getChildren()
                .addAll(
                        new Label("Bed polygon: x,y points separated by spaces"),
                        bed,
                        new Label("Excluded polygons: one polygon per line"),
                        exclusions,
                        generic,
                        new HBox(8, image, browse),
                        error);
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(600);
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().setPrefWidth(620);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).setId("profile-save");
        var result = new Profile[1];
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                var zones = exclusions
                        .getText()
                        .lines()
                        .filter(line -> !line.isBlank())
                        .map(ProfileEditor::parse)
                        .toList();
                var profile = new Profile(
                        fields.get("id").getText(),
                        fields.get("name").getText(),
                        fields.get("manufacturer").getText(),
                        parse(bed.getText()),
                        zones,
                        Double.parseDouble(fields.get("height").getText()),
                        Double.parseDouble(fields.get("nozzle").getText()),
                        Double.parseDouble(fields.get("filament").getText()),
                        Integer.parseInt(fields.get("tools").getText()),
                        fields.get("firmware").getText(),
                        fields.get("format").getText(),
                        generic.isSelected(),
                        source == null ? null : source.imageReference());
                result[0] = catalog.save(profile, chosen[0]);
            } catch (Exception failure) {
                e.consume();
                error.setText(failure.getMessage());
            }
        });
        dialog.setResultConverter(button -> button == ButtonType.OK ? result[0] : null);
        return Dialogs.show(dialog, owner, text);
    }

    private static String polygon(List<Point> points) {
        return String.join(" ", points.stream().map(p -> p.x() + "," + p.y()).toList());
    }

    private static List<Point> parse(String text) {
        return Arrays.stream(text.strip().split("\\s+"))
                .map(pair -> {
                    var xy = pair.split(",");
                    if (xy.length != 2) throw new IllegalArgumentException("Use x,y polygon points");
                    return new Point(Double.parseDouble(xy[0]), Double.parseDouble(xy[1]));
                })
                .toList();
    }

    private ProfileEditor() {}
}
