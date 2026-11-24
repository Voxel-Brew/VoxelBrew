package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.util.di.ApplicationModule;
import dev.voxelbrew.util.update.ApplicationImage;
import dev.voxelbrew.util.update.UpdateCancellation;
import dev.voxelbrew.util.update.UpdateService;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Screen;
import javafx.stage.Stage;

@lombok.extern.slf4j.Slf4j
public final class WorkbenchApplication extends Application {
    private Workbench workbench;

    static Workbench openWindow(Stage stage, Path plugins, Path open) throws java.io.IOException {
        return openWindow(stage, plugins, open, ApplicationModule.system(ApplicationImage.Component.GUI));
    }

    private static Workbench openWindow(Stage stage, Path plugins, Path open, ApplicationModule application)
            throws java.io.IOException {
        var workbench = Workbench.create(stage, plugins, application, new FileIoModule());
        var screen = Screen.getPrimary().getVisualBounds();
        stage.setScene(
                workbench.createScene(Math.min(1400, screen.getWidth() - 60), Math.min(900, screen.getHeight() - 80)));
        stage.setMinWidth(Math.min(1100, screen.getWidth() - 60));
        stage.setMinHeight(Math.min(700, screen.getHeight() - 80));
        stage.show();
        log.info("Workbench window opened with plugin directory {}", plugins);
        if (open != null) workbench.openWithRecovery(open);
        stage.setOnHidden(event -> workbench.close());
        return workbench;
    }

    private static java.util.List<String> launchArguments(java.util.List<String> args) {
        if (args.size() == 1
                && args.getFirst().toLowerCase(java.util.Locale.ROOT).endsWith(".vxb")) {
            return java.util.List.of("--open", args.getFirst());
        }
        return args;
    }

    @Override
    public void start(Stage stage) throws java.io.IOException {
        var application = ApplicationModule.system(ApplicationImage.Component.GUI);
        var translations = application.translations();
        if (!Platform.isSupported(ConditionalFeature.SCENE3D)) {
            throw new IllegalStateException(translations.translate("graphics.unavailable"));
        }
        var args = launchArguments(getParameters().getRaw());
        Path
                plugins =
                        dev.voxelbrew.util.services.Services.load(dev.voxelbrew.api.userconfig.UserConfigService.class)
                                .pluginsFolder(),
                open = null;
        Path smokeOutput = Path.of(System.getProperty("voxelbrew.smoke.output", "build/gui-smoke.png"));
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--plugin-dir") && i + 1 < args.size()) plugins = Path.of(args.get(++i));
            else if (args.get(i).equals("--open") && i + 1 < args.size()) {
                open = Path.of(args.get(++i));
            } else if (args.get(i).equals("--smoke-output") && i + 1 < args.size()) {
                smokeOutput = Path.of(args.get(++i));
            } else if (!args.get(i).equals("--smoke-test")) {
                throw new IllegalArgumentException(
                        "Usage: gui [design.vxb | --open design.vxb] [--plugin-dir directory] [--smoke-test] [--smoke-output screenshot.png]");
            }
        }
        if (open != null && args.contains("--smoke-test")) {
            throw new IllegalArgumentException("--smoke-test creates an unsaved fixture; omit --open");
        }
        workbench = openWindow(stage, plugins, open, application);
        if (open == null && !args.contains("--smoke-test")) workbench.openWithRecovery(null);
        enableFinderOpen(stage, plugins);
        workbench.startMcpIfConfigured();
        checkForUpdates(stage, translations);
        if (args.contains("--smoke-test")) {
            var controller = workbench.controller();
            controller.add(new Feature.Sketch(
                    "plate", "plate.profile", new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 60, 40)));
            controller.add(new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "plate.profile"), 8));
            controller.add(new Feature.Sketch(
                    "hole", "hole.profile", new Feature.Plane("origin-xy", 0), new Feature.Circle(10, 10, 4)));
            controller.add(new Feature.ThroughCut(
                    "cut", new Feature.ProfileRef("hole", "hole.profile"), new Feature.BodyRef("thickness", "body")));
            PackageSmoke.verify(workbench, stage, smokeOutput);
        }
    }

    private void enableFinderOpen(Stage stage, Path plugins) {
        if (!System.getProperty("os.name").startsWith("Mac")) return;
        MacDocumentEvents.install(path -> {
            var controller = workbench.controller();
            if (stage.isShowing() && controller.path() == null && !controller.dirty() && !controller.ioBusy()) {
                controller.open(path).exceptionally(failure -> null);
                stage.toFront();
            } else {
                try {
                    openWindow(new Stage(), plugins, path);
                } catch (java.io.IOException failure) {
                    log.error("Cannot open window for {}", path, failure);
                }
            }
        });
    }

    private void checkForUpdates(Stage stage, TranslationService translations) {
        WindowResources.commonExecutor().execute(() -> {
            try {
                var result = UpdateService.system(ApplicationImage.Component.GUI)
                        .check(new UpdateService.Request(null, false), new UpdateCancellation());
                if (!result.available() || !result.installable()) return;
                Platform.runLater(() -> {
                    if (!stage.isShowing()) return;
                    var download = new ButtonType(translations.translate("update.download"));
                    var prompt = new Alert(Alert.AlertType.INFORMATION, result.message(), download, ButtonType.CANCEL);
                    prompt.setTitle(translations.translate("update.title"));
                    prompt.setHeaderText(translations.translate("update.heading"));
                    if (prompt.showAndWait().filter(download::equals).isPresent() && stage.isShowing()) {
                        workbench.openUpdates();
                    }
                });
            } catch (Exception failure) {
                log.warn("Startup update check failed", failure);
            }
        });
    }

    @Override
    public void stop() {
        log.info("Closing workbench application");
        if (workbench != null) workbench.close();
    }
}
