package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.Feature.BodyRef;
import java.util.*;
import java.util.function.Supplier;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Additional target slots keep their output numbers when an earlier cut is edited. */
final class AdditionalCutTargets extends VBox {
    private final List<ComboBox<BodyRef>> slots = new ArrayList<>();
    private final VBox rows = new VBox(4);
    private final Button add;
    private final Button remove;
    private final Map<BodyRef, String> choices;
    private Runnable changed = () -> {};

    AdditionalCutTargets(
            Map<BodyRef, String> choices, Supplier<BodyRef> first, List<BodyRef> initial, TranslationService tr) {
        super(4);
        this.choices = new LinkedHashMap<>(choices);
        for (var ref : initial) this.choices.putIfAbsent(ref, ref.featureId() + "." + ref.outputName());
        add = new Button(tr.translate("gui.add"));
        remove = new Button(tr.translate("gui.remove"));
        add.setId("add-cut-target");
        remove.setId("remove-cut-target");
        UiHelp.install(add, tr);
        UiHelp.install(remove, tr);
        add.setOnAction(event -> this.choices.keySet().stream()
                .filter(ref -> !ref.equals(first.get()) && !selected().contains(ref))
                .findFirst()
                .ifPresent(this::append));
        remove.setOnAction(event -> {
            rows.getChildren().remove(slots.removeLast());
            update();
        });
        getChildren().addAll(rows, new HBox(4, add, remove));
        initial.forEach(this::append);
        update();
        managedProperty().bind(visibleProperty());
    }

    private void append(BodyRef ref) {
        var choice = new ComboBox<BodyRef>();
        choice.setId("cut-target-" + (slots.size() + 2));
        choice.getItems().setAll(choices.keySet());
        UiText.choices(choice, choices::get);
        choice.setValue(ref);
        choice.setOnAction(event -> changed.run());
        slots.add(choice);
        rows.getChildren().add(choice);
        update();
    }

    private void update() {
        add.setDisable(slots.size() + 1 >= choices.size());
        remove.setDisable(slots.isEmpty());
        changed.run();
    }

    void onChange(Runnable callback) {
        changed = callback;
    }

    List<BodyRef> selected() {
        if (!isVisible()) return List.of();
        return slots.stream().map(ComboBox::getValue).toList();
    }
}
