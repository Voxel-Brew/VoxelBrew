package dev.voxelbrew.gui;

import dev.voxelbrew.api.printer.PrinterProfileCatalog;
import dev.voxelbrew.api.printer.PrinterStore;
import dev.voxelbrew.engine.feature.FeatureRegistry;
import dev.voxelbrew.util.di.ApplicationModule;
import dev.voxelbrew.util.i18n.Translations;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.stage.Stage;

/** Service-discovered factory; each invocation owns an independent constructor graph. */
public final class DefaultGuiFactory implements GuiComponent.Factory {
    private static final class Scope implements GuiComponent {
        private final ApplicationModule application;
        private final FileIoModule files;

        private final Translations translations;

        private final Stage stage;

        private final Path plugins;
        private PrinterStore printerRuntime;
        private PrinterProfileCatalog profiles;
        private DocumentController documentController;
        private WindowResources windowResources;
        private McpAutomation mcpAutomation;
        private McpServerHost mcpServerHost;
        private PrinterVisibility printerVisibility;
        private PrintController printController;
        private SlicerSettings slicerSettings;
        private PrintingPane printingPane;
        private PrinterPane printerPane;
        private Appearance appearance;
        private Rendering rendering;
        private RecentFiles recentFiles;
        private Workbench workbench;

        Scope(Stage stage, Path plugins, ApplicationModule application, FileIoModule files) {
            this.application = application;
            this.files = files;
            this.translations = new Translations(application.configuration(), application.paths());
            this.stage = stage;
            this.plugins = plugins;
        }

        private PrinterStore printerRuntime() {
            if (printerRuntime == null)
                printerRuntime = new dev.voxelbrew.printer.FilePrinterStore(application.configuration());
            return printerRuntime;
        }

        private PrinterProfileCatalog profiles() {
            if (profiles == null)
                profiles = new dev.voxelbrew.printer.DefaultPrinterProfileCatalog(
                        application.paths().profiles());
            return profiles;
        }

        private DocumentController documentController() {
            if (documentController == null)
                documentController = new DocumentController(
                        Platform::runLater,
                        plugins,
                        resources().rebuild,
                        resources().files,
                        files.source(),
                        files.sink());
            return documentController;
        }

        private WindowResources windowResources() {
            if (windowResources == null)
                windowResources = new WindowResources(plugins, files.exporters(), files.slicers());
            return windowResources;
        }

        @Override
        public WindowResources resources() {
            return windowResources();
        }

        private McpAutomation mcpAutomation() {
            if (mcpAutomation == null) mcpAutomation = new McpAutomation(documentController(), Platform::runLater);
            return mcpAutomation;
        }

        private McpServerHost mcpServerHost() {
            if (mcpServerHost == null) mcpServerHost = new McpServerHost(mcpAutomation());
            return mcpServerHost;
        }

        private PrinterVisibility printerVisibility() {
            if (printerVisibility == null) printerVisibility = new PrinterVisibility(application.configuration());
            return printerVisibility;
        }

        private PrintController printController() {
            if (printController == null)
                printController = new PrintController(
                        Platform::runLater,
                        () -> documentController().snapshot().document(),
                        plugins,
                        resources().slicing,
                        files.gcode());
            return printController;
        }

        private SlicerSettings slicerSettings() {
            if (slicerSettings == null)
                slicerSettings = new SlicerSettings(application.configuration(), resources().appearance);
            return slicerSettings;
        }

        private PrintingPane printingPane() {
            if (printingPane == null)
                printingPane = new PrintingPane(
                        profiles(),
                        printerRuntime(),
                        printerVisibility(),
                        documentController(),
                        printController(),
                        resources().slicers,
                        slicerSettings(),
                        resources().exporters,
                        resources().validation,
                        translations);
            return printingPane;
        }

        private PrinterPane printerPane() {
            if (printerPane == null)
                printerPane = new PrinterPane(
                        printerRuntime(),
                        resources().printers,
                        profiles(),
                        printerVisibility(),
                        translations,
                        resources().printer);
            return printerPane;
        }

        private Appearance appearance() {
            if (appearance == null) appearance = new Appearance(application.configuration());
            return appearance;
        }

        private Rendering rendering() {
            if (rendering == null) rendering = new Rendering(application.configuration());
            return rendering;
        }

        private RecentFiles recentFiles() {
            if (recentFiles == null) recentFiles = new RecentFiles(application.configuration());
            return recentFiles;
        }

        private AboutDialog aboutDialog() {
            return new AboutDialog(application.updates(), translations, () -> WindowResources.worker("update"));
        }

        @Override
        public Workbench workbench() {
            if (workbench == null)
                workbench = new Workbench(
                        stage,
                        plugins,
                        documentController(),
                        mcpServerHost(),
                        resources().exporters,
                        files.importers(),
                        printingPane(),
                        printerPane(),
                        slicerSettings(),
                        appearance(),
                        rendering(),
                        resources().appearance,
                        translations,
                        application.configuration(),
                        recentFiles(),
                        before -> new UpdateDialog(
                                application.updates(), translations, () -> WindowResources.worker("update"), before),
                        () -> aboutDialog(),
                        resources(),
                        FeatureRegistry::new,
                        (geometry, parameters, id) ->
                                new SketchController(geometry, parameters, id, Platform::runLater));
            return workbench;
        }
    }

    public DefaultGuiFactory() {}

    @Override
    public GuiComponent create(Stage stage, Path plugins, ApplicationModule application, FileIoModule files) {
        return new Scope(stage, plugins, application, files);
    }
}
