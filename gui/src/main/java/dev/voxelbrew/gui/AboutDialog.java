package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.util.Version;
import dev.voxelbrew.util.update.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;

/** An explicit, window-local release lookup. Closing it cancels work and rejects late results. */
@Slf4j
final class AboutDialog {
    private final UpdateServiceFactory factory;

    private final TranslationService translations;

    private final ExecutorService worker;

    private final UpdateCancellation cancellation = new UpdateCancellation();

    private final Dialog<ButtonType> dialog = new Dialog<>();

    private final Label status = new Label();

    private final Button check;

    private final Hyperlink updates, license, notices;

    private final ProgressIndicator progress = new ProgressIndicator();

    private final Button close;

    private boolean closed, openUpdates;

    private static Label label(String text, String id) {
        var label = new Label(text);
        label.setId(id);
        label.setWrapText(true);
        return label;
    }

    AboutDialog(UpdateServiceFactory factory, TranslationService translations, Supplier<ExecutorService> workers) {
        this.factory = factory;
        this.translations = translations;
        dialog.setTitle(translations.translate("about.title").orElseThrow());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        close = (Button) dialog.getDialogPane().lookupButton(ButtonType.CLOSE);
        close.setId("about-close");
        var product = new Label("VoxelBrew CAD");
        product.getStyleClass().add("selection-title");
        var version =
                label(translations.translate("about.version", Version.version()).orElseThrow(), "about-version");
        status.setId("about-status");
        status.setWrapText(true);
        progress.setMaxSize(20, 20);
        progress.setMinSize(20, 20);
        progress.managedProperty().bind(progress.visibleProperty());
        check = new Button(translations.translate("about.check").orElseThrow());
        check.setId("about-check");
        check.setMinWidth(Region.USE_PREF_SIZE);
        UiHelp.install(check, translations);
        check.setOnAction(event -> check());
        updates = link("about.updates", "about-updates");
        updates.setOnAction(event -> {
            openUpdates = true;
            close.fire();
        });
        license = link("about.license", "about-license");
        notices = link("about.notices", "about-notices");
        var releaseStatus = new HBox(10, progress, status);
        releaseStatus.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(status, Priority.ALWAYS);
        var content = new VBox(
                12,
                product,
                version,
                label(translations.translate("about.description").orElseThrow(), "about-description"),
                new Separator(),
                releaseStatus,
                check,
                updates,
                new Separator(),
                SupportDialog.banners(),
                new Separator(),
                license,
                notices);
        content.getStyleClass().add("about-content");
        content.setPrefWidth(380);
        dialog.getDialogPane().setContent(content);
        worker = workers.get();
    }

    boolean show(Window owner) {
        log.info("Opening About for VoxelBrew {}", Version.version());
        license.setOnAction(event -> showLegal(owner, "LICENSE", license.getText()));
        notices.setOnAction(event -> showLegal(owner, "THIRD_PARTY_NOTICES.md", notices.getText()));
        try {
            check();
            Dialogs.show(dialog, owner, translations);
            return openUpdates;
        } finally {
            closed = true;
            cancellation.cancel();
            worker.shutdownNow();
            log.debug("Closed About and cancelled its release lookup");
        }
    }

    private void check() {
        check.setDisable(true);
        progress.setVisible(true);
        status.setText(translations.translate("about.checking").orElseThrow());
        worker.execute(() -> {
            try {
                var result = factory.create().check(new UpdateService.Request(null, false), cancellation);
                Platform.runLater(() -> {
                    if (closed) return;
                    String message = result.available()
                            ? translations
                                    .translate(
                                            "about.available", result.release().version())
                                    .orElseThrow()
                            : translations
                                    .translate("about.current", result.release().version())
                                    .orElseThrow();
                    if (!result.installable())
                        message += "\n"
                                + translations.translate("about.development").orElseThrow();
                    status.setText(message);
                    finished();
                });
            } catch (Exception failure) {
                if (failure instanceof CancellationException
                        || Thread.currentThread().isInterrupted()) log.debug("About release lookup cancelled");
                else log.warn("About release lookup failed", failure);
                Platform.runLater(() -> {
                    if (closed) return;
                    status.setText(translations.translate("about.failed").orElseThrow());
                    finished();
                });
            }
        });
    }

    private void finished() {
        progress.setVisible(false);
        check.setDisable(false);
    }

    private void showLegal(Window owner, String resource, String title) {
        try (var input = AboutDialog.class.getResourceAsStream("legal/" + resource)) {
            if (input == null) throw new IOException("Missing legal resource: " + resource);
            var text = new TextArea(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            text.setId("about-legal-text");
            text.setEditable(false);
            text.setWrapText(true);
            text.setPrefHeight(300);
            text.setAccessibleText(title);
            var legal = new Dialog<ButtonType>();
            legal.setTitle(title);
            legal.getDialogPane().setContent(text);
            legal.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            legal.getDialogPane().lookupButton(ButtonType.CLOSE).setId("about-legal-close");
            Dialogs.show(legal, owner, translations);
        } catch (IOException failure) {
            log.error("Cannot read bundled legal information {}", resource, failure);
            status.setText(translations.translate("about.legalFailed").orElseThrow());
        }
    }

    private Hyperlink link(String key, String id) {
        var link = new Hyperlink(translations.translate(key).orElseThrow());
        link.setId(id);
        link.setWrapText(true);
        UiHelp.install(link, translations);
        return link;
    }
}
