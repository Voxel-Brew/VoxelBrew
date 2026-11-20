package dev.voxelbrew.gui;

import dev.voxelbrew.util.di.ApplicationModule;
import java.nio.file.Path;
import javafx.stage.Stage;

/**
 * A separate graph for each window: documents and mutable controllers are never process globals.
 */
public interface GuiComponent {
    interface Factory {
        GuiComponent create(Stage stage, Path plugins, ApplicationModule application, FileIoModule files);
    }

    static Factory factory() {
        return dev.voxelbrew.util.services.Services.load(Factory.class);
    }

    Workbench workbench();

    WindowResources resources();
}
