package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.util.update.*;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Explicit updater. All network, archive and helper work stays off the JavaFX thread. */
@lombok.extern.slf4j.Slf4j
final class UpdateDialog {
    private final UpdateServiceFactory factory;

    private final TranslationService translations;

    private final Consumer<Runnable> beforeInstall;

    private final ExecutorService worker;

    private final UpdateCancellation cancellation = new UpdateCancellation();

    private final Dialog<ButtonType> dialog = new Dialog<>();

    private final CheckBox force;

    private final Label status;

    private final ProgressIndicator progress = new ProgressIndicator();

    private final Button check, download;
    private final Button install, cancel;

    private UpdateService service;

    private UpdateService.Check selected;

    private UpdateService.Prepared prepared;

    private boolean busy, closed, scheduled;

    interface Factory {
        UpdateDialog create(Consumer<Runnable> beforeInstall);
    }

    static boolean show(Window owner, Factory factory, Consumer<Runnable> beforeInstall) {
        var updates = factory.create(beforeInstall);
        try {
            Dialogs.show(updates.dialog, owner, updates.translations);
            return updates.scheduled;
        } finally {
            updates.closed = true;
            updates.cancellation.cancel();
            var prepared = updates.prepared;
            if (prepared != null) updates.worker.execute(() -> cleanup(prepared));
            updates.worker.shutdown();
        }
    }

    private static void cleanup(UpdateService.Prepared prepared) {
        try {
            prepared.close();
        } catch (IOException failure) {
            log.warn("Update staging cleanup failed", failure);
        }
    }

    UpdateDialog(
            UpdateServiceFactory factory,
            TranslationService translations,
            java.util.function.Supplier<ExecutorService> workers,
            Consumer<Runnable> beforeInstall) {
        this.factory = factory;
        this.translations = translations;
        this.beforeInstall = beforeInstall;
        this.worker = workers.get();
        this.force = new CheckBox(translations.translate("update.reinstall").orElseThrow());
        this.status = new Label();
        var checkType =
                new ButtonType(translations.translate("update.check").orElseThrow(), ButtonBar.ButtonData.OK_DONE);
        var downloadType =
                new ButtonType(translations.translate("update.download").orElseThrow(), ButtonBar.ButtonData.APPLY);
        var installType =
                new ButtonType(translations.translate("update.install").orElseThrow(), ButtonBar.ButtonData.APPLY);
        dialog.setTitle(translations.translate("update.title").orElseThrow());
        dialog.setHeaderText(translations.translate("update.heading").orElseThrow());
        dialog.getDialogPane().setPrefWidth(480);
        dialog.getDialogPane().getButtonTypes().setAll(checkType, downloadType, installType, ButtonType.CANCEL);
        check = (Button) dialog.getDialogPane().lookupButton(checkType);
        download = (Button) dialog.getDialogPane().lookupButton(downloadType);
        install = (Button) dialog.getDialogPane().lookupButton(installType);
        cancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        install.setId("update-install");
        cancel.setId("update-cancel");
        cancel.setText(translations.translate("action.cancel"));
        check.setId("update-check");
        download.setId("update-download");
        force.setId("update-force");
        UiHelp.install(force, translations);
        for (var button : java.util.List.of(check, download, install)) {
            button.getStyleClass().add("update-action");
            button.managedProperty().bind(button.visibleProperty());
        }
        status.setId("update-status");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);
        progress.setMaxSize(20, 20);
        progress.setMinSize(20, 20);
        progress.managedProperty().bind(progress.visibleProperty());
        var feedback = new HBox(10, progress, status);
        feedback.setAlignment(Pos.CENTER_LEFT);
        feedback.getStyleClass().add("update-feedback");
        feedback.visibleProperty().bind(status.textProperty().isNotEmpty());
        feedback.managedProperty().bind(feedback.visibleProperty());
        var installed = new Label(translations
                .translate("update.installed", BuildVersion.current())
                .orElseThrow());
        installed.getStyleClass().add("update-installed");
        dialog.getDialogPane().setContent(new VBox(14, installed, force, feedback));
        busy(false);
        force.selectedProperty().addListener((observable, old, value) -> invalidateSelection());
        check.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            check();
        });
        download.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            download();
        });
        install.addEventFilter(ActionEvent.ACTION, e -> {
            if (scheduled) return;
            e.consume();
            if (!busy && prepared != null) {
                beforeInstall.accept(() -> {
                    if (!closed && !busy) handOff();
                });
            }
        });
    }

    private void invalidateSelection() {
        selected = null;
        status.setText("");
        busy(false);
    }

    private void busy(boolean value) {
        busy = value;
        boolean available = selected != null && selected.available() && selected.installable();
        boolean ready = prepared != null;
        progress.setVisible(value);
        force.setDisable(value || ready);
        check.setVisible(!ready);
        check.setDisable(value);
        download.setVisible(available && !ready);
        download.setDisable(value);
        install.setVisible(ready);
        install.setDisable(value);
        check.setDefaultButton(!available && !ready);
        download.setDefaultButton(available && !ready);
        install.setDefaultButton(ready);
        var primary = javafx.css.PseudoClass.getPseudoClass("recommended");
        check.pseudoClassStateChanged(primary, !available);
        download.pseudoClassStateChanged(primary, true);
        install.pseudoClassStateChanged(primary, true);
    }

    private void check() {
        var request = new UpdateService.Request(null, force.isSelected());
        selected = null;
        busy(true);
        status.setText(translations.translate("update.checking"));
        worker.execute(() -> {
            try {
                var created = factory.create();
                var result = created.check(request, cancellation);
                Platform.runLater(() -> {
                    if (closed) return;
                    service = created;
                    selected = result;
                    status.setText(result.message());
                    busy(false);
                });
            } catch (Exception failure) {
                failed(failure);
            }
        });
    }

    private void download() {
        var selection = selected;
        var updater = service;
        busy(true);
        worker.execute(() -> {
            try {
                var ready = updater.prepare(
                        selection,
                        cancellation,
                        message -> Platform.runLater(() -> {
                            if (!closed) status.setText(message);
                        }));
                Platform.runLater(() -> {
                    if (closed) {
                        Thread.startVirtualThread(() -> cleanup(ready));
                        return;
                    }
                    prepared = ready;
                    status.setText(
                            translations.translate(
                                    "gui.verified_and_ready_install_and_quit_will_close_this_application_and_retain_the_previous_install"));
                    busy(false);
                });
            } catch (Exception failure) {
                failed(failure);
            }
        });
    }

    private void handOff() {
        busy(true);
        cancel.setDisable(true);
        status.setText(translations.translate("update.starting"));
        worker.execute(() -> {
            try {
                prepared.handOff();
                Platform.runLater(() -> {
                    scheduled = true;
                    install.setDisable(false);
                    install.fire();
                });
            } catch (Exception failure) {
                var failed = prepared;
                prepared = null;
                cleanup(failed);
                failed(failure);
            }
        });
    }

    private void failed(Exception failure) {
        if (failure instanceof CancellationException) log.debug("Update operation cancelled");
        else {
            log.error("Update operation failed", failure);
        }
        Platform.runLater(() -> {
            if (closed) return;
            status.setText(
                    failure instanceof CancellationException
                            ? translations.translate("gui.update_cancelled")
                            : translations.translate("update.failed", failure.getMessage()));
            cancel.setDisable(false);
            busy(false);
        });
    }
}
