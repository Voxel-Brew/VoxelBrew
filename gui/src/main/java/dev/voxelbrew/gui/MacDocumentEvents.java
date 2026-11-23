package dev.voxelbrew.gui;

import com.sun.glass.ui.Application;
import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.application.Platform;
import lombok.experimental.UtilityClass;

/** Finder delivers open-document events to the JavaFX Glass application delegate. */
@UtilityClass
class MacDocumentEvents {
    static void install(Consumer<Path> open) {
        var glass = Application.GetApplication();
        // JavaFX 21 also handles quit and accessibility theme changes through this handler.
        var toolkit = glass.getEventHandler();
        glass.setEventHandler(new Application.EventHandler() {
            @Override
            public void handleOpenFilesAction(Application app, long time, String[] files) {
                for (String file : files) {
                    Platform.runLater(() -> open.accept(Path.of(file)));
                }
            }

            @Override
            public void handleQuitAction(Application app, long time) {
                toolkit.handleQuitAction(app, time);
            }

            @Override
            public boolean handleThemeChanged(String themeName) {
                return toolkit.handleThemeChanged(themeName);
            }
        });
    }
}
