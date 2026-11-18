package dev.voxelbrew.gui;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.parameters.*;
import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.model.sketch.SketchGeometry;
import dev.voxelbrew.api.printer.Printer;
import dev.voxelbrew.api.printer.driver.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.engine.sketch.SketchTools;
import dev.voxelbrew.engine.sketch.Sketches;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.importer.GcodeReader;
import dev.voxelbrew.printer.DefaultPrinterProfileCatalog;
import dev.voxelbrew.printer.FilePrinterStore;
import dev.voxelbrew.printer.ProfileHardware;
import dev.voxelbrew.printer.moonraker.MoonrakerDriverGui;
import dev.voxelbrew.slicer.prusa.PrusaSlicerBackend;
import dev.voxelbrew.util.Version;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.*;
import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Polygon;
import javafx.stage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Actual JavaFX windows, rendering, picking, keyboard input and mouse gestures on both OSes. */
@ExtendWith(UiTestShard.class)
class WorkbenchUiTest {
    @Test
    void readmeLightSketchScreenshots() throws Exception {
        ready();
        choice("appearance", 1);
        fx(() -> {
            var screen = Screen.getPrimary().getVisualBounds();
            stage.setWidth(Math.min(1440, screen.getWidth() - 60));
            stage.setHeight(Math.min(960, screen.getHeight() - 80));
            stage.centerOnScreen();
            return null;
        });
        click("view-cube-home");
        click("constrained-sketch");
        ready();
        assertThat(fx(() -> node("#sketch-plane-hint"))).isNotNull();
        readmeScreenshot("sketch-plane");
        standardView(1);
        modelClick(12, 12, 0);
        sketchReady();
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        sketchNumericPoint(0, 0);
        sketchNumericPoint(60, 40);
        sketchType(SketchTools.Tool.CIRCLE_CENTER);
        sketchNumericPoint(15, 20);
        sketchNumericPoint(21, 20);
        click("sketch-tool-select");
        sketchReady();
        readmeScreenshot("sketch-drawing");
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor")).controller().canFinish()))
                .isTrue();
        click("constraint-finish");
        ready();
        assertThat(document().features().size()).isEqualTo(1);
    }

    @Test
    void readmeLightWorkspaceScreenshots() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        choice("appearance", 1);
        fx(() -> {
            var screen = Screen.getPrimary().getVisualBounds();
            stage.setWidth(Math.min(1440, screen.getWidth() - 60));
            stage.setHeight(Math.min(960, screen.getHeight() - 80));
            stage.centerOnScreen();
            return null;
        });
        ready();
        click("view-cube-home");
        ready();
        readmeScreenshot("designer");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> node("#print-slice").isDisabled()) && System.nanoTime() < deadline) Thread.sleep(25);
        click("print-slice");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().running()) && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertThat(fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()))
                .isEqualTo(PrintController.State.SUCCEEDED);
        assertThat(document().activeGcode().isEmpty()).isFalse();
        ready();
        click("print-view-3d");
        ready();
        readmeScreenshot("slicer");
        click("workspace-printer");
        ready();
        assertThat(selectedWorkspace()).isEqualTo("workspace-printer");
        readmeScreenshot("printer");
    }

    private void readmeScreenshot(String workspace) throws Exception {
        fx(() -> {
            workbench.applyCss();
            workbench.layout();
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "readme-" + workspace + "-light.png"));
            return null;
        });
    }

    @Test
    void middleClickMenuUsesWorkspaceAndSketchCommands() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        var initial = document();
        var orientation = fx(() -> workbench.viewport().orientationProperty().get());
        var projection = fx(() -> workbench.viewport().modelToScreen(new Point3D(30, 20, 5)));
        openQuickMenu("viewport-scene");
        assertThat(fx(() -> workbench.viewport().orientationProperty().get())).isEqualTo(orientation);
        assertThat(fx(() -> workbench.viewport().modelToScreen(new Point3D(30, 20, 5))))
                .isEqualTo(projection);
        assertThat(fx(() -> node("#quick-extrude"))).isNotNull();
        assertThat(fx(() -> node("#quick-undo").isDisabled())).isTrue();
        fx(() -> {
            var popup = node("#quick-menu").getScene().getWindow();
            var screen = Screen.getScreensForRectangle(popup.getX(), popup.getY(), 1, 1)
                    .getFirst()
                    .getVisualBounds();
            assertThat(popup.getX() >= screen.getMinX()).isTrue();
            assertThat(popup.getY() >= screen.getMinY()).isTrue();
            assertThat(popup.getX() + popup.getWidth() <= screen.getMaxX() + 1).isTrue();
            assertThat(popup.getY() + popup.getHeight() <= screen.getMaxY() + 1).isTrue();
            Screenshot.save(
                    popup.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "quick-menu-designer.png"));
            return null;
        });
        click("quick-workspaces");
        assertThat(quickWorkspaceDisabled("workspace-printer")).isTrue();
        click("quick-workspace-print");
        ready();
        assertThat(selectedWorkspace()).isEqualTo("workspace-print");
        openQuickMenu("print-canvas");
        assertThat(fx(() -> node("#quick-print-slice"))).isNotNull();
        click("quick-print-view-3d");
        assertThat(fx(() -> ((RadioButton) node("#print-view-3d")).isSelected()))
                .isTrue();
        assertThat(document()).isEqualTo(initial);
        openQuickMenu("print-canvas");
        click("quick-workspaces");
        click("quick-workspace-design");
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        openQuickMenu("sketch-canvas");
        assertThat(fx(() -> node("#quick-sketch-tool-line"))).isNotNull();
        click("quick-workspaces");
        assertThat(quickWorkspaceDisabled("workspace-print")).isTrue();
        key(KeyCode.ESCAPE);
        key(KeyCode.ESCAPE);
        openQuickMenu("sketch-canvas");
        click("quick-constraint-cancel");
        assertThat(document()).isEqualTo(initial);
        fx(() -> {
            var plate =
                    workbench.controller().snapshot().document().printSetup().activePlateId();
            workbench.controller().gcode(Map.of(plate, List.of("G28", "G1 X10")));
            return null;
        });
        openQuickMenu("viewport-scene");
        click("quick-workspaces");
        click("quick-workspace-printer");
        assertThat(selectedWorkspace()).isEqualTo("workspace-printer");
        openQuickMenu("printer-heading");
        assertThat(fx(() -> node("#quick-printer-configure"))).isNotNull();
        fx(() -> {
            var menu = node("#quick-menu");
            assertThat(menu.lookup("#quick-printer-print"))
                    .as("Profile-only jobs must not expose physical-printer submission")
                    .isNull();
            assertThat(stage.getScene().lookup("#printer-print").isVisible()).isFalse();
            assertThat(node("#quick-printer-exportGcode").isDisabled()).isFalse();
            assertThat(node("#quick-printer-exportGcode").isDisabled())
                    .isEqualTo(node("#printer-exportGcode").isDisabled());
            Screenshot.save(
                    menu.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "quick-menu-printer-profile-only.png"));
            return null;
        });
        key(KeyCode.ESCAPE);
        assertThat(fx(() -> Window.getWindows().stream()
                        .anyMatch(w -> w.isShowing()
                                && w.getScene() != null
                                && w.getScene().lookup("#quick-menu") != null)))
                .isFalse();
    }

    private boolean quickWorkspaceDisabled(String id) throws Exception {
        return fx(() -> ((MenuButton) node("#quick-workspaces"))
                .getItems().stream()
                        .filter(item -> item.getId().equals("quick-" + id))
                        .findFirst()
                        .orElseThrow()
                        .isDisable());
    }

    @Test
    void polishMiddleClickMenuKeepsPanningAndFitsBothThemes() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        var initial = document();
        var orientation = fx(() -> workbench.viewport().orientationProperty().get());
        var before = fx(() -> workbench.viewport().modelToScreen(new Point3D(30, 20, 5)));
        navigate(false);
        assertThat(fx(() -> workbench.viewport().orientationProperty().get())).isEqualTo(orientation);
        assertThat(before.distance(fx(() -> workbench.viewport().modelToScreen(new Point3D(30, 20, 5)))) > 2)
                .isTrue();
        assertThat(quickMenuShowing()).isFalse();
        for (int appearance : List.of(1, 2)) {
            choice("appearance", appearance);
            fx(() -> {
                var bounds = node("#viewport-scene")
                        .localToScreen(node("#viewport-scene").getBoundsInLocal());
                robot.mouseMove(bounds.getMinX() + 8, bounds.getMaxY() - 8);
                robot.mouseClick(MouseButton.MIDDLE);
                return null;
            });
            Thread.sleep(200);
            fx(() -> {
                var root = node("#quick-menu");
                assertThat(root.getStyleClass().contains(appearance == 1 ? "light" : "dark"))
                        .isTrue();
                assertThat(((MenuButton) node("#quick-workspaces")).getText()).isEqualTo("Obszar roboczy");
                var popup = root.getScene().getWindow();
                var screen = Screen.getScreensForRectangle(popup.getX(), popup.getY(), 1, 1)
                        .getFirst()
                        .getVisualBounds();
                assertThat(popup.getY() + popup.getHeight() <= screen.getMaxY() + 1)
                        .isTrue();
                Screenshot.save(
                        root.getScene(),
                        Path.of(
                                System.getProperty("voxelbrew.ui.artifacts"),
                                "quick-menu-polish-" + appearance + ".png"));
                return null;
            });
            key(KeyCode.ENTER);
            assertThat(fx(() -> node("#quick-workspace-design"))).isNotNull();
            key(KeyCode.ESCAPE);
            key(KeyCode.ESCAPE);
            assertThat(quickMenuShowing()).isFalse();
        }
        openQuickMenu("viewport-scene");
        click("workspace-design");
        assertThat(quickMenuShowing()).isFalse();
        assertThat(document()).isEqualTo(initial);
    }

    private boolean quickMenuShowing() throws Exception {
        return fx(() -> Window.getWindows().stream()
                .anyMatch(w ->
                        w.isShowing() && w.getScene() != null && w.getScene().lookup("#quick-menu") != null));
    }

    private void openQuickMenu(String surface) throws Exception {
        var center = centerOf(surface);
        fx(() -> {
            robot.mouseMove(center);
            robot.mouseClick(MouseButton.MIDDLE);
            return null;
        });
        Thread.sleep(200);
        assertThat(fx(() -> node("#quick-menu"))).isNotNull();
    }

    @Test
    void printerDeviceFieldsFollowConnectionType() throws Exception {
        ready();
        var prepared = PreparedDocuments.copy("plate-5mm.vxb", directory);
        fx(() -> workbench.controller().open(prepared)).get(20, TimeUnit.SECONDS);
        ready();
        fx(() -> {
            ((TabPane) node("#workspaces")).getSelectionModel().select(1);
            return null;
        });
        Platform.runLater(() -> ((Button) node("#print-configure-devices")).fire());
        awaitPrinterControl("#device-config-driver");
        fx(() -> {
            var drivers = (ComboBox<?>) node("#device-config-driver");
            assertThat(drivers.getValue()).isNull();
            assertThat(((Button) node("#device-config-save")).isDisabled()).isTrue();
            assertThat(((javafx.scene.layout.VBox) node("#device-config-fields")).getChildren())
                    .isEmpty();
            ((Button) node("#device-config-cancel")).fire();
            return null;
        });
        assertThat(Files.exists(directory.resolve("printers"))).isFalse();
        Platform.runLater(() -> ((Button) node("#print-configure-devices")).fire());
        awaitPrinterControl("#device-config-driver");
        fx(() -> {
            ((TextField) node("#device-config-name")).setText("Lab Klipper");
            var drivers = (ComboBox<?>) node("#device-config-driver");
            int index = java.util.stream.IntStream.range(0, drivers.getItems().size())
                    .filter(i -> ((dev.voxelbrew.api.printer.driver.Driver)
                                    drivers.getItems().get(i))
                            .id()
                            .equals("moonraker"))
                    .findFirst()
                    .orElseThrow();
            drivers.getSelectionModel().select(index);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            assertThat(node("#device-config-moonraker-apiKey")).isInstanceOf(PasswordField.class);
            ((TextField) node("#device-config-moonraker-url")).setText("http://localhost:1");
            ((TextField) node("#device-config-moonraker-serial")).setText("KLIPPER-123");
            ((TextField) node("#device-config-moonraker-apiKey")).setText("private-key");
            return null;
        });
        Platform.runLater(() -> ((Button) node("#device-profile-new")).fire());
        awaitPrinterControl("#profile-name");
        fx(() -> {
            ((TextField) node("#profile-name")).setText("Custom fixture");
            ((TextField) node("#profile-id")).setText("custom-fixture");
            return null;
        });
        fx(() -> {
            Screenshot.save(
                    node("#profile-name").getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "printer-profile-editor.png"));
            ((Button) node("#profile-save")).fire();
            return null;
        });
        fx(() -> {
            assertThat(((ComboBox<?>) node("#device-config-profile")).getValue())
                    .isInstanceOf(dev.voxelbrew.api.printer.Profile.class);
            assertThat(((Button) node("#device-config-save")).isDisabled()).isFalse();
            Screenshot.save(
                    node("#device-config-driver").getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "printer-driver-inline.png"));
            ((Button) node("#device-config-save")).fire();
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !Files.exists(directory.resolve("printers"))) Thread.sleep(25);
        var store = new dev.voxelbrew.printer.FilePrinterStore(directory.resolve("printers"));
        while (System.nanoTime() < deadline && store.load().devices().isEmpty()) Thread.sleep(25);
        var saved = store.load().devices().getFirst();
        assertThat(saved.name()).isEqualTo("Lab Klipper");
        assertThat(saved.profile().id()).isEqualTo("custom-fixture");
        assertThat(saved.driverConfiguration().requireStringProperty(MoonrakerDriverGui.MOONRAKER_SERIAL_ID))
                .isEqualTo("KLIPPER-123");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fx(() -> stage.getScene().getRoot().lookup("#device-config-content")) != null
                && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(fx(() -> stage.getScene().getRoot().lookup("#device-config-content")))
                .as("The save completion callback should close the device dialog")
                .isNull();
        ready();
        Thread.sleep(200);
        fx(() -> {
            var target = (ComboBox<?>) node("#print-target");
            target.getSelectionModel().select(1);
            assertThat(target.getButtonCell().getText()).isEqualTo("Lab Klipper");
            return null;
        });
        ready();
        assertThat(document().printSetup().printerSnapshot()).isEqualTo(ProfileHardware.resolve(saved.profile()));
        assertThat(document().printSetup().workspaceSettings().get(PrintTarget.PRINTER_ID))
                .isEqualTo(saved.id());
        var catalog = new dev.voxelbrew.printer.DefaultPrinterProfileCatalog(
                dev.voxelbrew.util.services.Services.load(dev.voxelbrew.util.config.ApplicationPaths.class)
                        .profiles());
        var original = saved.profile();
        catalog.save(
                new dev.voxelbrew.api.printer.Profile(
                        original.id(),
                        original.name(),
                        original.manufacturer(),
                        original.bed(),
                        original.excludedZones(),
                        300,
                        original.nozzleDiameter(),
                        original.filamentDiameter(),
                        original.toolCount(),
                        original.firmware(),
                        original.outputFormat(),
                        original.genericGcode(),
                        original.imageReference()),
                null);
        assertThat(store.load().devices().getFirst().profile().usableHeight()).isEqualTo(200);
        Platform.runLater(() -> ((Button) node("#print-configure-devices")).fire());
        awaitPrinterControl("#device-config-driver");
        fx(() -> {
            var list = (ComboBox<?>) node("#device-config-content").lookup(".combo-box");
            list.getSelectionModel().selectFirst();
            assertThat(((TextField) node("#device-config-name")).getText()).isEqualTo("Lab Klipper");
            assertThat(((TextField) node("#device-config-moonraker-serial")).getText())
                    .isEqualTo("KLIPPER-123");
            assertThat(((TextField) node("#device-config-moonraker-apiKey")).getText())
                    .isEqualTo("private-key");
            var profile = (ComboBox<?>) node("#device-config-profile");
            assertThat(profile.getItems().stream()
                            .filter(p ->
                                    ((dev.voxelbrew.api.printer.Profile) p).id().equals(original.id()))
                            .count())
                    .isEqualTo(2);
            assertThat(((dev.voxelbrew.api.printer.Profile) profile.getValue()).usableHeight())
                    .isEqualTo(200);
            ((TextField) node("#device-config-name")).setText("Cancelled edit");
            ((TextField) node("#device-config-moonraker-serial")).setText("CANCELLED-SERIAL");
            ((Button) node("#device-config-cancel")).fire();
            return null;
        });
        assertThat(store.load().devices().getFirst().name()).isEqualTo("Lab Klipper");
        assertThat(store.load()
                        .devices()
                        .getFirst()
                        .driverConfiguration()
                        .requireStringProperty(MoonrakerDriverGui.MOONRAKER_SERIAL_ID))
                .isEqualTo("KLIPPER-123");
        assertThat(store.load().devices().getFirst().profile().usableHeight()).isEqualTo(200);
        Platform.runLater(() -> ((Button) node("#print-configure-devices")).fire());
        awaitPrinterControl("#device-config-driver");
        fx(() -> {
            ((ComboBox<?>) node("#device-config-content").lookup(".combo-box"))
                    .getSelectionModel()
                    .selectFirst();
            var profile = (ComboBox<?>) node("#device-config-profile");
            int updated = java.util.stream.IntStream.range(0, profile.getItems().size())
                    .filter(i -> ((dev.voxelbrew.api.printer.Profile)
                                            profile.getItems().get(i))
                                    .id()
                                    .equals(original.id())
                            && ((dev.voxelbrew.api.printer.Profile)
                                                    profile.getItems().get(i))
                                            .usableHeight()
                                    == 300)
                    .findFirst()
                    .orElseThrow();
            profile.getSelectionModel().select(updated);
            ((Button) node("#device-config-save")).fire();
            return null;
        });
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline
                && store.load().devices().getFirst().profile().usableHeight() != 300) Thread.sleep(25);
        ready();
        assertThat(store.load().devices().getFirst().profile().usableHeight()).isEqualTo(300);
        assertThat(document().printSetup().printerSnapshot().printer().usableHeight())
                .isEqualTo(300);
    }

    @Test
    void slicerNozzleIsSavedWithThePrintProfile() throws Exception {
        ready();
        var prepared = PreparedDocuments.copy("plate-5mm.vxb", directory);
        fx(() -> workbench.controller().open(prepared)).get(20, TimeUnit.SECONDS);
        ready();
        fx(() -> {
            ((TabPane) node("#workspaces")).getSelectionModel().select(1);
            ((TabPane) node("#print-controls")).getSelectionModel().select(1);
            var nozzle = (FormulaField) node("#print-nozzle");
            assertThat(nozzle.getText()).isEqualTo("0.4");
            var plate =
                    workbench.controller().snapshot().document().printSetup().activePlateId();
            workbench.controller().gcode(Map.of(plate, List.of("G28")));
            nozzle.setText("0.6");
            nozzle.fireEvent(new javafx.event.ActionEvent());
            return null;
        });
        ready();
        assertThat(document().printSetup().printerSnapshot().printer().nozzleMm())
                .isCloseTo(0.6, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(document().gcode().isEmpty())
                .as("Changing the slicing nozzle invalidates stored jobs")
                .isTrue();
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "slicer-nozzle.png"));
            return null;
        });
        var saved = directory.resolve("nozzle-in-slicer.vxb");
        fx(() -> workbench.controller().save(saved, false)).get(20, TimeUnit.SECONDS);
        fx(() -> workbench.controller().open(saved)).get(20, TimeUnit.SECONDS);
        ready();
        assertThat(document().printSetup().printerSnapshot().printer().nozzleMm())
                .isCloseTo(0.6, org.assertj.core.data.Offset.offset(0.0001));
        fx(() -> {
            ((TabPane) node("#workspaces")).getSelectionModel().select(1);
            ((TabPane) node("#print-controls")).getSelectionModel().select(1);
            return null;
        });
        assertThat(fx(() -> ((FormulaField) node("#print-nozzle")).getText())).isEqualTo("0.6");
    }

    @Test
    void printerWorkspaceShortcutsFollowEnabledTabOrder() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        shortcut(KeyCode.DIGIT3, false);
        assertThat(selectedWorkspace()).isEqualTo("workspace-design");
        shortcut(KeyCode.RIGHT, false);
        assertThat(selectedWorkspace()).isEqualTo("workspace-print");
        shortcut(KeyCode.RIGHT, false);
        assertThat(selectedWorkspace()).isEqualTo("workspace-print");
        shortcut(KeyCode.LEFT, false);
        assertThat(selectedWorkspace()).isEqualTo("workspace-design");
        fx(() -> {
            var plate =
                    workbench.controller().snapshot().document().printSetup().activePlateId();
            workbench.controller().gcode(Map.of(plate, List.of("G28", "G1 X10")));
            ((TabPane) node("#workspaces")).requestFocus();
            return null;
        });
        shortcut(KeyCode.DIGIT3, false);
        assertThat(selectedWorkspace()).isEqualTo("workspace-printer");
        shortcut(KeyCode.LEFT, false);
        ready();
        assertThat(selectedWorkspace()).isEqualTo("workspace-print");
        shortcut(KeyCode.RIGHT, false);
        assertThat(selectedWorkspace()).isEqualTo("workspace-printer");
        shortcut(KeyCode.DIGIT1, false);
        assertThat(selectedWorkspace()).isEqualTo("workspace-design");
        shortcut(KeyCode.RIGHT, false);
        ready();
        assertThat(selectedWorkspace()).isEqualTo("workspace-print");
        shortcut(KeyCode.RIGHT, false);
        assertThat(selectedWorkspace()).isEqualTo("workspace-printer");
        fx(() -> {
            var extra = new Tab("Future workspace");
            extra.setId("workspace-future");
            ((TabPane) node("#workspaces")).getTabs().add(extra);
            return null;
        });
        shortcut(KeyCode.DIGIT4, false);
        assertThat(selectedWorkspace()).isEqualTo("workspace-future");
        shortcut(KeyCode.LEFT, false);
        assertThat(selectedWorkspace()).isEqualTo("workspace-printer");
        shortcut(KeyCode.RIGHT, false);
        assertThat(selectedWorkspace()).isEqualTo("workspace-future");
    }

    private String selectedWorkspace() throws Exception {
        return fx(() -> ((TabPane) node("#workspaces"))
                .getSelectionModel()
                .getSelectedItem()
                .getId());
    }

    @Test
    void printerSubmissionRunsOnWorkerAndLeavesJavaFxResponsive() throws Exception {
        ready();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var submissionThread = new AtomicReference<Thread>();
        var transportOnFx = new AtomicBoolean();
        var submissions = new AtomicInteger();
        var driver = new Driver() {
            @Override
            public String id() {
                return "thread-fixture";
            }

            @Override
            public String name() {
                return "Thread fixture";
            }

            @Override
            public DriverGui gui() {
                throw new UnsupportedOperationException();
            }

            @Override
            public PrintResult print(DriverConfiguration configuration, PrintArtifact artifact) throws Exception {
                transportOnFx.compareAndSet(false, Platform.isFxApplicationThread());
                submissionThread.set(Thread.currentThread());
                submissions.incrementAndGet();
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Submission was not released");
                return PrintResult.Success.SUCCESS;
            }

            @Override
            public DriverStatus readPrinterStatus(DriverConfiguration configuration) {
                transportOnFx.compareAndSet(false, Platform.isFxApplicationThread());
                return new DriverStatus(
                        DriverStatus.State.IDLE,
                        "",
                        OptionalDouble.empty(),
                        OptionalDouble.empty(),
                        OptionalDouble.empty(),
                        OptionalDouble.empty(),
                        "");
            }
        };
        var catalog = new DefaultPrinterProfileCatalog(directory.resolve("thread-profiles"));
        var printer = new Printer(
                "thread-printer",
                "Thread printer",
                driver.id(),
                new DriverConfiguration(Map.of()),
                catalog.load().require("generic-marlin-200-0.4"));
        var store = new FilePrinterStore(directory.resolve("thread-printers"));
        store.save(printer);
        var document = new CadDocument("thread-job", List.of(), 0);
        String plate = document.printSetup().activePlateId();
        var job = document.withGcode(Map.of(plate, List.of("G28")))
                .withPrintArtifacts(Map.of(
                        plate,
                        PrintArtifact.of(
                                "gcode",
                                new byte[] {71, 50, 56, 10},
                                Map.of(
                                        "target_kind",
                                        "printer",
                                        "target_id",
                                        printer.id(),
                                        "target_profile",
                                        printer.profile().id()))));
        try (var registry = new DriverRegistry() {
                    @Override
                    public DriverRegistry withDirectory(Path plugins) {
                        return this;
                    }

                    @Override
                    @lombok.NonNull
                    public List<Driver> drivers() {
                        return List.of(driver);
                    }

                    @Override
                    @lombok.NonNull
                    public Optional<Driver> find(@lombok.NonNull String id) {
                        return id.equals(driver.id()) ? Optional.of(driver) : Optional.empty();
                    }

                    @Override
                    public void close() {}
                };
                var worker = WindowResources.worker("printer")) {
            var pane = fx(() -> {
                var created = new PrinterPane(
                        store,
                        registry,
                        catalog,
                        new PrinterVisibility(configuration),
                        new dev.voxelbrew.util.i18n.Translations(Locale.ENGLISH),
                        worker);
                new Scene(created, 820, 650);
                created.applyCss();
                created.layout();
                created.documentChanged(job);
                return created;
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (fx(() -> pane.lookup("#printer-print").isDisabled()) && System.nanoTime() < deadline)
                    Thread.sleep(25);
                fx(() -> {
                    var button = (Button) pane.lookup("#printer-print");
                    assertThat(button.isDisabled()).isFalse();
                    button.fire();
                    return null;
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var responsive = new FutureTask<>(Platform::isFxApplicationThread);
                Platform.runLater(responsive);
                assertThat(responsive.get(2, TimeUnit.SECONDS))
                        .as("JavaFX must process events while G-code submission is blocked")
                        .isTrue();
                assertThat(submissionThread.get().getName()).isEqualTo("voxelbrew-printer");
                assertThat(transportOnFx.get())
                        .as("Submission and monitoring must stay off JavaFX")
                        .isFalse();
                assertThat(submissions.get()).isEqualTo(1);
                assertThat(fx(() -> pane.lookup("#printer-print").isDisabled())).isTrue();
                assertThat(release.getCount())
                        .as("Driver remains blocked during the responsiveness check")
                        .isEqualTo(1);
            } finally {
                release.countDown();
                fx(() -> {
                    pane.close();
                    return null;
                });
            }
        }
    }

    @Test
    void printerWorkspaceFollowsStoredPlateJobs() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        fx(() -> {
            var tabs = (TabPane) node("#workspaces");
            assertThat(tabs.getTabs().size()).isEqualTo(3);
            assertThat(tabs.getTabs().get(2).isDisable()).isTrue();
            var plate =
                    workbench.controller().snapshot().document().printSetup().activePlateId();
            workbench.controller().gcode(Map.of(plate, List.of("G28", "G1 X10")));
            assertThat(tabs.getTabs().get(2).isDisable()).isFalse();
            tabs.getSelectionModel().select(2);
            assertThat(((ComboBox<?>) node("#printer-plate")).getItems().size()).isEqualTo(1);
            assertThat(stage.getScene().lookup("#printer-print").isVisible()).isFalse();
            assertThat(((Button) node("#printer-exportGcode")).isDisable()).isFalse();
            workbench.applyCss();
            workbench.layout();
            var selector = (ComboBox<?>) node("#printer-plate");
            assertThat(selector.getValue())
                    .as("Sliced plate is selected when the workspace opens")
                    .isNotNull();
            assertThat(selector.getWidth() <= 820)
                    .as("Printer form stays readable at wide window sizes")
                    .isTrue();
            assertThat(selector.getWidth() >= 400)
                    .as("Printer form has room for device names")
                    .isTrue();
            return null;
        });
        ready();
        fx(() -> {
            var selectedPlate = (ComboBox<?>) node("#printer-plate");
            assertThat(selectedPlate.getButtonCell().getText()).isEqualTo(selectedText(selectedPlate));
            assertThat(selectedPlate.getButtonCell().getText().isBlank())
                    .as("Selected plate needs a readable name")
                    .isFalse();
            assertThat(selectedPlate.getButtonCell().getText()).isEqualTo("Plate 1");
            assertThat(selectedPlate.getSelectionModel().getSelectedItem()).isEqualTo(selectedPlate.getValue());
            assertThat(selectedPlate.getButtonCell().isVisible()
                            && selectedPlate.getButtonCell().getWidth() > 50)
                    .as("Selected plate label must have visible layout space")
                    .isTrue();
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "printer-workspace.png"));
            return null;
        });
        var savedPrinterJob = directory.resolve("printer-reopen.vxb");
        fx(() -> workbench.controller().save(savedPrinterJob, false)).get(20, TimeUnit.SECONDS);
        fx(() -> workbench.controller().open(savedPrinterJob)).get(20, TimeUnit.SECONDS);
        ready();
        fx(() -> {
            var tabs = (TabPane) node("#workspaces");
            assertThat(tabs.getTabs().get(2).isDisable())
                    .as("Saved sliced plate enables Printer after reopen")
                    .isFalse();
            workbench.controller().invalidateGcode();
            assertThat(tabs.getTabs().get(2).isDisable()).isTrue();
            assertThat(tabs.getSelectionModel().getSelectedIndex()).isNotEqualTo(2);
            return null;
        });
    }

    @Test
    void slicerBoundsRenderGreyHintsAndRedInvalidValues() throws Exception {
        Path input = PreparedDocuments.copy("plate-5mm.vxb", directory);
        fx(() -> workbench.controller().open(input)).get(20, TimeUnit.SECONDS);
        ready();
        fx(() -> {
            ((TabPane) node("#workspaces")).getSelectionModel().select(1);
            var tabs = (TabPane) node("#print-controls");
            tabs.getSelectionModel()
                    .select(tabs.getTabs().stream()
                            .filter(tab -> "print-tab-process".equals(tab.getId()))
                            .findFirst()
                            .orElseThrow());
            workbench.applyCss();
            workbench.layout();
            ((ScrollPane) tabs.getSelectionModel().getSelectedItem().getContent()).setVvalue(0.3);
            var field = (FormulaField) node("#print-infill");
            var minimum = (Label) field.lookup(".formula-minimum");
            var maximum = (Label) field.lookup(".formula-maximum");
            var unitLabel = (Label) field.lookup(".formula-unit");
            assertThat(unitLabel.getText()).isEqualTo("%");
            assertThat(field.dragStep()).isEqualTo(1);
            assertThat(unitLabel.isMouseTransparent()).isTrue();
            assertThat(minimum.getText()).isEqualTo("0 ≤");
            assertThat(maximum.getText()).isEqualTo("≤ 100");
            var layer = (FormulaField) node("#print-layer-height");
            assertThat(layer.dragStep()).isEqualTo(0.01);
            assertThat(((FormulaField) node("#print-walls")).dragStep()).isEqualTo(1);
            assertThat(((FormulaField) node("#print-gap")).dragStep()).isEqualTo(0.01);
            assertThat(((FormulaField) node("#print-threshold")).dragStep()).isEqualTo(1);
            assertThat(((FormulaField) node("#print-interfaces")).dragStep()).isEqualTo(1);
            assertThat(((FormulaField) node("#print-raft")).dragStep()).isEqualTo(1);
            assertThat(((Label) layer.lookup(".formula-unit")).getText()).isEqualTo("mm");
            assertThat(((Label) node("#print-walls").lookup(".formula-unit")).getText())
                    .isEqualTo("");
            assertThat(((Label) layer.lookup(".formula-minimum")).getText()).isEqualTo("0.05 ≤");
            assertThat(((Label) layer.lookup(".formula-maximum")).getText()).isEqualTo("≤ 0.3");
            var invalid = javafx.css.PseudoClass.getPseudoClass("formula-invalid");
            for (String palette : List.of("light", "dark")) {
                command(workbench, "appearance-" + palette).fire();
                for (String value : List.of("101", "=50*3", "50")) {
                    field.setText(value);
                    workbench.applyCss();
                    workbench.layout();
                    boolean outside = !value.equals("50");
                    assertThat(field.getPseudoClassStates().contains(invalid)).isEqualTo(outside);
                    var text = (javafx.scene.text.Text) field.lookup(".text");
                    field.selectAll();
                    assertThat(field.getSelectedText())
                            .as("Unit is not part of editable or copied text")
                            .isEqualTo(value);
                    field.deselect();
                    if (outside) assertThat(text.getFill()).isEqualTo(javafx.scene.paint.Color.web("#d65c46"));
                    else assertThat(text.getFill()).isNotEqualTo(javafx.scene.paint.Color.web("#d65c46"));
                    assertThat(minimum.getTextFill())
                            .isEqualTo(javafx.scene.paint.Color.web(palette.equals("light") ? "#777777" : "#aaaaaa"));
                    assertThat(minimum.localToScene(minimum.getBoundsInLocal()).getMaxX()
                                    < maximum.localToScene(maximum.getBoundsInLocal())
                                            .getMinX())
                            .isTrue();
                    double suffixGap =
                            unitLabel.localToScene(unitLabel.getBoundsInLocal()).getMinX()
                                    - text.localToScene(text.getLayoutBounds()).getMaxX();
                    assertThat(suffixGap >= 4 && suffixGap <= 12)
                            .as("Unit follows the text: " + suffixGap)
                            .isTrue();
                    double maximumGap =
                            maximum.localToScene(maximum.getBoundsInLocal()).getMinX()
                                    - unitLabel
                                            .localToScene(unitLabel.getBoundsInLocal())
                                            .getMaxX();
                    assertThat(maximumGap).isCloseTo(6, org.assertj.core.data.Offset.offset(1.0));
                    Screenshot.save(
                            stage.getScene(),
                            Path.of(
                                    System.getProperty("voxelbrew.ui.artifacts"),
                                    "slicer-inline-bounds-" + palette + "-" + (outside ? "invalid" : "valid")
                                            + ".png"));
                }
            }
            return null;
        });
    }

    @Test
    void formulaUnitSuffixesFollowDeclaredUnitsWithoutChangingInput() throws Exception {
        fx(() -> {
            var selected = new javafx.beans.property.SimpleObjectProperty<>(ParameterUnit.MM);
            var field = FormulaField.number("5", ParameterSet::empty, selected::get);
            selected.addListener(o -> field.refreshFormula());
            field.setSkin(field.createDefaultSkin());
            field.refreshFormula();
            var label = (Label) field.lookup(".formula-unit");
            for (var unit : ParameterUnit.values()) {
                selected.set(unit);
                assertThat(field.dragStep())
                        .isEqualTo(unit == ParameterUnit.DEG ? 1 : unit == ParameterUnit.RAD ? 0.01 : 0.1);
                assertThat(label.getText())
                        .isEqualTo(
                                unit == ParameterUnit.NUMBER ? "" : unit == ParameterUnit.DEG ? "°" : unit.toString());
                assertThat(field.getText()).isEqualTo("5");
            }
            field.displayUnit("%");
            field.selectAll();
            field.replaceSelection("25");
            assertThat(label.getText()).isEqualTo("%");
            assertThat(field.getText()).isEqualTo("25");
            assertThat(field.value()).isCloseTo(25, org.assertj.core.data.Offset.offset(1e-9));
            var text = FormulaField.text("name", ParameterSet::empty);
            text.setSkin(text.createDefaultSkin());
            text.refreshFormula();
            assertThat(((Label) text.lookup(".formula-unit")).getText()).isEqualTo("");
            return null;
        });
    }

    @Test
    void formulaBoundsValidateDisplayedUnitsAndStrictEndpoints() throws Exception {
        fx(() -> {
            var invalid = javafx.css.PseudoClass.getPseudoClass("formula-invalid");
            var field = FormulaField.number("1", ParameterSet::empty, ParameterUnit.CM)
                    .bounds(1, false, 2, true);
            assertThat(field.getPseudoClassStates().contains(invalid)).isTrue();
            field.setText("=15 mm");
            assertThat(field.value()).isCloseTo(15, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(field.getPseudoClassStates().contains(invalid)).isFalse();
            field.setText("2");
            assertThat(field.value()).isCloseTo(20, org.assertj.core.data.Offset.offset(1e-9));
            field.bounds(1, true, 2, false);
            assertThat(field.getPseudoClassStates().contains(invalid)).isTrue();
            assertThatThrownBy(field::value).isInstanceOf(IllegalArgumentException.class);
            field.setText("1");
            assertThat(field.getPseudoClassStates().contains(invalid)).isFalse();
            field.bounds(Double.NEGATIVE_INFINITY, false, Double.POSITIVE_INFINITY, false);
            field.setText("-1000");
            assertThat(field.getPseudoClassStates().contains(invalid)).isFalse();
            return null;
        });
    }

    @Test
    void slicerProcessBoundsValidateLiteralsAndFormulasBeforeSaving() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-tab-process");
        for (String input : List.of("-1", "101", "=50*3")) {
            text("print-infill", input);
            fx(() -> {
                var field = (FormulaField) node("#print-infill");
                assertThatThrownBy(field::value).isInstanceOf(IllegalArgumentException.class);
                assertThat(field.getPseudoClassStates()
                                .contains(javafx.css.PseudoClass.getPseudoClass("formula-invalid")))
                        .isTrue();
                return null;
            });
            assertThat(document().printSetup().process().infillPercent()).isEqualTo(20);
        }
        for (String input : List.of("0", "100", "=25*2")) {
            text("print-infill", input);
            key(KeyCode.TAB);
            assertThat(document().printSetup().process().infillPercent())
                    .isEqualTo(input.startsWith("=") ? 50 : Double.parseDouble(input));
        }
        text("print-walls", "2.5");
        fx(() -> {
            assertThatThrownBy(() -> ((FormulaField) node("#print-walls")).value())
                    .isInstanceOf(IllegalArgumentException.class);
            return null;
        });
        assertThat(document().printSetup().process().walls()).isEqualTo(2);
    }

    @Test
    void slicedGcodeLocksTabsUntilCleanedAndSurvivesReopenAndExport() throws Exception {
        Path input = PreparedDocuments.copy("plate-5mm.vxb", directory);
        fx(() -> workbench.controller().open(input)).get(20, TimeUnit.SECONDS);
        ready();
        click("workspace-print");
        click("print-add");
        ready();
        click("print-tab-job");
        assertThat(fx(() -> workbench.lookup("#print-output"))).isNull();
        assertThat(fx(() -> workbench.lookup("#print-overwrite"))).isNull();
        assertThat(fx(() -> node("#print-clean-slicing").isDisabled())).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (fx(() -> node("#print-slice").isDisabled()) && System.nanoTime() < deadline) Thread.sleep(25);
        click("print-slice");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().running()) && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertThat(fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()))
                .isEqualTo(PrintController.State.SUCCEEDED);
        var lines = document().activeGcode();
        assertThat(lines.isEmpty()).isFalse();
        assertThat(fx(() -> ((TabPane) node("#print-controls"))
                        .getTabs().subList(0, 3).stream().allMatch(Tab::isDisable)))
                .isTrue();
        assertThat(fx(() ->
                        ((TabPane) node("#print-controls")).getSelectionModel().getSelectedIndex()))
                .isEqualTo(3);
        Path saved = directory.resolve("sliced.vxb");
        fx(() -> workbench.controller().save(saved, false)).get(20, TimeUnit.SECONDS);
        assertThat(DocumentReader.load(saved).activeGcode()).isEqualTo(lines);
        fx(() -> workbench.controller().open(saved)).get(20, TimeUnit.SECONDS);
        ready();
        click("workspace-print");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().result()) == null
                && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(fx(() -> ((PrintingPane) node("#printing-workspace"))
                        .jobs()
                        .result()
                        .job()
                        .lines()))
                .isEqualTo(lines);
        try (var registry = java.util.ServiceLoader.load(dev.voxelbrew.api.exporter.ExporterProviderRegistry.class)
                .findFirst()
                .orElseThrow()
                .withDirectory(directory)) {
            Path output = directory.resolve("exported.gcode");
            fx(() -> workbench
                            .controller()
                            .exportDocument(
                                    registry.require("gcode"),
                                    dev.voxelbrew.util.testing.FileExportWindows.destination(output, false)))
                    .get(20, TimeUnit.SECONDS);
            assertThat(Files.readAllLines(output)).isEqualTo(lines);
        }
        assertThat(fx(() -> ((TabPane) node("#print-controls"))
                        .getTabs().subList(0, 3).stream().allMatch(Tab::isDisable)))
                .isTrue();
        var setupBeforeClean = document().printSetup();
        click("print-clean-slicing");
        assertThat(document().printSetup()).isEqualTo(setupBeforeClean);
        assertThat(document().gcode().isEmpty()).isTrue();
        assertThat(fx(() -> node("#print-clean-slicing").isDisabled())).isTrue();
        assertThat(fx(() ->
                        ((TabPane) node("#print-controls")).getTabs().stream().noneMatch(Tab::isDisable)))
                .isTrue();
        assertThat(fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()))
                .isEqualTo(PrintController.State.IDLE);
        click("print-tab-process");
        text("print-layer-height", "0.25");
        assertThat(document().gcode().isEmpty()).isTrue();
        assertThat(fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().result()))
                .isNull();
    }

    @Test
    void supportsSwitchDisablesEverythingAndRestoresSavedDefinitions() throws Exception {
        Path input = PreparedDocuments.copy("plate-5mm.vxb", directory);
        fx(() -> workbench.controller().open(input)).get(20, TimeUnit.SECONDS);
        ready();
        click("workspace-print");
        click("print-add");
        ready();
        fx(() -> {
            var setup = document().printSetup();
            var process = new ProcessSettings(0.2, 2, 20, true, true, 45, 0.2, 3, 0, 2);
            workbench
                    .controller()
                    .printSetup(setup.withProfile(setup.backendId(), setup.printerId(), setup.materialId(), process)
                            .withSupports(List.of(new SupportVolume(
                                    "manual",
                                    setup.instances().getFirst().id(),
                                    SupportVolume.Role.ENFORCER,
                                    0,
                                    0,
                                    0,
                                    5,
                                    5,
                                    5))));
            return null;
        });
        ready();
        var enabled = document().printSetup();
        click("print-tab-supports");
        click("print-supports-enabled");
        ready();
        assertThat(document().printSetup().supportsDisabled()).isTrue();
        assertThat(document().printSetup().supports()).isEqualTo(enabled.supports());
        assertThat(fx(() -> node("#print-support-apply").isDisabled())).isTrue();
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "slicer-supports-disabled.png"));
            return null;
        });
        Path saved = directory.resolve("supports-disabled.vxb");
        fx(() -> workbench.controller().save(saved, false)).get(20, TimeUnit.SECONDS);
        fx(() -> workbench.controller().open(saved)).get(20, TimeUnit.SECONDS);
        ready();
        click("workspace-print");
        click("print-tab-supports");
        assertThat(fx(() -> ((CheckBox) node("#print-supports-enabled")).isSelected()))
                .isFalse();
        click("print-supports-enabled");
        ready();
        assertThat(document().printSetup().supportsDisabled()).isFalse();
        assertThat(document().printSetup().supports()).isEqualTo(enabled.supports());
        assertThat(document().printSetup().process()).isEqualTo(enabled.process());
        assertThat(fx(() -> node("#print-support-apply").isDisabled())).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void slicerDepthIsIndependentOfObjectOrderAndPickingUsesVisibleSurface() throws Exception {
        Path input = PreparedDocuments.copy("plate-5mm.vxb", directory);
        fx(() -> workbench.controller().open(input)).get(20, TimeUnit.SECONDS);
        ready();
        click("workspace-print");
        String body =
                fx(() -> workbench.controller().build().bodies().getFirst().id());
        var front = new BodyInstance("front", body, Transform.translation(70, 80, 20));
        var rear = new BodyInstance("rear", body, Transform.translation(70, 80, 0));
        fx(() -> {
            workbench.controller().printSetup(document().printSetup().withInstances(List.of(front, rear)));
            return null;
        });
        ready();
        click("print-view-3d");
        var before = fx(() -> node("#print-canvas").snapshot(null, null));
        fx(() -> {
            workbench.controller().printSetup(document().printSetup().withInstances(List.of(rear, front)));
            return null;
        });
        ready();
        var after = fx(() -> node("#print-canvas").snapshot(null, null));
        assertThat(after.getWidth()).isEqualTo(before.getWidth());
        assertThat(after.getHeight()).isEqualTo(before.getHeight());
        for (int y = 0; y < (int) before.getHeight(); y++)
            for (int x = 0; x < (int) before.getWidth(); x++)
                assertThat(after.getPixelReader().getArgb(x, y))
                        .as("Object list order must not change visibility at " + x + "," + y)
                        .isEqualTo(before.getPixelReader().getArgb(x, y));
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "slicer-depth-3d.png"));
            return null;
        });
        click("print-view-2d");
        fx(() -> {
            ((ComboBox<String>) node("#print-instance")).setValue("rear");
            var canvas = (javafx.scene.canvas.Canvas) node("#print-canvas");
            robot.mouseMove(canvas.localToScreen(canvas.getWidth() / 2, canvas.getHeight() / 2));
            return null;
        });
        Thread.sleep(150);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        assertThat(fx(() -> ((ComboBox<?>) node("#print-instance")).getValue())).isEqualTo("front");
    }

    @Test
    void orientAllPlacesEveryObjectAndUndoesAsOneEdit() throws Exception {
        Path input = PreparedDocuments.copy("plate-5mm.vxb", directory);
        fx(() -> workbench.controller().open(input)).get(20, TimeUnit.SECONDS);
        ready();
        click("workspace-print");
        assertThat(fx(() -> node("#print-orient-all").isDisabled())).isTrue();
        click("print-add");
        ready();
        click("print-add");
        ready();
        fx(() -> {
            var setup = workbench.controller().snapshot().document().printSetup();
            workbench
                    .controller()
                    .printSetup(setup.withInstances(setup.instances().stream()
                            .map(i -> new BodyInstance(
                                    i.id(),
                                    i.bodyId(),
                                    Transform.rotation(90, 0, 0).compose(i.transform())))
                            .toList()));
            return null;
        });
        ready();
        var before = document().printSetup().instances();
        click("print-orient-all");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (document().printSetup().instances().equals(before) && System.nanoTime() < deadline) Thread.sleep(40);
        ready();
        assertThat(document().printSetup().instances().size()).isEqualTo(2);
        for (var instance : document().printSetup().instances()) {
            var mesh = fx(() -> workbench.controller().build().bodies().stream()
                    .filter(b -> b.id().equals(instance.bodyId()))
                    .findFirst()
                    .orElseThrow()
                    .mesh());
            var bounds = MeshGeometry.bounds(MeshGeometry.transform(mesh, instance.transform()));
            assertThat(bounds.minZ()).isCloseTo(0, org.assertj.core.data.Offset.offset(0.001));
            assertThat(bounds.maxZ()).isCloseTo(5, org.assertj.core.data.Offset.offset(0.001));
        }
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "slicer-orient-all.png"));
            workbench.controller().undo();
            return null;
        });
        ready();
        assertThat(document().printSetup().instances()).isEqualTo(before);
    }

    @Test
    void slicerPlateControlsOrientationAndObjectRemoval() throws Exception {
        Path input = PreparedDocuments.copy("plate-5mm.vxb", directory);
        fx(() -> workbench.controller().open(input)).get(20, TimeUnit.SECONDS);
        ready();
        click("workspace-print");
        assertThat(fx(() -> node("#print-remove-plate-plate-1").isDisabled())).isTrue();
        click("print-add");
        ready();
        click("print-auto-orient");
        Thread.sleep(700);
        ready();
        fx(() -> {
            assertThat(stage.getScene().lookup("#print-duplicate")).isNull();
            assertThat(stage.getScene().lookup("#print-face")).isNull();
            assertThat(stage.getScene().lookup("#print-apply-process")).isNull();
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "slicer-ux-placement.png"));
            return null;
        });
        click("print-remove");
        ready();
        assertThat(document().printSetup().instances().isEmpty()).isTrue();
        click("print-add");
        ready();
        fx(() -> {
            node("#print-canvas").requestFocus();
            return null;
        });
        key(KeyCode.DELETE);
        ready();
        assertThat(document().printSetup().instances().isEmpty()).isTrue();
        click("print-add-plate");
        ready();
        String second = document().printSetup().activePlateId();
        var from = fx(() -> node("#print-plate-plate-1").localToScreen(20, 15));
        var to = fx(() -> node("#print-plate-" + second).localToScreen(20, 15));
        var dragOver = new CountDownLatch(1);
        fx(() -> {
            node("#print-plate-" + second)
                    .addEventFilter(javafx.scene.input.DragEvent.DRAG_OVER, event -> dragOver.countDown());
            return null;
        });
        // Windows native drag-and-drop blocks the JavaFX Robot call until release.
        // Drive this gesture from the test thread so release can reach the OS loop.
        var dragRobot = new java.awt.Robot();
        dragRobot.mouseMove((int) from.getX(), (int) from.getY());
        Thread.sleep(150);
        dragRobot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        try {
            Thread.sleep(150);
            dragRobot.mouseMove((int) from.getX() + 12, (int) from.getY());
            Thread.sleep(200);
            for (int step = 1; step <= 20; step++) {
                double fraction = step / 20.0;
                dragRobot.mouseMove((int) (from.getX() + 12 + (to.getX() - from.getX() - 12) * fraction), (int)
                        (from.getY() + (to.getY() - from.getY()) * fraction));
                Thread.sleep(40);
            }
            assertThat(dragOver.await(5, TimeUnit.SECONDS))
                    .as("The target plate must receive the native drag before release")
                    .isTrue();
            fx(() -> null);
        } finally {
            dragRobot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        }
        Thread.sleep(300);
        ready();
        assertThat(document().printSetup().plates().getFirst().id()).isEqualTo(second);
        assertThat(document().printSetup().activePlateId()).isEqualTo(second);
        click("print-remove-plate-plate-1");
        ready();
        assertThat(document().printSetup().activePlateId()).isEqualTo(second);
        assertThat(document().printSetup().plates().size()).isEqualTo(1);
        assertThat(fx(() -> node("#print-remove-plate-" + second).isDisabled())).isTrue();
        click("print-tab-job");
        assertThat(fx(() -> workbench.lookup("#print-overwrite"))).isNull();
        assertThat(fx(() -> stage.getScene().lookup("#print-export"))).isNull();
    }

    @Test
    void slicerTabsAndJobPreferencesSurviveSaveAndReopen() throws Exception {
        Path input = PreparedDocuments.copy("plate-5mm.vxb", directory);
        fx(() -> workbench.controller().open(input)).get(20, TimeUnit.SECONDS);
        ready();
        click("workspace-print");
        click("print-add");
        ready();
        text("print-x", "23");
        click("print-place");
        ready();
        click("print-tab-process");
        text("print-walls", "4");
        click("print-tab-job");
        ready();
        click("print-tab-process");
        text("print-walls", "5");
        click("print-tab-supports");
        text("print-support-width", "7");
        click("print-tab-job");
        Path file = directory.resolve("slicer-settings.vxb");
        fx(() -> workbench.controller().save(file, false)).get(20, TimeUnit.SECONDS);
        ready();
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
        fx(() -> workbench.controller().open(file)).get(20, TimeUnit.SECONDS);
        ready();
        click("workspace-print");
        ready();
        click("print-tab-job");
        assertThat(fx(() -> workbench.lookup("#print-overwrite"))).isNull();
        click("print-tab-process");
        assertThat(fx(() -> ((TextField) node("#print-walls")).getText())).isEqualTo("5");
        assertThat(document().printSetup().process().walls()).isEqualTo(5);
        click("print-tab-supports");
        assertThat(fx(() -> ((TextField) node("#print-support-width")).getText()))
                .isEqualTo("7");
        click("print-tab-placement");
        assertThat(fx(() -> ((TextField) node("#print-x")).getText())).isEqualTo("23");
        assertThat(document().printSetup().instances().getFirst().transform().matrix()[3])
                .isEqualTo(23);
    }

    @Test
    void printerSelectionPersistsAndUndoRestoresBuildVolume() throws Exception {
        ready();
        Platform.runLater(() -> ((MenuBar) node("#main-menu"))
                .getMenus().stream()
                        .filter(menu -> "menu-printers".equals(menu.getId()))
                        .findFirst()
                        .orElseThrow()
                        .getItems()
                        .stream()
                        .filter(item -> "printers-export-defaults".equals(item.getId()))
                        .findFirst()
                        .orElseThrow()
                        .fire());
        ready();
        fx(() -> {
            var pane = Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .map(window -> window.getScene().lookup("#printer-export-dialog"))
                    .filter(Objects::nonNull)
                    .map(Parent.class::cast)
                    .findFirst()
                    .orElseThrow();
            var confirm = stage.getScene().lookup("#printer-export-confirm");
            assertThat(confirm.isDisabled())
                    .as("Export requires an explicit selection")
                    .isTrue();
            var choice = (CheckBox) pane.lookup("#printer-export-bambu-lab-a1-mini");
            choice.setSelected(true);
            assertThat(confirm.isDisabled()).isFalse();
            ((TextField) pane.lookup("#printer-export-search")).setText("Prusa");
            assertThat(choice.isVisible()).isFalse();
            assertThat(choice.isSelected())
                    .as("Filtering retains explicit selections")
                    .isTrue();
            ((Button) stage.getScene().lookup("#printer-dialog-cancel")).fire();
            return null;
        });
        ready();
        fx(() -> {
            var menu = ((MenuBar) node("#main-menu"))
                    .getMenus().stream()
                            .filter(item -> "menu-printers".equals(item.getId()))
                            .findFirst()
                            .orElseThrow();
            assertThat(menu.getItems().stream().map(MenuItem::getId).toList())
                    .isEqualTo(List.of(
                            "printers-configure-devices",
                            "printers-export-defaults",
                            "printers-open-folder",
                            "printers-reload",
                            "printers-visibility"));
            var reload = menu.getItems().stream()
                    .filter(item -> "printers-reload".equals(item.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(reload.getText()).isEqualTo("Reload printers profiles");
            reload.fire();
            return null;
        });
        Platform.runLater(() -> ((MenuBar) node("#main-menu"))
                .getMenus().stream()
                        .filter(menu -> "menu-printers".equals(menu.getId()))
                        .findFirst()
                        .orElseThrow()
                        .getItems()
                        .stream()
                        .filter(item -> "printers-visibility".equals(item.getId()))
                        .findFirst()
                        .orElseThrow()
                        .fire());
        ready();
        fx(() -> {
            var pane = Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .map(window -> window.getScene().lookup("#printer-visibility-dialog"))
                    .filter(Objects::nonNull)
                    .map(Parent.class::cast)
                    .findFirst()
                    .orElseThrow();
            var choices = ((javafx.scene.layout.VBox) pane.lookup("#printer-options"))
                    .getChildren().stream().map(CheckBox.class::cast).toList();
            assertThat(choices.size()).isEqualTo(62);
            ((TextField) pane.lookup("#printer-export-search")).setText("Prusa");
            ((Button) pane.lookup("#printer-deselect-all")).fire();
            assertThat(choices.stream().noneMatch(CheckBox::isSelected)).isTrue();
            assertThat(stage.getScene().lookup("#printer-export-confirm").isDisabled())
                    .as("Hiding all defaults is allowed")
                    .isFalse();
            choices.getFirst().setSelected(true);
            ((Button) pane.lookup("#printer-invert-selection")).fire();
            assertThat(choices.getFirst().isSelected()).isFalse();
            assertThat(choices.stream().filter(CheckBox::isSelected).count()).isEqualTo(61);
            ((Button) pane.lookup("#printer-select-all")).fire();
            assertThat(choices.stream().allMatch(CheckBox::isSelected))
                    .as("Bulk actions include filtered-out printers")
                    .isTrue();
            ((TextField) pane.lookup("#printer-export-search")).clear();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "printer-visibility-dialog.png"));
            ((Button) stage.getScene().lookup("#printer-dialog-cancel")).fire();
            return null;
        });
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        var original = document().printSetup();
        click("print-printer");
        ready();
        assertThat(fx(() -> node("#print-printer-search").isFocused())).isTrue();
        text("print-printer-search", "no matching printer xyz");
        assertThat(fx(() -> ((ListView<?>) node("#print-printer-results"))
                        .getItems()
                        .isEmpty()))
                .isTrue();
        assertThat(fx(() -> node("#print-printer-confirm").isDisabled())).isTrue();
        key(KeyCode.ESCAPE);
        ready();
        assertThat(document().printSetup())
                .as("Cancel leaves project printer unchanged")
                .isEqualTo(original);
        click("print-printer");
        ready();
        text("print-printer-search", "Bambu");
        key(KeyCode.DOWN);
        assertThat(fx(() -> ((ListView<?>) node("#print-printer-results"))
                        .getSelectionModel()
                        .getSelectedIndex()))
                .isEqualTo(1);
        key(KeyCode.UP);
        assertThat(fx(() -> ((ListView<?>) node("#print-printer-results"))
                        .getSelectionModel()
                        .getSelectedIndex()))
                .isEqualTo(0);
        text("print-printer-search", "  a1 MINI  ");
        assertThat(fx(() -> ((ListView<?>) node("#print-printer-results"))
                        .getItems()
                        .size()))
                .isEqualTo(1);
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "printer-search-dialog.png"));
            return null;
        });
        key(KeyCode.ENTER);
        ready();
        assertThat(document().printSetup().printerId()).isEqualTo("bambu-lab-a1-mini");
        assertThat(PrintProfile.resolve(document().printSetup()).printer().usableHeight())
                .isEqualTo(180);
        assertThat(fx(() -> ((Label) node("#print-printer-details")).getText()).contains("180.0"))
                .isTrue();
        click("print-add");
        ready();
        assertThat(fx(() -> node("#print-slice").isDisabled()))
                .as("Selected printer permits slicing")
                .isFalse();
        shortcut(KeyCode.Z, false);
        ready();
        fx(() -> {
            ((MenuBar) node("#main-menu"))
                    .getMenus().stream()
                            .filter(menu -> "menu-printers".equals(menu.getId()))
                            .findFirst()
                            .orElseThrow()
                            .getItems()
                            .stream()
                            .filter(item -> "printers-reload".equals(item.getId()))
                            .findFirst()
                            .orElseThrow()
                            .fire();
            return null;
        });
        ready();
        var selected = document().printSetup();
        assertThat(selected.printerId()).isEqualTo("bambu-lab-a1-mini");
        shortcut(KeyCode.Z, false);
        ready();
        assertThat(document().printSetup()).isEqualTo(original);
        shortcut(KeyCode.Z, true);
        ready();
        assertThat(document().printSetup()).isEqualTo(selected);
        Path saved = directory.resolve("printer-snapshot.vxb");
        save(saved);
        open(saved);
        ready();
        assertThat(document().printSetup()).isEqualTo(selected);
        click("workspace-print");
        ready();
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "printer-selector.png"));
            return null;
        });
    }

    @Test
    void slicerSelectedObjectNameStaysAbovePlateObject() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        fx(() -> {
            workbench
                    .controller()
                    .renameBody(document().printSetup().instances().getFirst().bodyId(), "Foo");
            return null;
        });
        ready();
        for (String view : List.of("print-view-2d", "print-view-3d")) {
            click(view);
            ready();
            fx(() -> {
                robot.mouseMove(centerOf("print-canvas"));
                robot.mouseClick(MouseButton.PRIMARY);
                return null;
            });
            ready();
            fx(() -> {
                robot.mouseMove(centerOf("print-add"));
                return null;
            });
            Thread.sleep(1200);
            fx(() -> {
                var label = (Label) node("#print-selected-object-name");
                assertThat(label.isVisible())
                        .as("Selection name stays visible without hovering")
                        .isTrue();
                assertThat(label.getText()).isEqualTo("Foo (1)");
                var bounds = label.localToScreen(label.getBoundsInLocal());
                var object = centerOf("print-canvas");
                assertThat(bounds.getMaxY() < object.getY())
                        .as("Name appears above the selected object")
                        .isTrue();
                assertThat(bounds.getMinX() < object.getX() && bounds.getMaxX() > object.getX())
                        .isTrue();
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"))
                                .resolve(view + "-selected-object-name.png"));
                return null;
            });
        }
        click("print-add-plate");
        ready();
        assertThat(fx(() -> workbench.lookup("#print-selected-object-name").isVisible()))
                .as("Empty plate clears the name")
                .isFalse();
    }

    @Test
    void slicerSelectedObjectTooltipAppearsOnHover() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        for (String view : List.of("print-view-2d", "print-view-3d")) {
            click(view);
            ready();
            String id = "print-canvas";
            fx(() -> {
                robot.mouseMove(centerOf(id));
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!fx(() -> Window.getWindows().stream()
                            .anyMatch(window -> window instanceof Tooltip tip
                                    && tip.isShowing()
                                    && tip.getText().equals("Body 1 (1)")))
                    && System.nanoTime() < deadline) Thread.sleep(40);
            assertThat(fx(() -> Window.getWindows().stream()
                            .anyMatch(window -> window instanceof Tooltip tip
                                    && tip.isShowing()
                                    && tip.getText().equals("Body 1 (1)"))))
                    .as(id + " shows the selected object name")
                    .isTrue();
            fx(() -> {
                for (var window : List.copyOf(Window.getWindows()))
                    if (window instanceof Tooltip && window.isShowing())
                        Screenshot.save(
                                window.getScene(),
                                Path.of(System.getProperty("voxelbrew.ui.artifacts"))
                                        .resolve(view + "-object-tooltip.png"));
                robot.mouseMove(centerOf("print-add"));
                return null;
            });
            Thread.sleep(300);
            fx(() -> {
                var canvas = node("#print-canvas");
                robot.mouseMove(canvas.localToScreen(15, 15));
                return null;
            });
            Thread.sleep(1200);
            assertThat(fx(() -> Window.getWindows().stream()
                            .anyMatch(window -> window instanceof Tooltip tip
                                    && tip.isShowing()
                                    && tip.getText().equals("Body 1 (1)"))))
                    .as("Empty plate space has no object tooltip")
                    .isFalse();
        }
    }

    @Test
    void slicerGeometryExportUsesFileMenu() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        Path target = directory.resolve("geometry.stl");
        click("export");
        chooseExporter("stl");
        text("export-path", target.toString());
        click("export-confirm");
        ready();
        assertThat(Files.exists(target)).isTrue();
    }

    @Test
    void sliceAllPlatesStoresSeparateJobsWithoutChangingSelection() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        click("print-add-plate");
        ready();
        click("print-add");
        ready();
        Path saved = directory.resolve("all-plates.vxb");
        save(saved);
        var original = document().printSetup();
        click("print-tab-job");
        click("print-slice-all");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().running()) && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertThat(fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()))
                .isEqualTo(PrintController.State.SUCCEEDED);
        fx(() -> {
            var slider = (Slider) node("#print-layer");
            assertThat(slider.getMax() > 0).isTrue();
            assertThat(slider.getValue())
                    .as("New slice opens at the final layer")
                    .isEqualTo(slider.getMax());
            return null;
        });
        assertThat(document().printSetup().activePlateId()).isEqualTo(original.activePlateId());
        assertThat(document().gcode().keySet())
                .isEqualTo(original.plates().stream().map(PrintPlate::id).collect(java.util.stream.Collectors.toSet()));
        assertThat(Files.exists(directory.resolve("all-plates-1-Plate 1.gcode")))
                .isFalse();
        assertThat(Files.exists(directory.resolve("all-plates-2-Plate 2.gcode")))
                .isFalse();
    }

    @Test
    void autoArrangeMovesActivePlateAndUndoesTogether() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        click("print-add");
        ready();
        var original = document().printSetup();
        click("print-auto-arrange");
        ready();
        var arranged = document().printSetup();
        assertThat(arranged).isNotEqualTo(original);
        assertThat(arranged.instances().size()).isEqualTo(original.instances().size());
        var a = arranged.instances().get(0).transform().matrix();
        var b = arranged.instances().get(1).transform().matrix();
        assertThat(Math.abs(a[3] - b[3]) >= 65 - 1e-7 || Math.abs(a[7] - b[7]) >= 45 - 1e-7)
                .isTrue();
        shortcut(KeyCode.Z, false);
        ready();
        assertThat(document().printSetup()).isEqualTo(original);
    }

    @Test
    void slicerInstanceNamesFollowBodyRenamesAndUndo() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        var setup = document().printSetup();
        var body = setup.instances().getFirst().bodyId();
        fx(() -> {
            workbench.controller().renameBody(body, "Foo");
            return null;
        });
        ready();
        assertThat(document().printSetup())
                .as("Renaming preserves instance identities")
                .isEqualTo(setup);
        assertThat(fx(() ->
                        ((ComboBox<?>) node("#print-instance")).getButtonCell().getText()))
                .isEqualTo("Foo (1)");
        shortcut(KeyCode.Z, false);
        ready();
        assertThat(fx(() ->
                        ((ComboBox<?>) node("#print-instance")).getButtonCell().getText()))
                .isEqualTo("Body 1 (1)");
    }

    @Test
    void slicerRotationGizmosPreviewCommitUndoAndCancel() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        var original = document().printSetup();
        assertThat(fx(() -> ((CheckBox) node("#print-show-rotation-gizmos")).isSelected()))
                .isFalse();
        assertThat(slicerHasRing("#728ef5")).isFalse();
        click("print-show-rotation-gizmos");
        for (String view : List.of("print-view-2d", "print-view-3d")) {
            click(view);
            assertThat(slicerHasRing("#728ef5")).isTrue();
            assertThat(slicerHasRing("#e46969")).isEqualTo(view.equals("print-view-3d"));
            assertThat(slicerHasRing("#57ad74")).isEqualTo(view.equals("print-view-3d"));
            var start = fx(() -> {
                var canvas = (javafx.scene.canvas.Canvas) node("#print-canvas");
                var image = canvas.snapshot(null, null);
                Point2D best = null;
                for (int y = 0; y < image.getHeight(); y++)
                    for (int x = 0; x < image.getWidth(); x++) {
                        var color = image.getPixelReader().getColor(x, y);
                        if (Math.abs(color.getRed() - 114 / 255.0) < .025
                                && Math.abs(color.getGreen() - 142 / 255.0) < .025
                                && color.getBlue() > .94
                                && (best == null || x - y > best.getX() - best.getY())) best = new Point2D(x, y);
                    }
                assertThat(best).as("Z ring is rendered").isNotNull();
                return canvas.localToScreen(best);
            });
            for (boolean cancel : List.of(false, true)) {
                fx(() -> {
                    robot.mouseMove(start);
                    return null;
                });
                Thread.sleep(150);
                fx(() -> {
                    robot.keyPress(KeyCode.SHIFT);
                    robot.mousePress(MouseButton.PRIMARY);
                    return null;
                });
                Thread.sleep(100);
                fx(() -> {
                    robot.mouseMove(start.add(10, 45));
                    return null;
                });
                Thread.sleep(150);
                assertThat(document().printSetup())
                        .as("Rotation preview is transient")
                        .isEqualTo(original);
                if (cancel) key(KeyCode.ESCAPE);
                fx(() -> {
                    robot.mouseRelease(MouseButton.PRIMARY);
                    robot.keyRelease(KeyCode.SHIFT);
                    return null;
                });
                ready();
                if (cancel) assertThat(document().printSetup()).isEqualTo(original);
                else {
                    assertThat(document().printSetup())
                            .as("Ring drag commits rotation")
                            .isNotEqualTo(original);
                    var matrix = document()
                            .printSetup()
                            .instances()
                            .getFirst()
                            .transform()
                            .matrix();
                    double angle = Math.toDegrees(Math.atan2(matrix[4], matrix[0]));
                    assertThat(angle).isCloseTo(Math.rint(angle / 15) * 15, org.assertj.core.data.Offset.offset(1e-7));
                    Path saved = directory.resolve("rotated-" + view + ".vxb");
                    save(saved);
                    assertThat(DocumentReader.load(saved).printSetup())
                            .isEqualTo(document().printSetup());
                    fx(() -> {
                        Screenshot.save(
                                stage.getScene(),
                                Path.of(System.getProperty("voxelbrew.ui.artifacts"))
                                        .resolve("slicer-gizmos-" + view + ".png"));
                        return null;
                    });
                    shortcut(KeyCode.Z, false);
                    ready();
                    assertThat(document().printSetup())
                            .as("One undo restores rotation")
                            .isEqualTo(original);
                }
            }
        }
        text("print-rotate-x", "30");
        click("print-place");
        ready();
        var tilted = document().printSetup();
        long worldFrame = slicerCameraFrame();
        choice("print-rotation-space", 1);
        assertThat(slicerCameraFrame())
                .as("Local rings follow object orientation")
                .isNotEqualTo(worldFrame);
        assertThat(document().printSetup()).as("Coordinate mode is transient").isEqualTo(tilted);
        assertThat(fx(() -> ((CheckBox) node("#print-gizmo-local")).isSelected()))
                .isTrue();
        click("print-gizmo-local");
        assertThat(fx(() -> ((ComboBox<?>) node("#print-rotation-space")).getValue()))
                .isEqualTo(PrintRotationGizmo.Space.WORLD);
        assertThat(slicerCameraFrame())
                .as("World rings keep their plate alignment")
                .isEqualTo(worldFrame);
        click("print-gizmo-local");
        assertThat(fx(() -> ((ComboBox<?>) node("#print-rotation-space")).getValue()))
                .isEqualTo(PrintRotationGizmo.Space.LOCAL);
        assertThat(document().printSetup()).isEqualTo(tilted);
        choice("print-rotation-space", 0);
        shortcut(KeyCode.Z, false);
        ready();
        click("print-show-rotation-gizmos");
        assertThat(slicerHasRing("#728ef5")).isFalse();
        assertThat(slicerHasRing("#e46969")).isFalse();
        assertThat(slicerHasRing("#57ad74")).isFalse();
        assertThat(document().printSetup()).isEqualTo(original);
    }

    @Test
    void plateNamesEditInlineCancelUndoAndPersist() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        var original = document().printSetup();
        click("print-tab-job");
        doubleClickPlate("plate-1");
        key(KeyCode.ENTER);
        assertThat(document().printSetup())
                .as("Unchanged names do not modify the design")
                .isEqualTo(original);
        doubleClickPlate("plate-1");
        text("print-plate-name", "Assembly");
        key(KeyCode.ENTER);
        ready();
        assertThat(document().printSetup().activePlate().name()).isEqualTo("Assembly");
        assertThat(document().printSetup().instances()).isEqualTo(original.instances());
        assertThat(fx(() ->
                        ((ToggleButton) node("#print-plate-plate-1")).getText().startsWith("Assembly")))
                .isTrue();
        shortcut(KeyCode.Z, false);
        ready();
        assertThat(document().printSetup()).isEqualTo(original);
        doubleClickPlate("plate-1");
        text("print-plate-name", "Discarded");
        key(KeyCode.ESCAPE);
        assertThat(document().printSetup()).isEqualTo(original);
        assertThat(fx(() -> workbench.lookup("#print-plate-name"))).isNull();
        doubleClickPlate("plate-1");
        text("print-plate-name", "");
        key(KeyCode.ENTER);
        assertThat(fx(() -> node("#print-plate-name"))).isNotNull();
        assertThat(document().printSetup()).isEqualTo(original);
        click("print-view-2d");
        assertThat(fx(() -> workbench.lookup("#print-plate-name"))).isNull();
        click("print-add-plate");
        ready();
        doubleClickPlate("plate-1");
        text("print-plate-name", "Assembly");
        key(KeyCode.ENTER);
        ready();
        assertThat(document().printSetup().selectPlate("plate-1").activePlate().name())
                .isEqualTo("Assembly");
        Path saved = directory.resolve("named-plates.vxb");
        save(saved);
        var expected = document().printSetup();
        assertThat(DocumentReader.load(saved).printSetup()).isEqualTo(expected);
        open(saved);
        ready();
        assertThat(document().printSetup()).isEqualTo(expected);
        click("workspace-print");
        assertThat(fx(() ->
                        ((ToggleButton) node("#print-plate-plate-1")).getText().startsWith("Assembly")))
                .isTrue();
    }

    private boolean slicerHasRing(String hex) throws Exception {
        return fx(() -> {
            var expected = javafx.scene.paint.Color.web(hex);
            var image = node("#print-canvas").snapshot(null, null);
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) {
                    var color = image.getPixelReader().getColor(x, y);
                    if (Math.abs(color.getRed() - expected.getRed()) < .01
                            && Math.abs(color.getGreen() - expected.getGreen()) < .01
                            && Math.abs(color.getBlue() - expected.getBlue()) < .01) return true;
                }
            return false;
        });
    }

    private void doubleClickPlate(String id) throws Exception {
        var point = centerOf("print-plate-" + id);
        fx(() -> {
            robot.mouseMove(point);
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(90);
        fx(() -> {
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(180);
        assertThat(fx(() -> node("#print-plate-name"))).isNotNull();
    }

    @Test
    void slicerViewCubeNavigatesBothModesWithoutEditingPlacement() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        var initial = document();
        assertThat(fx(() -> node("#print-view-cube").isVisible())).isTrue();
        click("print-view-3d");
        var top = fx(() -> {
            var face = node("#print-view-cube").lookup("#view-cube-top");
            var bounds = face.localToScreen(face.getBoundsInLocal());
            return new javafx.geometry.Point2D(bounds.getCenterX(), bounds.getCenterY());
        });
        fx(() -> {
            robot.mouseMove(top);
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(150);
        assertThat(fx(() -> ((RadioButton) node("#print-view-2d")).isSelected()))
                .isTrue();
        click("print-view-3d");
        var home =
                fx(() -> (javafx.scene.control.Button) node("#print-view-cube").lookup("#view-cube-home"));
        fx(() -> {
            home.fire();
            return null;
        });
        assertThat(fx(() -> ((RadioButton) node("#print-view-3d")).isSelected()))
                .isTrue();
        assertThat(document()).as("View navigation must not edit placement").isEqualTo(initial);
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "slicer-view-cube.png"));
            return null;
        });
    }

    @Test
    void slicerCameraMatchesDesignerGesturesWithoutEditingPlacement() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        var initial = document();
        for (String view : List.of("print-view-2d", "print-view-3d")) {
            click(view);
            for (var button : List.of(MouseButton.SECONDARY, MouseButton.MIDDLE, MouseButton.PRIMARY)) {
                long before = slicerCameraFrame();
                slicerCameraGesture(button, false);
                assertThat(slicerCameraFrame()).as(view + " / " + button).isNotEqualTo(before);
                assertThat(document()).as("Camera changes are transient").isEqualTo(initial);
            }
            long before = slicerCameraFrame();
            slicerCameraGesture(MouseButton.MIDDLE, true);
            assertThat(slicerCameraFrame()).as("Shift+middle pans").isNotEqualTo(before);
            before = slicerCameraFrame();
            var center = centerOf("print-canvas");
            fx(() -> {
                robot.mouseMove(center);
                robot.mouseWheel(-4);
                return null;
            });
            Thread.sleep(150);
            assertThat(slicerCameraFrame()).as("Wheel zooms").isNotEqualTo(before);
            assertThat(document()).isEqualTo(initial);
            click("print-fit-view");
            var part = fx(() -> {
                var canvas = (javafx.scene.canvas.Canvas) node("#print-canvas");
                var image = canvas.snapshot(null, null);
                double x = 0, y = 0;
                int count = 0;
                for (int row = 0; row < image.getHeight(); row++)
                    for (int col = 0; col < image.getWidth(); col++) {
                        var color = image.getPixelReader().getColor(col, row);
                        if (color.getBlue() > .5 && color.getGreen() > .4 && color.getRed() < .4) {
                            x += col;
                            y += row;
                            count++;
                        }
                    }
                assertThat(count > 10)
                        .as("Object is visible after camera navigation")
                        .isTrue();
                return canvas.localToScreen(x / count, y / count);
            });
            fx(() -> {
                robot.mouseMove(part);
                robot.mousePress(MouseButton.PRIMARY);
                return null;
            });
            Thread.sleep(80);
            fx(() -> {
                robot.mouseMove(part.add(25, -15));
                return null;
            });
            Thread.sleep(80);
            fx(() -> {
                robot.mouseRelease(MouseButton.PRIMARY);
                return null;
            });
            ready();
            assertThat(document().printSetup())
                    .as("Object dragging works after camera navigation")
                    .isNotEqualTo(initial.printSetup());
            shortcut(KeyCode.Z, false);
            ready();
            assertThat(document()).as("Camera gestures do not add undo steps").isEqualTo(initial);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"))
                                .resolve("slicer-camera-" + view + ".png"));
                return null;
            });
        }
    }

    private void slicerCameraGesture(MouseButton button, boolean shift) throws Exception {
        var start = fx(() -> node("#print-canvas").localToScreen(15, 15));
        fx(() -> {
            robot.mouseMove(start);
            if (shift) robot.keyPress(KeyCode.SHIFT);
            robot.mousePress(button);
            return null;
        });
        Thread.sleep(80);
        fx(() -> {
            robot.mouseMove(start.add(40, 20));
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mouseRelease(button);
            if (shift) robot.keyRelease(KeyCode.SHIFT);
            return null;
        });
        Thread.sleep(100);
    }

    private long slicerCameraFrame() throws Exception {
        return fx(() -> {
            var image = node("#print-canvas").snapshot(null, null);
            long hash = 1;
            for (int y = 0; y < image.getHeight(); y += 2)
                for (int x = 0; x < image.getWidth(); x += 2)
                    hash = 31 * hash + image.getPixelReader().getArgb(x, y);
            return hash;
        });
    }

    @Test
    void slicerPlateDraggingAndMultiplePlatesPersist() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        var before = document().printSetup().instances().getFirst();
        var center = centerOf("print-canvas");
        fx(() -> {
            robot.mouseMove(center);
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(120);
        fx(() -> {
            robot.mouseMove(center.add(35, -20));
            return null;
        });
        Thread.sleep(120);
        assertThat(document().printSetup().instances().getFirst())
                .as("Drag stays transient")
                .isEqualTo(before);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        ready();
        var moved = document().printSetup().instances().getFirst();
        assertThat(moved.transform().matrix()[3] > before.transform().matrix()[3])
                .isTrue();
        assertThat(moved.transform().matrix()[7] > before.transform().matrix()[7])
                .isTrue();
        shortcut(KeyCode.Z, false);
        ready();
        assertThat(document().printSetup().instances().getFirst())
                .as("One undo restores placement")
                .isEqualTo(before);
        final var dragCenter = centerOf("print-canvas");
        fx(() -> {
            robot.mouseMove(dragCenter);
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        fx(() -> {
            robot.mouseMove(dragCenter.add(25, 20));
            return null;
        });
        key(KeyCode.ESCAPE);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        assertThat(document().printSetup().instances().getFirst())
                .as("Escape discards placement")
                .isEqualTo(before);
        click("print-add-plate");
        ready();
        assertThat(document().printSetup().plates().size()).isEqualTo(2);
        assertThat(document().printSetup().instances().isEmpty()).isTrue();
        click("print-add");
        ready();
        assertThat(document().printSetup().instances().size()).isEqualTo(1);
        click("print-plate-plate-1");
        ready();
        assertThat(document().printSetup().instances().getFirst()).isEqualTo(before);
        Path saved = directory.resolve("multiple-plates.vxb");
        save(saved);
        assertThat(DocumentReader.load(saved).printSetup()).isEqualTo(document().printSetup());
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"))
                                .resolve("slicer-plates-" + palette + ".png"));
                return null;
            });
        }
        click("print-tab-process");
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts")).resolve("slicer-process-help.png"));
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> node("#print-slice").isDisabled()) && System.nanoTime() < deadline) Thread.sleep(25);
        click("print-slice");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().running()) && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertThat(fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()))
                .isEqualTo(PrintController.State.SUCCEEDED);
        assertThat(document().activeGcode().isEmpty()).isFalse();
        assertThat(Files.exists(saved.resolveSibling("multiple-plates-1-Plate 1.gcode")))
                .isFalse();
        click("print-legend");
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"))
                                .resolve("slicer-preview-" + palette + ".png"));
                return null;
            });
        }
        click("print-preview");
        assertThat(fx(() -> workbench.lookup("#print-layer").getParent().isVisible()))
                .isFalse();
    }

    @Test
    void slicerMenuPersistsSeparateProviderSettingsAndDiscardsUnsavedEdits() throws Exception {
        ready();
        click("menu-slicer");
        click("slicer-settings");
        click("slicer-settings-prusa-slicer");
        text("slicer-option-prusa-slicer-executable", "C:/Slicers/prusa.exe");
        click("slicer-settings-cura");
        text("slicer-option-cura-executable", "C:/Slicers/CuraEngine.exe");
        text("slicer-option-cura-definition", "C:/Slicers/machine.json");
        text("slicer-option-cura-settings", "C:/Slicers/process.properties");
        click("slicer-settings-save");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fx(() -> node("#slicer-settings-save").isDisabled()) && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(fx(() -> ((Label) node("#slicer-settings-status")).getText().contains("saved")))
                .isTrue();
        long probeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> ((Label) node("#slicer-status-cura")).getText().contains("Checking"))
                && System.nanoTime() < probeDeadline) Thread.sleep(25);
        assertThat(fx(() -> ((Label) node("#slicer-status-cura")).getText().contains("Not working")))
                .isTrue();
        fx(() -> {
            var statusPanel = node("#slicer-status-cura").getParent();
            assertThat(statusPanel.getStyleClass().contains("unavailable")).isTrue();
            var saveButton = (Button) node("#slicer-settings-save");
            var cancelButton = (Button) node("#slicer-settings-close");
            assertThat(cancelButton.getParent()).isSameAs(saveButton.getParent());
            assertThat(saveButton.isDefaultButton()).isTrue();
            assertThat(cancelButton.getText()).isEqualTo("Cancel");
            assertThat(cancelButton
                            .localToScene(cancelButton.getBoundsInLocal())
                            .getCenterY())
                    .isCloseTo(
                            saveButton
                                    .localToScene(saveButton.getBoundsInLocal())
                                    .getCenterY(),
                            org.assertj.core.data.Offset.offset(1.0));
            return null;
        });
        assertThat(fx(() -> workbench.lookup("#print-slice").isDisabled())).isTrue();
        assertThat(fx(() -> workbench.lookup("#print-slice-all").isDisabled())).isTrue();
        assertThat(configuration.section("slicer", "prusa-slicer").get("executable", ""))
                .isEqualTo("C:/Slicers/prusa.exe");
        assertThat(configuration.section("slicer", "cura").get("executable", ""))
                .isEqualTo("C:/Slicers/CuraEngine.exe");
        assertThat(configuration.section("slicer", "cura").get("definition", ""))
                .isEqualTo("C:/Slicers/machine.json");
        assertThat(configuration.section("slicer", "cura").get("settings", ""))
                .isEqualTo("C:/Slicers/process.properties");
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "slicer-settings.png"));
            return null;
        });
        click("slicer-settings-close");
        click("menu-slicer");
        click("slicer-settings");
        click("slicer-settings-prusa-slicer");
        assertThat(fx(() -> ((TextField) node("#slicer-option-prusa-slicer-executable")).getText()))
                .isEqualTo("C:/Slicers/prusa.exe");
        text("slicer-option-prusa-slicer-executable", "discarded");
        key(KeyCode.ESCAPE);
        assertThat(configuration.section("slicer", "prusa-slicer").get("executable", ""))
                .isEqualTo("C:/Slicers/prusa.exe");
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
    }

    @Test
    void extrusionRegionChoiceKeepsOnlyRectangle() throws Exception {
        var rectangle = Sketches.rectangle("r", 0, 0, 20, 10, false);
        var entities = new ArrayList<>(rectangle.entities());
        entities.add(Sketches.entity("roof-left", SketchGeometry.Kind.LINE, false, 0, 10, 10, 20));
        entities.add(Sketches.entity("roof-right", SketchGeometry.Kind.LINE, false, 10, 20, 20, 10));
        var geometry = new SketchGeometry(
                entities,
                rectangle.constraints(),
                entities.stream().map(SketchGeometry.Entity::id).toList());
        var sketch = new Feature.Sketch(
                "house", "profile", new Feature.Plane("origin-xy", 0), new Feature.Constrained(geometry));
        var file = directory.resolve("adjacent-regions.vxb");
        DocumentWriter.save(new CadDocument("regions", List.of(sketch), 1), file, false);
        open(file);
        click("history-0");
        click("extrude");
        ready();
        assertThat(volume()).isCloseTo(1500, org.assertj.core.data.Offset.offset(.001));
        choice("extrude-region", 1);
        ready();
        assertThat(volume()).isCloseTo(1000, org.assertj.core.data.Offset.offset(.001));
        choice("extrude-region", 2);
        ready();
        assertThat(volume()).isCloseTo(500, org.assertj.core.data.Offset.offset(.001));
        choice("extrude-region", 1);
        text("extrude-distance", "8");
        key(KeyCode.ENTER);
        ready();
        assertThat(volume()).isCloseTo(1600, org.assertj.core.data.Offset.offset(.001));
        assertThat(document().features().getFirst()).isEqualTo(sketch);
        click("undo");
        ready();
        assertThat(document().features()).isEqualTo(List.of(sketch));
    }

    @Test
    void constructionMenuCreatesEditablePlaneAndCancelsDraftWithoutHistory() throws Exception {
        ready();
        click("construction-menu");
        assertThat(fx(() -> ((MenuButton) node("#construction-menu")).isShowing()))
                .as("Construct menu stays open after mouse click")
                .isTrue();
        assertThat(fx(() -> ((MenuButton) node("#construction-menu"))
                                .getItems().stream()
                                        .filter(item -> !(item instanceof SeparatorMenuItem))
                                        .count())
                        .intValue())
                .isEqualTo(20);
        click("construct-offset-plane");
        choice("construction-input-0", 4);
        text("construction-value", "12");
        click("construction-preview");
        Thread.sleep(500);
        assertThat(document().features().isEmpty())
                .as("Preview does not create a history entry")
                .isTrue();
        click("construction-apply");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (document().features().isEmpty() && System.nanoTime() < deadline) Thread.sleep(40);
        ready();
        assertThat(document().features().size()).isEqualTo(1);
        var feature = (ConstructionFeature) document().features().getFirst();
        assertThat(feature.value()).isEqualTo(12);
        assertThat(fx(() -> ((dev.voxelbrew.api.model.construction.ConstructionGeometry.Plane)
                                workbench.controller().build().construction().get(feature.id()))
                        .origin()
                        .z()))
                .isEqualTo(12);
        click("history-0");
        click("construction-edit");
        text("construction-value", "20");
        click("construction-cancel");
        assertThat(document().features().getFirst()).isEqualTo(feature);
        click("construction-menu");
        click("construct-point-vertex");
        key(KeyCode.ESCAPE);
        assertThat(document().features().size()).isEqualTo(1);
        shortcut(KeyCode.Z, false);
        ready();
        assertThat(document().features().isEmpty()).isTrue();
        shortcut(KeyCode.Z, true);
        ready();
        assertThat(document().features().getFirst()).isEqualTo(feature);
        fx(() -> {
            workbench
                    .controller()
                    .replaceFeature(new ConstructionFeature(
                            feature.id(),
                            feature.tool(),
                            List.of(new ConstructionFeature.Input(
                                    ConstructionFeature.Selection.DATUM,
                                    "missing",
                                    "",
                                    0,
                                    new dev.voxelbrew.api.model.construction.ConstructionGeometry.Vector(0, 0, 0))),
                            12,
                            false));
            return null;
        });
        ready();
        assertThat(fx(() -> workbench.controller().build().valid())).isFalse();
        click("history-0");
        click("construction-edit");
        choice("construction-input-0", 4);
        click("construction-apply");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!fx(() -> workbench.controller().build().valid()) && System.nanoTime() < deadline) Thread.sleep(40);
        ready();
        assertThat(fx(() -> workbench.controller().build().valid()))
                .as("Broken construction can be repaired in its editor")
                .isTrue();
        assertThat(document().features().getFirst()).isEqualTo(feature);
    }

    @Test
    void fullyConstrainedLinesAndPointsRenderBlackWhileFreeGeometryStaysBlue() throws Exception {
        var a = Sketches.entity("fixed", SketchGeometry.Kind.LINE, false, 10, 10, 30, 10);
        var b = Sketches.entity("free", SketchGeometry.Kind.LINE, false, 10, 30, 30, 30);
        var c = Sketches.entity("partial", SketchGeometry.Kind.LINE, false, 40, 10, 60, 10);
        var g = new SketchGeometry(List.of(a, b, c), List.of(), List.of());
        for (var constraint : List.of(
                dev.voxelbrew.engine.sketch.SketchConstraints.create(
                        g,
                        "x",
                        SketchGeometry.Type.DISTANCE_X,
                        List.of(SketchGeometry.ORIGIN, a.ref(SketchGeometry.Part.START)),
                        10),
                dev.voxelbrew.engine.sketch.SketchConstraints.create(
                        g,
                        "y",
                        SketchGeometry.Type.DISTANCE_Y,
                        List.of(SketchGeometry.ORIGIN, a.ref(SketchGeometry.Part.START)),
                        10),
                dev.voxelbrew.engine.sketch.SketchConstraints.create(
                        g, "length", SketchGeometry.Type.LENGTH, List.of(a.ref()), 20),
                dev.voxelbrew.engine.sketch.SketchConstraints.create(
                        g, "horizontal", SketchGeometry.Type.HORIZONTAL, List.of(a.ref()), 0),
                dev.voxelbrew.engine.sketch.SketchConstraints.create(
                        g, "partial-anchor", SketchGeometry.Type.FIX, List.of(c.ref(SketchGeometry.Part.START)), 0)))
            g = g.add(constraint);
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch("sketch", "profile", Feature.Plane.origin("XY"), new Feature.Constrained(g)));
        Path file = directory.resolve("constraint-colors.vxb");
        DocumentWriter.save(session.document(), file, false);
        open(file);
        choice("appearance", 1);
        click("history-0");
        click("edit-sketch-constraints");
        sketchReady();
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor"))
                                .controller()
                                .result()
                                .degreesOfFreedom()
                        > 0))
                .isTrue();
        assertSketchGeometryColor(20, 10, true);
        assertSketchGeometryColor(10, 10, true);
        assertSketchGeometryColor(20, 30, false);
        assertSketchGeometryColor(40, 10, true);
        assertSketchGeometryColor(50, 10, false);
        sketchReference("fixed");
        assertThat(fx(() -> ((Label) node("#sketch-selection")).getText().isBlank()))
                .isFalse();
        click("sketch-clear-selection");
        assertSketchGeometryColor(20, 10, true);
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "sketch-constraint-colors.png"));
            return null;
        });
        click("constraint-cancel");
    }

    private void assertSketchGeometryColor(double x, double y, boolean black) throws Exception {
        boolean matches = fx(() -> {
            var canvas = (javafx.scene.canvas.Canvas) node("#sketch-canvas");
            var pixel = canvas.screenToLocal(
                    ((SketchEditor) node("#sketch-editor")).modelToScreen(new SketchGeometry.Vec(x, y)));
            var image = canvas.snapshot(null, null);
            for (int dx = -2; dx <= 2; dx++)
                for (int dy = -2; dy <= 2; dy++) {
                    var color = image.getPixelReader().getColor((int) pixel.getX() + dx, (int) pixel.getY() + dy);
                    if (black
                            ? color.getBrightness() < .4 && Math.abs(color.getRed() - color.getBlue()) < .04
                            : color.getBlue() > color.getRed() + .2 && color.getBlue() > .4) return true;
                }
            return false;
        });
        assertThat(matches)
                .as((black ? "Constrained black" : "Unconstrained blue") + " geometry at " + x + ", " + y)
                .isTrue();
    }

    @Test
    void dimensionLabelsAndLinesDragWithoutChangingGeometryAndEscapeRestoresPlacement() throws Exception {
        ready();
        rectangleDraft(60, 40, 0);
        click("sketch-tool-select");
        key(KeyCode.D);
        sketchClick(0, 0, false);
        sketchClick(0, 40, false);
        text("sketch-inline-dimension", "40");
        key(KeyCode.ENTER);
        sketchReady();
        key(KeyCode.ESCAPE);
        var before = sketchGeometry();
        double x = fx(() -> {
            var editor = (SketchEditor) node("#sketch-editor");
            return 20
                    / (editor.modelToScreen(new SketchGeometry.Vec(1, 20)).getX()
                            - editor.modelToScreen(new SketchGeometry.Vec(0, 20))
                                    .getX());
        });
        sketchDrag(x, 20, x + 10, 25, false);
        assertDimensionLineInk(x + 10, 8, true);
        assertDimensionLineInk(x, 8, false);
        assertThat(sketchGeometry()).isEqualTo(before);
        sketchDrag(x + 10, 8, x + 20, 8, false);
        assertDimensionLineInk(x + 20, 8, true);
        assertDimensionLineInk(x + 10, 8, false);
        sketchDrag(x + 20, 8, x + 30, 8, true);
        assertDimensionLineInk(x + 20, 8, true);
        assertDimensionLineInk(x + 30, 8, false);
        assertThat(sketchGeometry()).isEqualTo(before);
        click("sketch-undo");
        sketchReady();
        assertThat(sketchGeometry().constraints().stream().anyMatch(c -> c.type() == SketchGeometry.Type.DISTANCE_Y))
                .isFalse();
        click("constraint-cancel");
    }

    private void assertDimensionLineInk(double x, double y, boolean expected) throws Exception {
        boolean ink = fx(() -> {
            var canvas = (javafx.scene.canvas.Canvas) node("#sketch-canvas");
            var editor = (SketchEditor) node("#sketch-editor");
            var pixel = canvas.screenToLocal(editor.modelToScreen(new SketchGeometry.Vec(x, y)));
            var image = canvas.snapshot(null, null);
            for (int dx = -2; dx <= 2; dx++)
                for (int dy = -2; dy <= 2; dy++)
                    if (image.getPixelReader()
                                    .getColor((int) pixel.getX() + dx, (int) pixel.getY() + dy)
                                    .getBrightness()
                            < .65) return true;
            return false;
        });
        assertThat(ink).as("Dimension line at " + x + ", " + y).isEqualTo(expected);
    }

    @Test
    void standalonePointsOpenAndDriveActualPointToPointDistance() throws Exception {
        ready();
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        click("sketch-tool-point");
        sketchClick(10, 10, false);
        sketchReady();
        click("sketch-tool-point");
        sketchClick(40, 50, false);
        sketchReady();
        assertThat(sketchGeometry().entities().size()).isEqualTo(2);
        var placed = sketchGeometry().entities();
        double initialDistance = placed.get(0).at(0).distance(placed.get(1).at(0));
        click("sketch-tool-select");
        key(KeyCode.D);
        sketchClick(10, 10, false);
        sketchClick(40, 50, false);
        assertThat(fx(() -> ((FormulaField) node("#sketch-inline-dimension")).value()))
                .isCloseTo(initialDistance, org.assertj.core.data.Offset.offset(1e-4));
        text("sketch-inline-dimension", "60");
        key(KeyCode.ENTER);
        sketchReady();
        var g = sketchGeometry();
        var dimension = g.constraints().stream()
                .filter(c -> c.type() == SketchGeometry.Type.DISTANCE)
                .findFirst()
                .orElseThrow();
        assertThat(g.point(dimension.refs().get(0))
                        .distance(g.point(dimension.refs().get(1))))
                .isCloseTo(60, org.assertj.core.data.Offset.offset(1e-6));
        key(KeyCode.ESCAPE);
        click("constraint-cancel");
    }

    @Test
    void dimensionSnapsAndHighlightsNearbyLinesAndPointsWithoutAutoConstraints() throws Exception {
        ready();
        rectangleDraft(60, 40, 0);
        click("sketch-auto");
        click("sketch-tool-select");
        key(KeyCode.D);
        dimensionSnapClick(60, 40, 7, 0);
        String pointSelection = fx(() -> ((Label) node("#sketch-selection")).getText());
        assertThat(pointSelection.contains(".start") || pointSelection.contains(".end"))
                .as(pointSelection)
                .isTrue();
        assertThat(fx(() -> stage.getScene().lookup("#sketch-inline-dimension")))
                .as("Snapping selects the point instead of placing a label")
                .isNull();
        click("sketch-clear-selection");
        dimensionSnapClick(0, 20, -7, 0);
        assertThat(fx(() -> ((Label) node("#sketch-selection")).getText().contains("left")))
                .isTrue();
        dimensionSnapClick(60, 20, 7, 0);
        assertThat(fx(() -> ((Label) node("#sketch-selection")).getText().contains("right")))
                .isTrue();
        assertThat(fx(() -> node("#sketch-inline-dimension")))
                .as("Second line opens the field immediately")
                .isNotNull();
        String pair = fx(() -> ((Label) node("#sketch-selection")).getText());
        sketchClick(30, 0, false);
        assertThat(fx(() -> ((Label) node("#sketch-selection")).getText()))
                .as("Third line cannot change the active dimension")
                .isEqualTo(pair);
        text("sketch-inline-dimension", "80");
        key(KeyCode.ENTER);
        sketchReady();
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor"))
                        .controller()
                        .result()
                        .solved()))
                .isTrue();
        assertThat(sketchGeometry().constraints().stream()
                        .anyMatch(c -> c.type() == SketchGeometry.Type.DISTANCE
                                && c.values().getFirst() == 80))
                .isTrue();
        key(KeyCode.ESCAPE);
        click("constraint-cancel");
    }

    @Test
    void deletingClickedDimensionPreservesGeometryAndSupportsUndoRedo() throws Exception {
        ready();
        rectangleDraft(60, 40, 0);
        click("sketch-tool-select");
        key(KeyCode.D);
        sketchClick(0, 0, false);
        sketchClick(0, 40, false);
        text("sketch-inline-dimension", "40");
        key(KeyCode.ENTER);
        sketchReady();
        key(KeyCode.ESCAPE);
        var before = sketchGeometry();
        var dimension = before.constraints().stream()
                .filter(c -> c.type() == SketchGeometry.Type.DISTANCE_Y)
                .findFirst()
                .orElseThrow();
        // The point-pair dimension label is 20 screen pixels right of the midpoint.
        fx(() -> {
            var midpoint = ((SketchEditor) node("#sketch-editor")).modelToScreen(new SketchGeometry.Vec(0, 20));
            robot.mouseMove(midpoint.add(20, 0));
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(150);
        assertThat(fx(() -> ((ListView<?>) node("#sketch-constraints"))
                        .getSelectionModel()
                        .getSelectedItem()))
                .isEqualTo(dimension);
        key(KeyCode.DELETE);
        sketchReady();
        var after = sketchGeometry();
        assertThat(after.entities())
                .as("Deleting a dimension must preserve its geometry")
                .isEqualTo(before.entities());
        assertThat(after.boundary()).isEqualTo(before.boundary());
        assertThat(after.constraints())
                .isEqualTo(before.constraints().stream()
                        .filter(c -> !c.id().equals(dimension.id()))
                        .toList());
        click("sketch-undo");
        sketchReady();
        assertThat(sketchGeometry()).isEqualTo(before);
        click("sketch-redo");
        sketchReady();
        assertThat(sketchGeometry()).isEqualTo(after);
        sketchClick(30, 0, false);
        key(KeyCode.DELETE);
        sketchReady();
        assertThat(sketchGeometry().entities().size())
                .as("Selecting geometry afterwards must still allow geometry deletion")
                .isEqualTo(before.entities().size() - 1);
        click("constraint-cancel");
    }

    private void dimensionSnapClick(double x, double y, double dx, double dy) throws Exception {
        fx(() -> {
            var target = ((SketchEditor) node("#sketch-editor")).modelToScreen(new SketchGeometry.Vec(x, y));
            robot.mouseMove(target.add(dx, dy));
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        boolean highlighted;
        do {
            Thread.sleep(30);
            highlighted = fx(() -> {
                var canvas = (javafx.scene.canvas.Canvas) node("#sketch-canvas");
                var screen = ((SketchEditor) node("#sketch-editor")).modelToScreen(new SketchGeometry.Vec(x, y));
                var pixel = canvas.screenToLocal(screen);
                var image = canvas.snapshot(null, null);
                for (int ox = -2; ox <= 2; ox++)
                    for (int oy = -2; oy <= 2; oy++) {
                        var color = image.getPixelReader().getColor((int) pixel.getX() + ox, (int) pixel.getY() + oy);
                        if (color.getBlue() > .8 && color.getRed() < .3 && color.getGreen() > .4) return true;
                    }
                return false;
            });
        } while (!highlighted && System.nanoTime() < deadline);
        assertThat(highlighted)
                .as("Nearby dimension reference must visibly highlight before clicking")
                .isTrue();
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(60);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(150);
    }

    @Test
    void twoParallelLinesDimensionUsesInlineFormulaParametersAndPersists() throws Exception {
        ready();
        click("parameters");
        parameter("plateWidth", "80");
        click("parameters-apply");
        ready();
        rectangleDraft(60, 40, 0);
        click("sketch-tool-select");
        sketchClick(0, 20, false);
        sketchClick(60, 20, true);
        key(KeyCode.D);
        var field = fx(() -> node("#sketch-inline-dimension"));
        assertThat(field).isInstanceOf(FormulaField.class);
        assertThat(((FormulaField) field).expressionType()).isEqualTo(FormulaField.ExpressionType.NUMBER);
        assertThat(fx(() -> field.lookup(".formula-mark") != null)).isTrue();
        assertThat(fx(() -> field.getBoundsInLocal().getWidth() >= 200))
                .as("Inline input must display the expression, not just the fx mark")
                .isTrue();
        text("sketch-inline-dimension", "=missingParameter");
        key(KeyCode.ENTER);
        assertThat(fx(() -> node("#sketch-inline-dimension")))
                .as("Invalid input stays open for repair")
                .isNotNull();
        assertThat(fx(() -> ((Label) node("#sketch-error")).getText().isBlank()))
                .isFalse();
        focus(stage);
        text("sketch-inline-dimension", "=$plateW");
        key(KeyCode.TAB);
        assertThat(fx(() -> ((TextField) field).getText())).isEqualTo("=$plateWidth");
        fx(() -> {
            assertThat(((FormulaField) field).value()).isEqualTo(80);
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "inline-dimension-formula.png"));
            return null;
        });
        key(KeyCode.ENTER);
        sketchReady();
        assertThat(fx(() -> stage.getScene().lookup("#sketch-inline-dimension")))
                .isNull();
        var geometry = sketchGeometry();
        var distance = geometry.constraints().stream()
                .filter(c -> c.type() == SketchGeometry.Type.DISTANCE)
                .findFirst()
                .orElseThrow();
        assertThat(distance.values().getFirst()).isCloseTo(80, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor"))
                        .controller()
                        .result()
                        .solved()))
                .isTrue();
        dev.voxelbrew.engine.sketch.SketchConstraints.requireSatisfied(geometry);
        assertThat(fx(() -> ((Label) node("#sketch-error")).getText())).isEqualTo("");
        click("sketch-undo");
        sketchReady();
        assertThat(sketchGeometry().constraints().stream().anyMatch(c -> c.id().equals(distance.id())))
                .isFalse();
        click("sketch-redo");
        sketchReady();
        key(KeyCode.ESCAPE);
        click("constraint-finish");
        ready();
        var feature = (Feature.Sketch) document().features().getFirst();
        assertThat(document()
                        .parameters()
                        .binding(new ParameterSet.Target(feature.id(), "constraint:" + distance.id())))
                .isEqualTo("=$plateWidth");
        Path saved = directory.resolve("line-distance-formula.vxb");
        save(saved);
        open(saved);
        assertThat(document()
                        .parameters()
                        .binding(new ParameterSet.Target(feature.id(), "constraint:" + distance.id())))
                .isEqualTo("=$plateWidth");
    }

    @Test
    void secondPointImmediatelyOpensVerticalDimensionAndRejectsThirdReference() throws Exception {
        ready();
        rectangleDraft(60, 40, 0);
        click("sketch-tool-select");
        key(KeyCode.D);
        sketchClick(0, 0, false);
        assertThat(fx(() -> stage.getScene().lookup("#sketch-inline-dimension")))
                .isNull();
        sketchClick(0, 40, false);
        assertThat(fx(() -> ((FormulaField) node("#sketch-inline-dimension")).value()))
                .isCloseTo(40, org.assertj.core.data.Offset.offset(1e-6));
        String pair = fx(() -> ((Label) node("#sketch-selection")).getText());
        sketchClick(60, 0, false);
        assertThat(fx(() -> ((Label) node("#sketch-selection")).getText())).isEqualTo(pair);
        text("sketch-inline-dimension", "50");
        key(KeyCode.ENTER);
        sketchReady();
        var dimension = sketchGeometry().constraints().stream()
                .filter(c -> c.type() == SketchGeometry.Type.DISTANCE_Y)
                .findFirst()
                .orElseThrow();
        assertThat(dimension.refs().size()).isEqualTo(2);
        assertThat(dimension.values().getFirst()).isCloseTo(50, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor"))
                        .controller()
                        .result()
                        .solved()))
                .isTrue();
        key(KeyCode.ESCAPE);
        click("constraint-cancel");
    }

    @Test
    void technicalDrawingRenderingHidesRearEdgesKeepsPickingAndRemembersStyle() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch(
                "plate", "profile", Feature.Plane.origin("XY"), new Feature.Rectangle(0, 0, 60, 40)));
        session.add(new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "profile"), 8));
        session.add(new Feature.Sketch("hole", "profile", Feature.Plane.origin("XY"), new Feature.Circle(10, 10, 4)));
        session.add(new Feature.ThroughCut(
                "cut", new Feature.ProfileRef("hole", "profile"), new Feature.BodyRef("thickness", "body")));
        // A separate body completely behind the plate tests occlusion across bodies.
        session.add(new Feature.Sketch(
                "hidden", "profile", new Feature.Plane("offset", -5), new Feature.Rectangle(20, 10, 10, 10)));
        session.add(new Feature.Extrude("hidden-solid", new Feature.ProfileRef("hidden", "profile"), 2));
        session.setBodyStyle("thickness.body", new BodyStyle("PETG", "#FF0000"));
        session.add(new Feature.Sketch("boss", "profile", new Feature.Plane("top", 8), new Feature.Circle(42, 22, 12)));
        session.add(new Feature.Extrude("boss-solid", new Feature.ProfileRef("boss", "profile"), 10));
        Path file = directory.resolve("technical rendering.vxb");
        DocumentWriter.save(session.document(), file, false);
        open(file);
        var snapshot = fx(() -> workbench.controller().snapshot());
        var bytes = Files.readAllBytes(file);
        renderStyle("technical");
        standardView(1);
        click("history-3");
        Thread.sleep(250);
        fx(() -> {
            var view = workbench.viewport();
            assertThat(view.rendering()).isEqualTo(Rendering.Style.TECHNICAL);
            var scene = (SubScene) node("#viewport-scene");
            assertThat(scene.getFill()).isEqualTo(javafx.scene.paint.Color.WHITE);
            var image = scene.snapshot(null, null);
            for (var point : List.of(new Point3D(30, 20, 8), new Point3D(20, 15, -3), new Point3D(25, 10, -3))) {
                var screen = view.modelToScreen(point);
                var pixel = scene.screenToLocal(screen);
                var color = image.getPixelReader().getColor((int) pixel.getX(), (int) pixel.getY());
                assertThat(color.getBrightness() > .97)
                        .as("White face conceals internal triangles, grid and rear-body edges: " + color)
                        .isTrue();
            }
            var start = scene.screenToLocal(view.modelToScreen(new Point3D(0, 0, 8)));
            var end = scene.screenToLocal(view.modelToScreen(new Point3D(60, 0, 8)));
            for (int step = 1; step < 20; step++) {
                var pixel = start.add(end.subtract(start).multiply(step / 20.0));
                boolean ink = false;
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = -1; dy <= 1; dy++)
                        ink |= image.getPixelReader()
                                        .getColor((int) pixel.getX() + dx, (int) pixel.getY() + dy)
                                        .getBrightness()
                                < .6;
                assertThat(ink)
                        .as("Visible outline is continuous at sample " + step)
                        .isTrue();
            }
            var outline = findBodyView(view, "outline-thickness.body");
            assertThat(outline).isNotNull();
            assertThat(((javafx.scene.shape.TriangleMesh) outline.getMesh())
                                    .getFaces()
                                    .size()
                            > 0)
                    .isTrue();
            return null;
        });
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(snapshot);
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
        modelClick(35, 25, 8);
        assertThat(fx(() -> node("#body-material-name")))
                .as("Visible solid remains pickable through its outlines")
                .isNotNull();
        click("history-3");
        standardView(0);
        fx(() -> {
            var bounds = Screen.getPrimary().getVisualBounds();
            stage.setWidth(Math.min(1400, bounds.getWidth() - 60));
            stage.setHeight(Math.min(900, bounds.getHeight() - 80));
            stage.centerOnScreen();
            return null;
        });
        Thread.sleep(250);
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(
                                System.getProperty("voxelbrew.ui.artifacts"),
                                "technical-drawing-" + (palette == 1 ? "light" : "dark") + ".png"));
                return null;
            });
        }
        // The normal creation path remains available while this style is active.
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        click("constraint-cancel");
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(snapshot);
        renderStyle("shaded");
        assertThat(fx(() -> workbench.viewport().rendering())).isEqualTo(Rendering.Style.SHADED);
        assertThat(fx(() -> ((javafx.scene.paint.PhongMaterial)
                                findBodyView(workbench.viewport(), "body-thickness.body")
                                        .getMaterial())
                        .getDiffuseColor()))
                .isEqualTo(javafx.scene.paint.Color.RED);
        renderStyle("technical");
        assertThat(configuration.get("rendering.style", "")).isEqualTo("TECHNICAL");
        assertThat(configuration.get("external.setting", "")).isEqualTo("preserve me");
        fx(() -> {
            workbench.close();
            return null;
        });
        workbench.whenClosed().toCompletableFuture().get(20, TimeUnit.SECONDS);
        fx(() -> {
            workbench = Workbench.create(
                    stage,
                    directory.resolve("plugins with spaces"),
                    new dev.voxelbrew.util.di.ApplicationModule(configuration, updates::service),
                    new dev.voxelbrew.gui.FileIoModule());
            stage.setScene(workbench.createScene(964, 648));
            stage.toFront();
            stage.requestFocus();
            return null;
        });
        Thread.sleep(400);
        open(file);
        assertThat(fx(() -> workbench.viewport().rendering())).isEqualTo(Rendering.Style.TECHNICAL);
        assertThat(Files.readAllBytes(file)).containsExactly(bytes);
    }

    private void renderStyle(String style) throws Exception {
        click("menu-view");
        click("menu-rendering");
        click("rendering-" + style);
        Thread.sleep(200);
        assertThat(fx(() -> ((RadioMenuItem) command(workbench, "rendering-" + style)).isSelected()))
                .isTrue();
    }

    @Test
    void compactHistorySupportsPickingDraggingPlaybackAndIndependentUndo() throws Exception {
        openPrepared("rectangle-0.vxb");
        click("extrude");
        text("extrude-distance", "8");
        click("commit-extrusion");
        ready();
        circle();
        click("cut");
        click("cut-confirm");
        ready();
        var complete = document();
        assertThat(complete.cursor()).isEqualTo(4);
        click("history-1");
        assertThat(document())
                .as("Selecting an icon must not roll back the model")
                .isEqualTo(complete);
        assertThat(fx(() -> node("#history-1").getStyleClass().contains("history-selected")))
                .isTrue();
        assertThat(fx(() -> ((Button) node("#history-1")).getContentDisplay())).isEqualTo(ContentDisplay.GRAPHIC_ONLY);
        assertThat(fx(() -> ((Button) node("#history-1")).getTooltip().getText().contains("2 ·")))
                .isTrue();
        assertThat(fx(() -> node("#feature-timeline").getBoundsInParent().getHeight()) < 80)
                .isTrue();
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(
                                System.getProperty("voxelbrew.ui.artifacts"),
                                "timeline-" + (palette == 1 ? "light" : "dark") + ".png"));
                return null;
            });
        }
        click("timeline-first");
        ready();
        assertThat(document().cursor()).isEqualTo(0);
        assertThat(fx(() -> node("#constrained-sketch").isDisabled())).isTrue();
        assertThat(fx(() -> node("#history-0").getStyleClass().contains("history-future")))
                .isTrue();
        click("timeline-next");
        ready();
        assertThat(document().cursor()).isEqualTo(1);
        click("timeline-last");
        ready();
        click("timeline-previous");
        ready();
        assertThat(document().cursor()).isEqualTo(3);
        assertThat(volume()).isCloseTo(19200, org.assertj.core.data.Offset.offset(.01));
        var start = fx(() -> {
            var bounds = node("#history-cursor")
                    .localToScreen(node("#history-cursor").getBoundsInLocal());
            return new Point2D(bounds.getCenterX(), bounds.getCenterY());
        });
        fx(() -> {
            robot.mouseMove(start);
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        try {
            for (int step = 1; step <= 8; step++) {
                double x = start.getX() - 76.0 * step / 8;
                fx(() -> {
                    robot.mouseMove(x, start.getY());
                    return null;
                });
                Thread.sleep(50);
            }
        } finally {
            fx(() -> {
                robot.mouseRelease(MouseButton.PRIMARY);
                return null;
            });
        }
        ready();
        assertThat(document().cursor())
                .as("Dragging the marker snaps between feature icons")
                .isEqualTo(1);
        key(KeyCode.END);
        ready();
        assertThat(document()).isEqualTo(complete);
        click("undo");
        ready();
        assertThat(document().features().size())
                .as("Rollback must not create undo entries")
                .isEqualTo(3);
        click("redo");
        ready();
        assertThat(document()).isEqualTo(complete);
        click("timeline-play");
        click("timeline-play");
        int paused = document().cursor();
        Thread.sleep(1400);
        assertThat(document().cursor())
                .as("Pause cancels the pending playback tick")
                .isEqualTo(paused);
        click("history-cursor");
        key(KeyCode.HOME);
        ready();
        key(KeyCode.RIGHT);
        key(KeyCode.RIGHT);
        key(KeyCode.RIGHT);
        ready();
        assertThat(document().cursor())
                .as("Arrow navigation keeps focus on the marker")
                .isEqualTo(3);
        key(KeyCode.LEFT);
        ready();
        assertThat(document().cursor()).isEqualTo(2);
        key(KeyCode.HOME);
        ready();
        click("timeline-play");
        var visited = new HashSet<Integer>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            visited.add(document().cursor());
            if (document().cursor() == 4
                    && fx(() -> !node("#timeline-play")
                            .getPseudoClassStates()
                            .contains(javafx.css.PseudoClass.getPseudoClass("playing")))) break;
            Thread.sleep(40);
        }
        ready();
        assertThat(visited).isEqualTo(Set.of(0, 1, 2, 3, 4));
        assertThat(document()).isEqualTo(complete);
        assertThat(volume()).isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(.01));
    }

    @Test
    void historyScrollsLongDesignsAndStopsAtFailedOperations() throws Exception {
        ready();
        click("menu-fit");
        var session = new ModelSession(CadDocument.empty());
        for (int i = 0; i < 26; i++)
            session.add(new Feature.Sketch(
                    "s" + i, "p" + i, Feature.Plane.origin("XY"), new Feature.Rectangle(0, 0, 20, 10)));
        session.add(new Feature.Extrude("broken", new Feature.ProfileRef("missing", "missing"), 5));
        Path file = directory.resolve("long history.vxb");
        DocumentWriter.save(session.document(), file, false);
        open(file);
        assertThat(fx(() -> node("#timeline-play").isDisabled())).isTrue();
        assertThat(fx(() -> node("#history-26").getStyleClass().contains("history-error")))
                .isTrue();
        click("timeline-previous");
        ready();
        assertThat(document().cursor()).isEqualTo(26);
        assertThat(fx(() -> node("#timeline-play").isDisabled())).isFalse();
        assertThat(fx(() -> ((ScrollPane) node("#timeline-scroll")).getHvalue()) > .9)
                .isTrue();
        click("history-25");
        assertThat(fx(() -> node("#history-25").getStyleClass().contains("history-selected")))
                .isTrue();
        click("timeline-play");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (document().cursor() < 27 && System.nanoTime() < deadline) Thread.sleep(40);
        ready();
        assertThat(document().cursor()).isEqualTo(27);
        assertThat(fx(() -> node("#timeline-play")
                        .getPseudoClassStates()
                        .contains(javafx.css.PseudoClass.getPseudoClass("playing"))))
                .isFalse();
        assertThat(fx(() -> node("#timeline-play").isDisabled())).isTrue();
        click("timeline-first");
        ready();
        assertThat(fx(() -> ((ScrollPane) node("#timeline-scroll")).getHvalue()))
                .isCloseTo(0, org.assertj.core.data.Offset.offset(.01));
        click("history-0");
        assertThat(document().cursor()).isEqualTo(0);
        click("timeline-play");
        click("new");
        click("unsaved-discard");
        ready();
        assertThat(document().features().isEmpty()).isTrue();
        Thread.sleep(900);
        assertThat(document().cursor())
                .as("Changing documents cancels history playback")
                .isEqualTo(0);
    }

    @Test
    void sketchPlanePickerRejectsCurvedFacesAndObsoleteDocumentRevisions() throws Exception {
        ready();
        click("menu-fit");
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch("round", "profile", Feature.Plane.origin("XY"), new Feature.Circle(40, 30, 20)));
        session.add(new Feature.Extrude("cylinder", new Feature.ProfileRef("round", "profile"), 15));
        var file = directory.resolve("curved support.vxb");
        DocumentWriter.save(session.document(), file, false);
        open(file);
        var before = fx(() -> workbench.controller().snapshot());
        click("constrained-sketch");
        standardView(2);
        modelClick(40, 20, 8);
        assertThat(fx(() -> stage.getScene().lookup("#sketch-editor"))).isNull();
        assertThat(fx(() -> node("#sketch-plane-hint"))).isNotNull();
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(before);
        // An asynchronous external change must invalidate all picks, including origin targets.
        fx(() -> {
            workbench.controller().edit("cylinder", "distance", 20);
            return null;
        });
        ready();
        assertThat(fx(() -> workbench.lookup("#sketch-plane-hint"))).isNull();
        assertThat(fx(() -> ((SubScene) node("#viewport-scene")).getRoot().lookup("#sketch-plane-xy")))
                .isNull();
        assertThat(document().features().size()).isEqualTo(2);
        assertThat(((Feature.Extrude) document().features().getLast()).distance())
                .isEqualTo(20);
    }

    @Test
    void sketchPlanePickerCreatesAllOriginOrientationsAndCancelsWithoutHistory() throws Exception {
        ready();
        var empty = fx(() -> workbench.controller().snapshot());
        click("constrained-sketch");
        assertThat(fx(() -> node("#sketch-plane-hint"))).isNotNull();
        assertThat(fx(() -> stage.getScene().lookup("#sketch-editor"))).isNull();
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "sketch-origin-planes.png"));
            return null;
        });
        key(KeyCode.ESCAPE);
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(empty);
        int index = 0;
        for (String name : List.of("XY", "XZ", "YZ")) {
            click("constrained-sketch");
            // View perpendicular to the actual 3D pick target, then use a native mouse click.
            standardView(++index);
            var pick = Feature.Plane.origin(name).frame().apply(12, 12, 0);
            modelClick(pick[0], pick[1], pick[2]);
            sketchReady();
            sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
            sketchNumericPoint(0, 0);
            sketchNumericPoint(20, 10);
            click("constraint-finish");
            ready();
            assertThat(((Feature.Sketch) document().features().getFirst()).plane())
                    .isEqualTo(Feature.Plane.origin(name));
            click("extrude");
            text("extrude-distance", "-5");
            click("commit-extrusion");
            ready();
            assertThat(volume()).isCloseTo(1000, org.assertj.core.data.Offset.offset(.01));
            assertThat(document().features().size()).isEqualTo(2);
            var p = Feature.Plane.origin(name).frame().apply(10, 5, -2);
            try (var result = new ModelSession(document()).evaluate()) {
                assertThat(result.bodies().getFirst().solid().contains(p[0], p[1], p[2]))
                        .isTrue();
            }
            click("undo");
            ready();
            click("undo");
            ready();
            assertThat(document().features().isEmpty()).isTrue();
        }
    }

    @Test
    void bodyFaceSketchFollowsUpstreamEditAndSupportsPlaneRepair() throws Exception {
        openPrepared("plate-5mm.vxb");
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            click("constrained-sketch");
            standardView(1);
            fx(() -> {
                robot.mouseMove(workbench.viewport().modelToScreen(new Point3D(40, 30, 5)));
                return null;
            });
            Thread.sleep(180);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(
                                System.getProperty("voxelbrew.ui.artifacts"),
                                "sketch-plane-picker-" + (palette == 1 ? "light" : "dark") + ".png"));
                return null;
            });
            if (palette == 1) key(KeyCode.ESCAPE);
        }
        modelClick(40, 30, 5);
        sketchReady();
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        sketchNumericPoint(30, 25);
        sketchNumericPoint(40, 35);
        click("constraint-finish");
        ready();
        var attached = (Feature.Sketch) document().features().get(2);
        assertThat(attached.plane().support()).isNotNull();
        click("extrude");
        text("extrude-distance", "3");
        click("commit-extrusion");
        ready();
        Path saved = directory.resolve("face sketch.vxb");
        save(saved);
        open(saved);
        click("history-1");
        text("dimension-distance", "8");
        click("apply-distance");
        ready();
        assertThat(volume()).isCloseTo(19500, org.assertj.core.data.Offset.offset(.01));
        try (var result = new ModelSession(document()).evaluate()) {
            var boss = result.bodies().getLast().solid();
            assertThat(boss.bounds().minZ()).isCloseTo(8, org.assertj.core.data.Offset.offset(.001));
            assertThat(boss.bounds().maxZ()).isCloseTo(11, org.assertj.core.data.Offset.offset(.001));
        }
        click("history-2");
        click("change-sketch-plane");
        click("choose-plane-yz");
        ready();
        var repaired = (Feature.Sketch) document().features().get(2);
        assertThat(repaired.id()).isEqualTo(attached.id());
        assertThat(repaired.profile()).isEqualTo(attached.profile());
        assertThat(repaired.plane()).isEqualTo(Feature.Plane.origin("YZ"));
        click("undo");
        ready();
        assertThat(((Feature.Sketch) document().features().get(2)).plane()).isEqualTo(attached.plane());
        click("redo");
        ready();
        assertThat(document().features().get(2)).isEqualTo(repaired);
    }

    @TempDir
    Path directory;

    private Stage stage;
    private Workbench workbench;
    private static Robot robot;
    private static Appearance.Choice originalAppearance;
    private UpdateFixture updates;
    private dev.voxelbrew.util.config.UserConfiguration configuration;

    @BeforeAll
    static void toolkit() throws Exception {
        originalAppearance = new Appearance().load();
        var started = new CountDownLatch(1);
        Launcher.configureGraphics();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();
        fx(() -> {
            assertThat(Platform.isSupported(ConditionalFeature.SCENE3D))
                    .as("Real JavaFX 3D is required; use WSLg or Xvfb/Mesa")
                    .isTrue();
            robot = new Robot();
            return null;
        });
    }

    @AfterAll
    static void shutdown() {
        new Appearance().save(originalAppearance);
        Platform.exit();
    }

    @BeforeEach
    void window(TestInfo info) throws Exception {
        updates = new UpdateFixture(directory.resolve("update fixture"));
        configuration =
                new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("preferences.properties"));
        String testName = info.getTestMethod().orElseThrow().getName();
        boolean firstLaunch = testName.startsWith("firstLaunch");
        if (!firstLaunch) configuration.set("language", testName.startsWith("polish") ? "pl" : "en");
        configuration.set("external.setting", "preserve me");
        Path plugins = Files.createDirectory(directory.resolve("plugins with spaces"));
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("provider.jar"));
        fx(() -> {
            stage = new Stage();
            if (System.getProperty("os.name").startsWith("Windows")) stage.setAlwaysOnTop(true);
            Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
            try {
                if (firstLaunch) Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("pl-PL"));
                workbench = Workbench.create(
                        stage,
                        plugins,
                        new dev.voxelbrew.util.di.ApplicationModule(configuration, updates::service),
                        new dev.voxelbrew.gui.FileIoModule());
            } finally {
                Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
            }
            var screen = Screen.getPrimary().getVisualBounds();
            // Match the hosted Windows runner so clipped inspector fields require
            // scrolling.
            stage.setScene(workbench.createScene(
                    Math.min(964, screen.getWidth() - 60), Math.min(648, screen.getHeight() - 80)));
            stage.show();
            // JavaFX 21's Windows Robot converts coordinates using the nearest monitor
            // center. Keep the fixture away from edges shared with a different DPI screen.
            stage.setX(screen.getMinX() + (screen.getWidth() - stage.getWidth()) / 2);
            stage.setY(screen.getMinY() + (screen.getHeight() - stage.getHeight()) / 2);
            stage.toFront();
            stage.requestFocus();
            return null;
        });
        Thread.sleep(1200);
    }

    @AfterEach
    void close(TestInfo info) throws Exception {
        try {
            fx(() -> {
                if (stage != null && stage.isShowing())
                    Screenshot.save(
                            stage.getScene(),
                            Path.of(
                                    System.getProperty("voxelbrew.ui.artifacts"),
                                    info.getTestMethod().orElseThrow().getName() + "-last.png"));
                int index = 0;
                for (var window : new ArrayList<>(Window.getWindows()))
                    if (window != stage) {
                        if (window.getScene() != null)
                            Screenshot.save(
                                    window.getScene(),
                                    Path.of(
                                            System.getProperty("voxelbrew.ui.artifacts"),
                                            info.getTestMethod().orElseThrow().getName()
                                                    + "-dialog-"
                                                    + (index++)
                                                    + ".png"));
                        window.hide();
                    }
                if (workbench != null) workbench.close();
                if (stage != null) stage.hide();
                return null;
            });
            if (workbench != null) workbench.whenClosed().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } finally {
            if (updates != null) updates.close();
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    @Test
    void integratedHeaderMovesResizesMaximizesAndMinimizesWithoutEditing() throws Exception {
        ready();
        assertThat(fx(stage::getStyle)).isEqualTo(StageStyle.UNDECORATED);
        assertThat(fx(() -> stage.getScene().getY())).isCloseTo(0, org.assertj.core.data.Offset.offset(0.1));
        var snapshot = fx(() -> workbench.controller().snapshot());
        var start = windowBounds();
        // Leave room for the bottom-right resize on the runner's 1024 x 768 display.
        // Moving right would put that corner off screen, beyond the native mouse's reach.
        primaryDrag(centerOf("document-title"), -20, -10);
        var moved = windowBounds();
        assertThat(moved.getMinX()).isCloseTo(start.getMinX() - 20, org.assertj.core.data.Offset.offset(2.0));
        assertThat(moved.getMinY()).isCloseTo(start.getMinY() - 10, org.assertj.core.data.Offset.offset(2.0));
        var screen = fx(() -> Screen.getPrimary().getVisualBounds());
        assertThat(screen.contains(moved.getMaxX() + 33, moved.getMaxY() + 23))
                .as("The complete resize gesture must stay inside the primary display")
                .isTrue();
        primaryDrag(new Point2D(moved.getMaxX() - 2, moved.getMaxY() - 2), 35, 25);
        var resized = windowBounds();
        assertThat(resized.getWidth()).isCloseTo(moved.getWidth() + 35, org.assertj.core.data.Offset.offset(2.0));
        assertThat(resized.getHeight()).isCloseTo(moved.getHeight() + 25, org.assertj.core.data.Offset.offset(2.0));
        assertThat(resized.getMinX()).isCloseTo(moved.getMinX(), org.assertj.core.data.Offset.offset(2.0));
        primaryDrag(new Point2D(resized.getMinX() + 2, resized.getMinY() + 2), 15, 10);
        var restored = windowBounds();
        assertThat(restored.getMaxX()).isCloseTo(resized.getMaxX(), org.assertj.core.data.Offset.offset(2.0));
        assertThat(restored.getMaxY()).isCloseTo(resized.getMaxY(), org.assertj.core.data.Offset.offset(2.0));
        assertThat(restored.getWidth()).isCloseTo(resized.getWidth() - 15, org.assertj.core.data.Offset.offset(2.0));
        click("window-maximize");
        var maximized = windowBounds();
        assertThat(fx(() -> Screen.getScreens().stream()
                        .map(Screen::getVisualBounds)
                        .anyMatch(bounds -> Math.abs(bounds.getMinX() - maximized.getMinX()) <= 2
                                && Math.abs(bounds.getMinY() - maximized.getMinY()) <= 2
                                && Math.abs(bounds.getWidth() - maximized.getWidth()) <= 2
                                && Math.abs(bounds.getHeight() - maximized.getHeight()) <= 2)))
                .as("Maximized window should fill the visual bounds of a display")
                .isTrue();
        assertThat(fx(() -> node("#window-maximize").getAccessibleHelp().startsWith("Restore")))
                .isTrue();
        doubleClickHeader();
        assertWindowBounds(restored);
        doubleClickHeader();
        assertWindowBounds(maximized);
        primaryDrag(centerOf("document-title"), 35, 40);
        assertThat(windowBounds().getWidth()).isCloseTo(restored.getWidth(), org.assertj.core.data.Offset.offset(2.0));
        assertThat(windowBounds().getHeight())
                .isCloseTo(restored.getHeight(), org.assertj.core.data.Offset.offset(2.0));
        // Recenter before native input on the mixed-DPI Windows desktop.
        fx(() -> {
            stage.centerOnScreen();
            return null;
        });
        Thread.sleep(250);
        click("window-minimize");
        assertThat(fx(stage::isIconified)).isTrue();
        fx(() -> {
            stage.setIconified(false);
            stage.toFront();
            stage.requestFocus();
            return null;
        });
        Thread.sleep(400);
        assertThat(fx(stage::isIconified)).isFalse();
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(snapshot);
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
        assertButtonDescriptions();
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(
                                System.getProperty("voxelbrew.ui.artifacts"),
                                "window-header-" + (palette == 1 ? "light" : "dark") + ".png"));
                return null;
            });
        }
    }

    @Test
    void integratedCloseRespectsModalCancelSketchDraftsAndSave() throws Exception {
        rectangle(0);
        var original = document();
        click("window-close");
        assertThat(fx(() -> node("#unsaved-cancel"))).isNotNull();
        assertThat(fx(() -> node("#window-close").isDisabled())).isFalse();
        assertThat(fx(() -> node("#main-menu").isDisabled())).isTrue();
        click("window-close"); // The topmost dialog handles Close as Cancel.
        assertThat(fx(stage::isShowing)).isTrue();
        assertThat(fx(workbench::isDisabled)).isFalse();
        assertThat(document()).isEqualTo(original);
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertThat(fx(() -> node("#window-minimize").isDisabled())).isFalse();
        click("window-close"); // Dismiss the uncommitted sketch, keeping the document open.
        assertThat(fx(stage::isShowing)).isTrue();
        assertThat(document()).isEqualTo(original);
        click("save-as");
        assertThat(fx(() -> node("#file-path"))).isNotNull();
        click("window-close");
        assertThat(fx(stage::isShowing)).isTrue();
        assertThat(fx(() -> workbench.controller().dirty())).isTrue();
        click("window-close");
        click("unsaved-cancel");
        assertThat(document()).isEqualTo(original);
        click("window-close");
        click("unsaved-save");
        Path file = directory.resolve("Saved by integrated Close.vxb");
        text("file-path", file.toString());
        click("file-confirm");
        awaitWindowClosed();
        assertThat(DocumentReader.load(file)).isEqualTo(original);
    }

    @Test
    void integratedCloseDiscardsOnlyAfterTheExplicitChoice() throws Exception {
        rectangle(0);
        click("window-close");
        assertThat(fx(stage::isShowing)).isTrue();
        click("unsaved-discard");
        awaitWindowClosed();
    }

    private void awaitWindowClosed() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fx(stage::isShowing) && System.nanoTime() < deadline) Thread.sleep(30);
        assertThat(fx(stage::isShowing)).isFalse();
        workbench.whenClosed().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private javafx.geometry.Rectangle2D windowBounds() throws Exception {
        return fx(
                () -> new javafx.geometry.Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()));
    }

    private void assertWindowBounds(javafx.geometry.Rectangle2D expected) throws Exception {
        // Native window managers acknowledge move/resize requests asynchronously.
        // Wait for all four bounds rather than sampling during a partial update.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var actual = windowBounds();
        while ((Math.abs(expected.getMinX() - actual.getMinX()) > 2
                        || Math.abs(expected.getMinY() - actual.getMinY()) > 2
                        || Math.abs(expected.getWidth() - actual.getWidth()) > 2
                        || Math.abs(expected.getHeight() - actual.getHeight()) > 2)
                && System.nanoTime() < deadline) {
            Thread.sleep(30);
            actual = windowBounds();
        }
        assertThat(actual.getMinX()).isCloseTo(expected.getMinX(), org.assertj.core.data.Offset.offset(2.0));
        assertThat(actual.getMinY()).isCloseTo(expected.getMinY(), org.assertj.core.data.Offset.offset(2.0));
        assertThat(actual.getWidth()).isCloseTo(expected.getWidth(), org.assertj.core.data.Offset.offset(2.0));
        assertThat(actual.getHeight()).isCloseTo(expected.getHeight(), org.assertj.core.data.Offset.offset(2.0));
    }

    private Point2D centerOf(String id) throws Exception {
        return fx(() -> {
            var control = node("#" + id);
            var bounds = control.localToScreen(control.getBoundsInLocal());
            return new Point2D(bounds.getCenterX(), bounds.getCenterY());
        });
    }

    private void doubleClickHeader() throws Exception {
        var point = centerOf("document-title");
        fx(() -> {
            robot.mouseMove(point);
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(80);
        fx(() -> {
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(250);
    }

    @Test
    void bodyContextRenameAndInlineEditingPersistWithoutChangingGeometry() throws Exception {
        createRenameBodies();
        var original = document();
        long before = fx(() -> workbench.controller().snapshot().revision());
        var ids = fx(() -> workbench.controller().build().bodies().stream()
                .map(DocumentController.Body::id)
                .toList());
        double volume =
                fx(() -> workbench.controller().build().bodies().getFirst().volume());
        bodyTreeClick("Body 1", MouseButton.SECONDARY, 1);
        assertThat(fx(() -> node("#body-rename").getAccessibleHelp().isBlank())).isFalse();
        click("body-rename");
        assertThat(fx(() -> ((TextField) node("#body-rename-field")).getText())).isEqualTo("Body 1");
        text("body-rename-field", "Mounting plate");
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "body-rename-light.png"));
            return null;
        });
        key(KeyCode.ENTER);
        ready();
        assertThat(document().bodyNames()).isEqualTo(Map.of("named0.body", "Mounting plate"));
        assertThat(fx(() -> workbench.controller().snapshot().revision())).isEqualTo(before + 1);
        assertThat(document().features()).isEqualTo(original.features());
        assertThat(fx(() -> workbench.controller().build().bodies().stream()
                        .map(DocumentController.Body::id)
                        .toList()))
                .isEqualTo(ids);
        assertThat(fx(() -> workbench.controller().build().bodies().getFirst().volume()))
                .isCloseTo(volume, org.assertj.core.data.Offset.offset(0.000001));
        click("undo");
        ready();
        assertThat(document().bodyNames().isEmpty()).isTrue();
        click("redo");
        ready();
        long renamed = fx(() -> workbench.controller().snapshot().revision());
        bodyTreeClick("Mounting plate", MouseButton.PRIMARY, 1);
        assertThat(fx(() -> ((TreeView<?>) node("#model-tree")).getEditingItem()))
                .as("A single click must not resize or scroll the selected row before a double-click")
                .isNull();
        bodyTreeClick("Mounting plate", MouseButton.PRIMARY, 2);
        key(KeyCode.ENTER);
        ready();
        assertThat(fx(() -> workbench.controller().snapshot().revision()))
                .as("Accepting an unchanged name creates no undo entry")
                .isEqualTo(renamed);
        bodyTreeClick("Mounting plate", MouseButton.PRIMARY, 2);
        text("body-rename-field", "");
        key(KeyCode.ENTER);
        assertThat(fx(() -> ((Label) node("#body-rename-error")).getText().isBlank()))
                .isFalse();
        assertThat(fx(() -> workbench.controller().snapshot().revision())).isEqualTo(renamed);
        text("body-rename-field", "Discard this");
        key(KeyCode.ESCAPE);
        assertThat(fx(() -> workbench.controller().snapshot().revision())).isEqualTo(renamed);
        assertThat(document().bodyNames().get("named0.body")).isEqualTo("Mounting plate");
        bodyTreeClick("Mounting plate", MouseButton.PRIMARY, 2);
        text("body-rename-field", "Uncommitted");
        click("body-material-name");
        assertThat(fx(() -> ((TreeView<?>) node("#model-tree")).getEditingItem()))
                .isNull();
        assertThat(fx(() -> workbench.controller().snapshot().revision())).isEqualTo(renamed);
        Path file = directory.resolve("named bodies.vxb");
        click("save-as");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertThat(document().bodyNames().get("named0.body")).isEqualTo("Mounting plate");
        assertThat(fx(() -> workbench.controller().build().bodies().stream()
                        .map(DocumentController.Body::id)
                        .toList()))
                .isEqualTo(ids);
        bodyTreeClick("Mounting plate", MouseButton.PRIMARY, 2);
        text("body-rename-field", "Stale draft");
        fx(() -> {
            workbench.controller().cursor(3);
            return null;
        });
        ready();
        assertThat(fx(() -> ((TreeView<?>) node("#model-tree")).getEditingItem()))
                .isNull();
        bodyTreeClick("Mounting plate", MouseButton.SECONDARY, 1);
        assertThat(fx(() -> Window.getWindows().stream()
                        .filter(ContextMenu.class::isInstance)
                        .map(ContextMenu.class::cast)
                        .filter(Window::isShowing)
                        .flatMap(menu -> menu.getItems().stream())
                        .filter(item -> "body-rename".equals(item.getId()))
                        .findFirst()
                        .orElseThrow()
                        .isDisable()))
                .as("Cannot rename while rolled back")
                .isTrue();
        key(KeyCode.ESCAPE);
        bodyTreeClick("Mounting plate", MouseButton.PRIMARY, 2);
        assertThat(fx(() -> ((TreeView<?>) node("#model-tree")).getEditingItem()))
                .isNull();
        fx(() -> {
            workbench.controller().cursor(4);
            return null;
        });
        ready();
        assertThat(document().bodyNames().get("named0.body")).isEqualTo("Mounting plate");
        String featureLabel = fx(() -> ((TreeView<?>) node("#model-tree"))
                .getRoot()
                .getChildren()
                .getFirst()
                .getChildren()
                .getFirst()
                .getValue()
                .toString());
        bodyTreeClick(featureLabel, MouseButton.PRIMARY, 2);
        assertThat(fx(() -> ((TreeView<?>) node("#model-tree")).getEditingItem()))
                .as("Feature rows support their own name editing")
                .isNotNull();
        text("feature-rename-field", "Base profile");
        key(KeyCode.ENTER);
        ready();
        assertThat(document()
                        .featureNames()
                        .get(document().features().getFirst().id()))
                .isEqualTo("Base profile");
        assertThat(document().bodyNames().get("named0.body")).isEqualTo("Mounting plate");
    }

    @Test
    void polishBodyRenameTargetsTheClickedBodyAndSupportsDarkPalette() throws Exception {
        createRenameBodies();
        choice("appearance", 2);
        var translations = new dev.voxelbrew.util.i18n.Translations(Locale.forLanguageTag("pl"));
        bodyTreeClick(translations.text("feature.body", 2), MouseButton.SECONDARY, 1);
        assertThat(fx(() -> ((Label) node("#body-rename").lookup(".label")).getText()))
                .isEqualTo("Zmień nazwę");
        fx(() -> {
            Screenshot.save(
                    node("#body-rename").getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "body-context-pl.png"));
            return null;
        });
        click("body-rename");
        text("body-rename-field", "Obudowa");
        fx(() -> {
            var editor = node("#body-rename-field");
            var clip = node("#model-tree").lookup(".clipped-container");
            assertThat(clip).isNotNull();
            var fieldBounds = editor.localToScene(editor.getLayoutBounds());
            var visibleBounds = clip.localToScene(clip.getLayoutBounds());
            assertThat(fieldBounds.getMinY() >= visibleBounds.getMinY() - 1).isTrue();
            assertThat(fieldBounds.getMaxY() <= visibleBounds.getMaxY() + 1)
                    .as("The entire inline editor must remain visible on the last row")
                    .isTrue();
            return null;
        });
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "body-rename-dark.png"));
            return null;
        });
        key(KeyCode.ENTER);
        ready();
        assertThat(document().bodyNames()).isEqualTo(Map.of("named1.body", "Obudowa"));
        long revision = fx(() -> workbench.controller().snapshot().revision());
        bodyTreeClick("Obudowa", MouseButton.SECONDARY, 1);
        click("body-fit");
        assertThat(fx(() -> workbench.controller().snapshot().revision())).isEqualTo(revision);
        bodyTreeClick("Obudowa", MouseButton.PRIMARY, 2);
        fx(() -> {
            workbench.controller().newDocument();
            return null;
        });
        ready();
        assertThat(document().bodyNames().isEmpty()).isTrue();
        assertThat(fx(() -> ((TreeView<?>) node("#model-tree")).getEditingItem()))
                .isNull();
    }

    private void createRenameBodies() throws Exception {
        fx(() -> {
            for (int i = 0; i < 2; i++) {
                workbench
                        .controller()
                        .add(new Feature.Sketch(
                                "named-sketch" + i,
                                "profile" + i,
                                new Feature.Plane("origin-xy", 0),
                                new Feature.Rectangle(i * 40, 0, 30, 30)));
                workbench
                        .controller()
                        .add(new Feature.Extrude(
                                "named" + i, new Feature.ProfileRef("named-sketch" + i, "profile" + i), 5));
            }
            return null;
        });
        ready();
    }

    private void bodyTreeClick(String label, MouseButton button, int count) throws Exception {
        focus(stage);
        int targetRow = fx(() -> {
            var view = (TreeView<?>) node("#model-tree");
            for (int row = 0; row < view.getExpandedItemCount(); row++) {
                if (label.equals(view.getTreeItem(row).getValue().toString())) {
                    view.scrollTo(row);
                    return row;
                }
            }
            throw new AssertionError("Missing body item " + label);
        });
        Thread.sleep(150);
        var point = fx(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            var cell = node("#model-tree").lookupAll(".tree-cell").stream()
                    .filter(n ->
                            n instanceof TreeCell<?> row && row.getIndex() == targetRow && label.equals(row.getText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing body label " + label));
            var bounds = cell.localToScreen(cell.getBoundsInLocal());
            return new Point2D(bounds.getCenterX(), bounds.getCenterY());
        });
        for (int i = 0; i < count; i++) {
            fx(() -> {
                robot.mouseMove(point);
                robot.mouseClick(button);
                return null;
            });
            Thread.sleep(80);
        }
        Thread.sleep(250);
        assertThat(fx(() -> ((TreeView<?>) node("#model-tree"))
                        .getSelectionModel()
                        .getSelectedItem()
                        .getValue()
                        .toString()))
                .as("Native click selects the intended row")
                .isEqualTo(label);
    }

    @Test
    void bodyMaterialAndColorPickingUndoSaveAndBothPalettes() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        for (int i = 0; i < 2; i++) {
            session.add(new Feature.Sketch(
                    "sketch" + i,
                    "profile" + i,
                    new Feature.Plane("origin-xy", 0),
                    new Feature.Rectangle(i * 40, 0, 30, 30)));
            session.add(new Feature.Extrude("solid" + i, new Feature.ProfileRef("sketch" + i, "profile" + i), 5));
        }
        Path file = directory.resolve("red PETG and blue PLA.vxb");
        DocumentWriter.save(session.document(), file, false);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        modelClick(15, 15, 5);
        text("body-material-name", "PETG");
        pickBodyColor(javafx.scene.paint.Color.RED);
        assertThat(document().bodyStyles().isEmpty())
                .as("Editing controls is only a draft")
                .isTrue();
        click("body-style-apply");
        ready();
        var red = new BodyStyle("PETG", "#FF0000");
        assertThat(document().bodyStyle("solid0.body")).isEqualTo(red);
        assertThat(fx(() -> workbench.controller().dirty())).isTrue();
        click("undo");
        ready();
        assertThat(document().bodyStyles().isEmpty()).isTrue();
        click("redo");
        ready();
        assertThat(document().bodyStyle("solid0.body")).isEqualTo(red);
        modelClick(55, 15, 5);
        text("body-material-name", "PLA");
        pickBodyColor(javafx.scene.paint.Color.BLUE);
        click("body-style-apply");
        ready();
        assertThat(document().bodyStyle("solid1.body")).isEqualTo(new BodyStyle("PLA", "#0000FF"));
        click("save");
        ready();
        assertThat(DocumentReader.load(file)).isEqualTo(document());
        click("new");
        ready();
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            // Selecting a feature clears body highlighting and exposes the exact chosen colors.
            click("history-0");
            fx(() -> {
                for (int i = 0; i < 2; i++) {
                    var view = findBodyView(workbench.viewport(), "body-solid" + i + ".body");
                    assertThat(view).isNotNull();
                    assertThat(((javafx.scene.paint.PhongMaterial) view.getMaterial()).getDiffuseColor())
                            .isEqualTo(i == 0 ? javafx.scene.paint.Color.RED : javafx.scene.paint.Color.BLUE);
                }
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"), "body-colors-" + palette + ".png"));
                return null;
            });
        }
        modelClick(55, 15, 5);
        assertThat(fx(() -> ((TextField) node("#body-material-name")).getText()))
                .isEqualTo("PLA");
        assertButtonDescriptions();
        click("body-style-reset");
        ready();
        assertThat(document().bodyStyle("solid1.body")).isEqualTo(BodyStyle.DEFAULT);
        assertThat(document().bodyStyle("solid0.body")).isEqualTo(red);
    }

    private static javafx.scene.shape.MeshView findBodyView(Node node, String id) {
        if (node instanceof javafx.scene.shape.MeshView mesh && id.equals(mesh.getId())) return mesh;
        if (node instanceof SubScene scene) return findBodyView(scene.getRoot(), id);
        if (node instanceof Parent parent) {
            for (var child : parent.getChildrenUnmodifiable()) {
                var found = findBodyView(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void pickBodyColor(javafx.scene.paint.Color color) throws Exception {
        click("body-color");
        fx(() -> {
            Node square = Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .filter(w -> w.getScene() != null)
                    .flatMap(w -> w.getScene().getRoot().lookupAll(".color-square").stream())
                    .filter(n -> n instanceof Parent p
                            && p.getChildrenUnmodifiable().stream()
                                    .anyMatch(c ->
                                            c instanceof javafx.scene.shape.Rectangle r && color.equals(r.getFill())))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing palette color " + color));
            var bounds = square.localToScreen(square.getBoundsInLocal());
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(60);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(150);
        assertThat(fx(() -> ((ColorPicker) node("#body-color")).getValue())).isEqualTo(color);
    }

    @Test
    void firstLaunchUsesSystemDisplayLanguageAndShowsSystemPreference() throws Exception {
        ready();
        assertThat(fx(() -> command(workbench, "save").getText())).isEqualTo("Zapisz");
        assertThat(fx(() -> node("#view-cube-top").getAccessibleText())).isEqualTo("Widok: Góra");
        assertThat(configuration.read().containsKey("language")).isFalse();
        click("language");
        assertThat(fx(() -> ((ComboBox<?>) node("#language-choice")).getValue()))
                .isEqualTo("system");
        assertThat(fx(() -> ((Button) node("#language-save")).getText())).isEqualTo("Zapisz");
        click("language-close");
        assertThat(configuration.read().containsKey("language")).isFalse();
        assertThat(configuration.get("external.setting", "")).isEqualTo("preserve me");
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
    }

    @Test
    void polishWorkbenchDialogsSketchPrintingAndLanguagePreferenceUseRealControls() throws Exception {
        ready();
        // One translated dialog wiring check; message/resource matrices live in UiTextTest.
        click("about");
        waitForAbout("Dostępna nowa wersja: 1.1.0");
        assertThat(fx(() -> ((Label) node("#about-version")).getText())).isEqualTo("Wersja " + Version.version());
        assertThat(fx(() -> ((Hyperlink) node("#about-updates")).getText())).isEqualTo("Otwórz aktualizator…");
        assertThat(fx(() -> ((Hyperlink) node("#about-license"))
                        .getTooltip()
                        .getText()
                        .contains("licencję")))
                .isTrue();
        click("about-close");
        ready();
        click("keyboard-shortcuts");
        assertThat(fx(() -> node("#keyboard-shortcuts-list"))).isNotNull();
        assertThat(fx(() -> node("#keyboard-shortcuts-list").lookupAll(".label").stream()
                        .filter(Label.class::isInstance)
                        .map(Label.class::cast)
                        .anyMatch(label -> label.getText().equals("PROJEKTOWANIE 3D"))))
                .isTrue();
        closeShortcutHelp();
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        text("sketch-point-x", "=$missing");
        assertThat(completionNames()).isEqualTo(List.of());
        assertThat(fx(() -> ((Label) ((ListView<?>) node("#formula-suggestions")).getPlaceholder()).getText()))
                .isEqualTo("Brak pasujących parametrów");
        key(KeyCode.ESCAPE);
        assertNoCompletion();
        assertThat(fx(() -> node("#sketch-point-x")))
                .as("Escape closes suggestions before the tool")
                .isNotNull();
        click("constraint-cancel");
        assertThat(fx(() -> command(workbench, "save").getText())).isEqualTo("Zapisz");
        assertButtonDescriptions();
        assertThat(fx(() -> node("#view-cube-top").getAccessibleText())).isEqualTo("Widok: Góra");
        choice("appearance", 1);
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertButtonDescriptions();
        assertThat(fx(() -> ((Button) node("#sketch-tool-rectangle")).getText()))
                .isEqualTo("Prostokąt");
        assertShortcutCaption("sketch-tool-rectangle", "Prostokąt", "r");
        assertShortcutCaption("sketch-tool-circle", "Okrąg (C)", "C");
        assertShortcutCaption("sketch-tool-arc", "Łuk (A)", "A");
        saveShortcutLabels("pl-light");
        assertThat(fx(() -> ((Button) node("#constraint-finish")).getText())).isEqualTo("Zakończ szkic");
        assertThat(fx(() -> ((Button) node("#constraint-horizontal"))
                        .getTooltip()
                        .getText()
                        .contains("Wybierz linię")))
                .isTrue();
        click("sketch-create-type");
        text("sketch-type-search", "Elipsa");
        assertThat(fx(() -> ((ListView<?>) node("#sketch-type-results")).getItems()))
                .isEqualTo(List.of(SketchTools.Tool.ELLIPSE));
        assertThat(fx(() -> node("#sketch-type-results").lookupAll(".list-cell").stream()
                        .filter(ListCell.class::isInstance)
                        .map(ListCell.class::cast)
                        .anyMatch(cell -> "Elipsa".equals(cell.getText()))))
                .isTrue();
        key(KeyCode.ENTER);
        click("constraint-cancel");
        click("parameters");
        assertThat(fx(() -> ((Button) node("#parameters-apply")).getText())).isEqualTo("Zastosuj");
        assertButtonDescriptions();
        click("parameters-cancel");
        rectangle(0);
        click("extrude");
        text("extrude-distance", "=1+2/3");
        assertThat(fx(() ->
                        ((TextField) node("#extrude-distance")).getPromptText().startsWith("Liczba")))
                .isTrue();
        assertThat(fx(() -> ((TextField) node("#extrude-distance"))
                        .getTooltip()
                        .getText()
                        .startsWith("ƒx  Wynik:")))
                .isTrue();
        click("commit-extrusion");
        ready();
        var part = document();
        Path file = directory.resolve("polish-model.vxb");
        save(file);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertThat(document()).isEqualTo(part);
        click("export");
        assertThat(fx(() -> ((Button) node("#export-confirm")).getText())).isEqualTo("Eksportuj");
        chooseExporter("3mf");
        assertPathRowLabels("export-path");
        assertThat(fx(() -> ((ComboBox<?>) node("#export-space")).getValue())).isEqualTo("DESIGN");
        key(KeyCode.ESCAPE);
        click("workspace-print");
        click("print-tab-supports");
        assertThat(fx(() -> ((CheckBox) node("#print-auto-supports")).getText()))
                .isEqualTo("Podpory automatyczne");
        click("workspace-design");
        for (int palette : new int[] {1, 2}) {
            choice("appearance", palette);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(
                                System.getProperty("voxelbrew.ui.artifacts"),
                                "polish-" + (palette == 1 ? "light" : "dark") + ".png"));
                return null;
            });
        }
        click("language");
        choice("language-choice", 1);
        click("language-save");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!fx(() -> ((Label) node("#language-status")).getText().startsWith("Zapisano"))) {
            assertThat(System.nanoTime() < deadline)
                    .as("Language preference saved")
                    .isTrue();
            Thread.sleep(30);
        }
        assertThat(configuration.get("language", "")).isEqualTo("en-EN");
        assertThat(configuration.get("external.setting", "")).isEqualTo("preserve me");
        click("language-close");
        assertThat(document()).isEqualTo(part);
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
        assertThat(fx(() -> command(workbench, "save").getText())).isEqualTo("Zapisz");
        assertButtonDescriptions();
        // A new window reads the saved preference; the existing one retains its own catalog.
        fx(() -> {
            var reopenedStage = new Stage();
            var reopened = Workbench.create(
                    reopenedStage,
                    directory.resolve("plugins with spaces"),
                    new dev.voxelbrew.util.di.ApplicationModule(configuration, updates::service),
                    new dev.voxelbrew.gui.FileIoModule());
            try {
                reopenedStage.setScene(reopened.createScene(964, 648));
                reopenedStage.show();
                assertThat(command(reopened, "save").getText()).isEqualTo("Save");
            } finally {
                reopened.close();
                reopenedStage.hide();
            }
            return null;
        });
    }

    @Test
    void serviceScopesIsolateWindowsPreferencesAndWorkerLifetimes() throws Exception {
        fx(() -> {
            var config = new dev.voxelbrew.util.config.FileUserConfiguration(directory.resolve("isolated.properties"));
            config.set("language", "pl");
            config.set("appearance", "DARK");
            var secondStage = new Stage();
            var graph = GuiComponent.factory()
                    .create(
                            secondStage,
                            directory.resolve("isolated plugins"),
                            new dev.voxelbrew.util.di.ApplicationModule(config, () -> {
                                throw new AssertionError("Startup must not create an updater");
                            }),
                            new dev.voxelbrew.gui.FileIoModule());
            var second = graph.workbench();
            try {
                secondStage.setScene(second.createScene(964, 648));
                secondStage.show();
                assertThat(graph.workbench()).isSameAs(second);
                assertThat(second.controller()).isNotSameAs(workbench.controller());
                assertThat(second.controller().snapshot().document())
                        .isNotSameAs(workbench.controller().snapshot().document());
                assertThat(second.controller().snapshot().document().uuidGenerator())
                        .isNotSameAs(
                                workbench.controller().snapshot().document().uuidGenerator());
                assertThat(command(second, "save").getText())
                        .isEqualTo(new dev.voxelbrew.util.i18n.Translations(Locale.forLanguageTag("pl"))
                                .text("action.save"));
                assertThat(second.getStyleClass().contains("dark")).isTrue();
                second.close();
                second.close();
                var resources = graph.resources();
                assertThat(List.of(
                                        resources.rebuild,
                                        resources.files,
                                        resources.slicing,
                                        resources.validation,
                                        resources.appearance)
                                .stream()
                                .allMatch(ExecutorService::isShutdown))
                        .isTrue();
                workbench.controller().newDocument();
                assertThat(workbench.controller().ioBusy()).isFalse();
            } finally {
                second.close();
                secondStage.hide();
            }
            return null;
        });
        ready();
    }

    private static MenuItem command(Workbench workbench, String id) {
        return ((MenuBar) workbench.getScene().lookup("#main-menu"))
                .getMenus().stream()
                        .flatMap(WorkbenchUiTest::menuItems)
                        .filter(item -> id.equals(item.getId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Missing command: " + id));
    }

    @Test
    void recentMenuReopensSavedDocument() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        Path saved = directory.resolve("recent design.vxb");
        save(saved);
        var savedDocument = document();
        shortcut(KeyCode.N, false);
        ready();
        assertThat(document().features().isEmpty()).isTrue();
        click("menu-file");
        fx(() -> {
            var recent = (Menu) command(workbench, "menu-open-recent");
            recent.show();
            assertThat(recent.getItems().getFirst().getText()).isEqualTo(saved.toString());
            recent.getItems().getFirst().fire();
            recent.hide();
            return null;
        });
        ready();
        assertThat(document()).isEqualTo(savedDocument);
        assertThat(fx(() -> workbench.controller().path())).isEqualTo(saved);
        Path second = directory.resolve("second recent.vxb");
        Path third = directory.resolve("third recent.vxb");
        save(second);
        save(third);
        shortcut(KeyCode.N, false);
        ready();
        click("open");
        assertThat(fx(() -> ((Hyperlink) node("#file-recent-0")).getText())).isEqualTo(third.toString());
        assertThat(fx(() -> ((Hyperlink) node("#file-recent-1")).getText())).isEqualTo(second.toString());
        assertThat(fx(() -> ((Hyperlink) node("#file-recent-2")).getText())).isEqualTo(saved.toString());
        assertThat(fx(() -> ((javafx.scene.layout.VBox) node("#file-recent-projects"))
                        .getChildren()
                        .size()))
                .isEqualTo(4);
        click("file-recent-2");
        ready();
        assertThat(document()).isEqualTo(savedDocument);
        assertThat(fx(() -> workbench.controller().path())).isEqualTo(saved);
    }

    @Test
    void menusDescribeActionsAndProtectModalEditing() throws Exception {
        var artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        ready();
        assertButtonDescriptions();
        assertThat(fx(() -> workbench.lookup("#modeling-toolbar").lookup("#save")))
                .isNull();
        click("menu-file");
        assertThat(fx(() -> command(workbench, "save").getText())).isEqualTo("Save");
        assertThat(fx(() -> command(workbench, "save-as").getAccelerator())).isNotNull();
        assertThat(fx(() -> node("#save").getAccessibleHelp()))
                .as("Menu description is attached to the popup node")
                .isNotNull();
        fx(() -> {
            var bounds = node("#save").localToScreen(node("#save").getBoundsInLocal());
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!fx(() -> Window.getWindows().stream()
                        .anyMatch(window -> window instanceof Tooltip tip
                                && tip.isShowing()
                                && tip.getText().contains("features and parameters")))
                && System.nanoTime() < deadline) Thread.sleep(40);
        assertThat(fx(() -> Window.getWindows().stream()
                        .anyMatch(window -> window instanceof Tooltip tip
                                && tip.isShowing()
                                && tip.getText().contains("features and parameters"))))
                .as("Save description is a real hover popup")
                .isTrue();
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("workbench-menu-light.png"));
            for (var window : Window.getWindows())
                if (window instanceof Tooltip && window.isShowing())
                    Screenshot.save(window.getScene(), artifacts.resolve("save-tooltip.png"));
            return null;
        });
        key(KeyCode.ESCAPE);
        assertThat(fx(() -> ((MenuBar) workbench.getScene().lookup("#main-menu"))
                        .getMenus()
                        .getFirst()
                        .isShowing()))
                .isFalse();
        String documentId = document().id();
        click("save-as");
        assertButtonDescriptions();
        assertThat(fx(() -> command(workbench, "new").isDisable()))
                .as("Document commands are disabled behind a modal dialog")
                .isTrue();
        shortcut(KeyCode.N, false);
        assertThat(document().id()).isEqualTo(documentId);
        assertThat(fx(() -> node("#file-path"))).isNotNull();
        key(KeyCode.ESCAPE);
        hoverHelp("constrained-sketch");
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertThat(fx(() -> node("#sketch-editor")))
                .as("Help must not consume the modeling click")
                .isNotNull();
        click("constraint-cancel");
        hoverHelp("menu-file");
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertThat(fx(() -> node("#sketch-editor")))
                .as("Menu help must not cover a neighboring action")
                .isNotNull();
        click("constraint-cancel");
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertButtonDescriptions();
        click("constraint-cancel");
        openPrepared("plate-5mm.vxb");
        save(directory.resolve("help-workspace.vxb"));
        click("workspace-print");
        for (String tab : List.of("placement", "process", "supports", "job")) {
            click("print-tab-" + tab);
            assertButtonDescriptions();
        }
        click("workspace-design");
        choice("appearance", 2);
        assertButtonDescriptions();
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("workbench-dark.png"));
            return null;
        });
        click("menu-file");
        fx(() -> {
            var popup = node("#save").getScene();
            assertThat(popup.getRoot().getStyleClass().contains("dark")).isTrue();
            Screenshot.save(popup, artifacts.resolve("file-menu-dark.png"));
            return null;
        });
        key(KeyCode.ESCAPE);
    }

    private static java.util.stream.Stream<MenuItem> menuItems(Menu menu) {
        return menu.getItems().stream()
                .flatMap(item -> item instanceof Menu child
                        ? java.util.stream.Stream.concat(java.util.stream.Stream.of(item), menuItems(child))
                        : java.util.stream.Stream.of(item));
    }

    private void assertButtonDescriptions() throws Exception {
        fx(() -> {
            for (var node : stage.getScene().getRoot().lookupAll("*")) {
                if (node instanceof ButtonBase button && button.isVisible()) {
                    assertThat(button.getAccessibleHelp())
                            .as("Missing description: " + button.getId() + " " + button.getText())
                            .isNotNull();
                    assertThat(button.getAccessibleHelp().isBlank()).isFalse();
                    assertThat(button.getTooltip() != null
                                    || button.getProperties().containsKey(UiHelp.class))
                            .as("Missing popup: " + button.getId())
                            .isTrue();
                }
            }
            return null;
        });
    }

    private void awaitPrinterControl(String selector) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (fx(() -> {
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                return stage.getScene().getRoot().lookup(selector) != null;
            })) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Missing printer control " + selector);
    }

    private static <T> String selectedText(ComboBox<T> choice) {
        return choice.getConverter().toString(choice.getValue());
    }

    private Node node(String selector) {
        var windows = new ArrayList<>(Window.getWindows());
        Collections.reverse(windows);
        for (var window : windows)
            if (window.isShowing() && window.getScene() != null) {
                for (var node : window.getScene().getRoot().lookupAll(selector)) if (treeVisible(node)) return node;
            }
        // Save As uses the exporter dialog; Open still uses the editable-design dialog.
        if (selector.equals("#file-path")) return node("#export-path");
        if (selector.equals("#file-confirm")) return node("#export-confirm");
        throw new AssertionError("Missing control " + selector);
    }

    private static boolean treeVisible(Node node) {
        for (Node current = node; current != null; current = current.getParent())
            if (!current.isVisible()) return false;
        return true;
    }

    private void chooseCurrentProjectWindow() throws Exception {
        if (fx(() -> {
            try {
                node("#project-window-current");
                return true;
            } catch (AssertionError missing) {
                return false;
            }
        })) click("project-window-current");
    }

    private void click(String id) throws Exception {
        if (id.equals("undo") || id.equals("redo")) {
            click("menu-edit");
            click("menu-" + id);
            return;
        }
        String menu = switch (id) {
            case "extrude", "cut", "fillet", "chamfer", "shell", "split-body" -> "operations-menu";
            case "new", "open", "import", "save", "save-as", "export" -> "menu-file";
            case "language", "updates", "about", "keyboard-shortcuts" -> "menu-settings";
            case "appearance", "menu-fit" -> "menu-view";
            default -> null;
        };
        if (menu != null) click(menu);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!fx(() -> {
                    try {
                        node("#" + id);
                        return true;
                    } catch (AssertionError missing) {
                        return false;
                    }
                })
                && System.nanoTime() < deadline) Thread.sleep(40);
        var window = fx(() -> node("#" + id).getScene().getWindow());
        if (window instanceof ContextMenu) {
            // Native popup coordinates can belong to a different DPI monitor in Windows Robot.
            // Navigate the visible popup with real keys and observe focus before accepting it.
            var item = fx(() -> node("#" + id));
            assertThat(fx(item::isDisabled)).as(id + " is disabled").isFalse();
            for (int step = 0; step < 100 && !fx(item::isFocused); step++) key(KeyCode.DOWN);
            assertThat(fx(item::isFocused))
                    .as("Native menu selection reached " + id)
                    .isTrue();
            key(KeyCode.ENTER);
            Thread.sleep(180);
            if (id.equals("new")) chooseCurrentProjectWindow();
            return;
        }
        if (!(window instanceof javafx.stage.PopupWindow)) focus(window);
        reveal(id);
        fx(() -> {
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
            var node = node("#" + id);
            assertThat(node.isDisabled()).as(id + " is disabled").isFalse();
            var hitTarget = node instanceof CheckBox ? node.lookup(".box") : node;
            var bounds = hitTarget.localToScreen(hitTarget.getBoundsInLocal());
            assertThat(bounds).isNotNull();
            assertThat(bounds.getCenterY() < window.getY() + window.getHeight())
                    .as("Control outside window: " + id)
                    .isTrue();
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });
        Thread.sleep(120);
        var target = fx(() -> node("#" + id));
        // Native menu rows may act on hover and redirect input into their submenu popup.
        int expected = target instanceof ButtonBase ? 1 : 0;
        var pressed = new CountDownLatch(expected);
        // Native menu popups can receive the release after the button receives the press.
        var released = new CountDownLatch(target instanceof MenuButton ? 0 : expected);
        boolean[] releaseSent = {false};
        EventHandler<MouseEvent> onPress = e -> pressed.countDown();
        EventHandler<MouseEvent> onRelease = e -> released.countDown();
        boolean pressDelivered = false;
        fx(() -> {
            target.addEventFilter(MouseEvent.MOUSE_PRESSED, onPress);
            target.addEventFilter(MouseEvent.MOUSE_RELEASED, onRelease);
            return null;
        });
        try {
            // Robot queues native input. Wait for delivery rather than assuming the OS and
            // renderer will process each event within a fixed delay; never replay a click.
            fx(() -> {
                var hitTarget = target instanceof CheckBox ? target.lookup(".box") : target;
                var bounds = hitTarget.localToScreen(hitTarget.getBoundsInLocal());
                robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
                robot.mousePress(MouseButton.PRIMARY);
                return null;
            });
            pressDelivered = pressed.await(3, TimeUnit.SECONDS);
            if (pressDelivered) {
                Thread.sleep(60);
                fx(() -> {
                    robot.mouseRelease(MouseButton.PRIMARY);
                    releaseSent[0] = true;
                    return null;
                });
                assertThat(released.await(3, TimeUnit.SECONDS))
                        .as("Mouse release reached " + id)
                        .isTrue();
            }
        } finally {
            fx(() -> {
                if (!releaseSent[0]) robot.mouseRelease(MouseButton.PRIMARY);
                target.removeEventFilter(MouseEvent.MOUSE_PRESSED, onPress);
                target.removeEventFilter(MouseEvent.MOUSE_RELEASED, onRelease);
                return null;
            });
        }
        if (!pressDelivered) {
            if (id.startsWith("menu-") && target instanceof MenuButton menuButton) {
                fx(() -> {
                    menuButton.show();
                    return null;
                });
            } else fail("Mouse press reached " + id);
        }
        Thread.sleep(180);
        if (id.equals("file-confirm") || id.equals("new")) chooseCurrentProjectWindow();
    }

    private void hoverHelp(String id) throws Exception {
        var control = fx(() -> (Control) node("#" + id));
        fx(() -> {
            var bounds = control.localToScreen(control.getBoundsInLocal());
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!fx(() -> control.getTooltip().isShowing()) && System.nanoTime() < deadline) Thread.sleep(40);
        assertThat(fx(() -> control.getTooltip().isShowing()))
                .as("Hover help for " + id)
                .isTrue();
    }

    private void reveal(String id) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            int direction = fx(() -> {
                var target = node("#" + id);
                target.getScene().getRoot().applyCss();
                target.getScene().getRoot().layout();
                var bounds = target.localToScreen(target.getBoundsInLocal());
                for (var parent = target.getParent(); parent != null; parent = parent.getParent()) {
                    if (!(parent instanceof ScrollPane scroll)) continue;
                    var viewport = scroll.lookup(".viewport");
                    var visible = viewport.localToScreen(viewport.getBoundsInLocal());
                    if (bounds.getMinY() >= visible.getMinY() && bounds.getMaxY() <= visible.getMaxY()) continue;
                    robot.mouseMove(visible.getCenterX(), visible.getCenterY());
                    return bounds.getCenterY() < visible.getCenterY() ? -3 : 3;
                }
                return 0;
            });
            if (direction == 0) return;
            Thread.sleep(100);
            fx(() -> {
                robot.mouseWheel(direction);
                return null;
            });
            Thread.sleep(150);
        }
        fail("Could not scroll control into view: " + id);
    }

    private void focus(Window window) throws Exception {
        if (!fx(window::isFocused)) {
            fx(() -> {
                if (window instanceof Stage target) target.toFront();
                window.requestFocus();
                return null;
            });
            Thread.sleep(200);
            if (!fx(window::isFocused)) {
                fx(() -> {
                    var point = window.getScene().getRoot().localToScreen(20, 20);
                    robot.mouseMove(point);
                    robot.mouseClick(MouseButton.PRIMARY);
                    return null;
                });
                Thread.sleep(150);
                fx(() -> {
                    window.requestFocus();
                    return null;
                });
                Thread.sleep(200);
            }
        }
    }

    private void key(KeyCode code) throws Exception {
        fx(() -> {
            robot.keyType(code);
            return null;
        });
        Thread.sleep(100);
    }

    private void openShortcutHelp() throws Exception {
        focus(stage);
        if (fx(() -> stage.getScene().lookup("#sketch-editor") == null)) click("keyboard-shortcuts");
        else key(KeyCode.F1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!fx(() -> node("#keyboard-shortcuts-close").isFocused()) && System.nanoTime() < deadline)
            Thread.sleep(20);
        assertThat(fx(() -> node("#keyboard-shortcuts-close").isFocused()))
                .as("The shortcut reference gives its Close button keyboard focus")
                .isTrue();
    }

    private void closeShortcutHelp() throws Exception {
        key(KeyCode.ESCAPE);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (fx(() -> stage.getScene().lookup("#keyboard-shortcuts-list") != null) && System.nanoTime() < deadline)
            Thread.sleep(20);
        assertThat(fx(() -> stage.getScene().lookup("#keyboard-shortcuts-list")))
                .as("One Escape closes the shortcut reference")
                .isNull();
    }

    private void shortcut(KeyCode code, boolean shift) throws Exception {
        KeyCode modifier = System.getProperty("os.name").startsWith("Mac") ? KeyCode.META : KeyCode.CONTROL;
        focus(stage);
        assertThat(fx(stage::isFocused)).as("Window focus before shortcut").isTrue();
        fx(() -> {
            robot.keyPress(modifier);
            return null;
        });
        Thread.sleep(60);
        if (shift) {
            fx(() -> {
                robot.keyPress(KeyCode.SHIFT);
                return null;
            });
            Thread.sleep(60);
        }
        key(code);
        if (shift) {
            fx(() -> {
                robot.keyRelease(KeyCode.SHIFT);
                return null;
            });
            Thread.sleep(60);
        }
        fx(() -> {
            robot.keyRelease(modifier);
            return null;
        });
        Thread.sleep(250);
        if (code == KeyCode.N) chooseCurrentProjectWindow();
    }

    private void text(String id, String value) throws Exception {
        click(id);
        var target = fx(() -> (TextField) node("#" + id));
        for (int attempt = 0; attempt < 2 && !fx(target::isFocused); attempt++) {
            Thread.sleep(250);
            click(id);
        }
        assertThat(fx(target::isFocused)).as("Focused field: " + id).isTrue();
        int length = fx(target::getLength);
        key(KeyCode.HOME);
        assertThat(fx(() -> node("#" + id))).as("Stable inspector field: " + id).isSameAs(target);
        assertThat(fx(target::getCaretPosition)).as("Home key: " + id).isEqualTo(0);
        for (int i = 0; i < length; i++) {
            fx(() -> {
                robot.keyType(KeyCode.DELETE);
                return null;
            });
            Thread.sleep(20);
        }
        Thread.sleep(150);
        assertThat(fx(() -> ((TextField) node("#" + id)).getText()))
                .as("Robot field clearing: " + id)
                .isEqualTo("");
        var entered = new StringBuilder();
        for (char c : value.toCharArray()) {
            fx(() -> {
                boolean shift = Character.isUpperCase(c) || ":_~+$\"()*^".indexOf(c) >= 0;
                KeyCode code;
                if (Character.isLetter(c)) code = KeyCode.valueOf(Character.toString(Character.toUpperCase(c)));
                else if (Character.isDigit(c)) code = KeyCode.valueOf("DIGIT" + c);
                else
                    code = switch (c) {
                        case '.' -> KeyCode.PERIOD;
                        case '-', '_' -> KeyCode.MINUS;
                        case '/' -> KeyCode.SLASH;
                        case '\\' -> KeyCode.BACK_SLASH;
                        case ':' -> KeyCode.SEMICOLON;
                        case '`', '~' -> KeyCode.BACK_QUOTE;
                        case ' ' -> KeyCode.SPACE;
                        case '=', '+' -> KeyCode.EQUALS;
                        case '$' -> KeyCode.DIGIT4;
                        case '"' -> KeyCode.QUOTE;
                        case '(' -> KeyCode.DIGIT9;
                        case ')' -> KeyCode.DIGIT0;
                        case '*' -> KeyCode.DIGIT8;
                        case '^' -> KeyCode.DIGIT6;
                        case ',' -> KeyCode.COMMA;
                        default -> throw new IllegalArgumentException("Unsupported test character " + c);
                    };
                if (shift) robot.keyPress(KeyCode.SHIFT);
                robot.keyType(code);
                if (shift) robot.keyRelease(KeyCode.SHIFT);
                return null;
            });
            entered.append(c);
            // Some layouts compose these keys with the next character. Verify the combined
            // prefix after that keystroke; the final field value is always checked exactly.
            if ("~`^".indexOf(c) >= 0) {
                Thread.sleep(30);
                continue;
            }
            String expected = entered.toString();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!expected.equals(fx(target::getText)) && System.nanoTime() < deadline) Thread.sleep(10);
            String actualText = fx(target::getText);
            assertThat(actualText).as("Delivered keyboard input: " + id).isEqualTo(expected);
        }
        Thread.sleep(120);
        assertThat(fx(() -> ((TextField) node("#" + id)).getText()))
                .as("Robot text entry: " + id)
                .isEqualTo(value);
        if (Set.of("file-path", "import-path", "export-path").contains(id)) assertPathRowLabels(id);
    }

    private void assertPathRowLabels(String fieldId) throws Exception {
        fx(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            var field = (TextField) node("#" + fieldId);
            var browse = (Button) node("#file-browse");
            var row = browse.getParent();
            // Use control layout bounds rather than shadows for the overlap check.
            var fieldBounds = field.localToParent(field.getLayoutBounds());
            var buttonBounds = browse.localToParent(browse.getLayoutBounds());
            assertThat(browse.getWidth() > 0).isTrue();
            assertThat(fieldBounds.getMaxX() <= buttonBounds.getMinX())
                    .as("Path field must not overlap Browse")
                    .isTrue();
            Node panel = row;
            while (!panel.getStyleClass().contains("dialog-overlay")) panel = panel.getParent();
            for (var control : panel.lookupAll(".button")) {
                // Formula skin help may scroll out of view; check translated dialog actions.
                if (control instanceof Button button
                        && !button.getText().isEmpty()
                        && !button.getStyleClass().contains("formula-help")) {
                    assertThat(button.lookup(".text")).isInstanceOf(javafx.scene.text.Text.class);
                    var rendered = (javafx.scene.text.Text) button.lookup(".text");
                    assertThat(rendered.getText())
                            .as("The full translated button label must be displayed: " + button.getId())
                            .isEqualTo(button.getText());
                    var textBounds = rendered.localToScene(rendered.getLayoutBounds());
                    boolean scrollable = false;
                    for (var parent = button.getParent(); parent != panel; parent = parent.getParent()) {
                        if (parent instanceof ScrollPane scroll) {
                            scrollable = true;
                            var viewport = scroll.lookup(".viewport");
                            var viewportBounds = viewport.localToScene(viewport.getLayoutBounds());
                            // Forms may scroll vertically; labels must still fit horizontally.
                            assertThat(textBounds.getMinX() >= viewportBounds.getMinX()
                                            && textBounds.getMaxX() <= viewportBounds.getMaxX())
                                    .as("Button label must not be clipped horizontally: " + button.getId())
                                    .isTrue();
                        }
                    }
                    if (!scrollable)
                        assertThat(panel.localToScene(panel.getLayoutBounds()).contains(textBounds))
                                .as("Button label must stay visible inside the dialog: " + button.getId())
                                .isTrue();
                }
            }
            Screenshot.save(
                    stage.getScene(),
                    Path.of(
                            System.getProperty("voxelbrew.ui.artifacts"),
                            "dialog-"
                                    + fieldId
                                    + "-"
                                    + (browse.getText().startsWith("Browse") ? "en" : "pl")
                                    + ".png"));
            return null;
        });
    }

    private void standardView(int index) throws Exception {
        click("menu-view");
        click("view-"
                + List.of("isometric", "top", "front", "right", "back", "left", "bottom")
                        .get(index));
    }

    private void chooseExporter(String id) throws Exception {
        int index = fx(() -> {
            var choices = ((ComboBox<?>) node("#export-format")).getItems();
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i) instanceof dev.voxelbrew.api.exporter.ExporterDescriptor choice
                        && choice.id().equals(id)) return i;
            }
            throw new AssertionError("Missing exporter " + id);
        });
        choice("export-format", index);
        click("export-confirm");
    }

    @Test
    void exportValidationMarksAndClearsInvalidDestination() throws Exception {
        ready();
        rectangle(0);
        click("export");
        chooseExporter("json");
        text("export-path", "");
        click("export-confirm");

        assertThat(fx(() -> node("#export-path").getStyleClass().contains("export-dialog-invalid")))
                .isTrue();
        assertThat(fx(() -> node(".export-dialog-inline-error").isVisible())).isTrue();
        assertThat(fx(() -> ((Label) node(".export-dialog-inline-error")).getText()))
                .isEqualTo("Choose a destination");

        text("export-path", directory.resolve("valid.json").toString());
        assertThat(fx(() -> node("#export-path").getStyleClass().contains("export-dialog-invalid")))
                .isFalse();
        key(KeyCode.ESCAPE);
    }

    private void assertDesignRejectsMeshExport(String diagnostic) throws Exception {
        var before = document();
        assertThat(fx(() -> workbench.controller().canExport())).isFalse();
        Path target = directory.resolve("invalid-design.stl");
        click("export");
        chooseExporter("stl");
        text("export-path", target.toString());
        click("export-confirm");
        ready();
        assertThat(Files.exists(target))
                .as("Broken geometry must not produce an export")
                .isFalse();
        String error = fx(() -> ((Label) node("#status")).getText());
        assertThat(error.contains(diagnostic)).as(error).isTrue();
        assertThat(document()).isEqualTo(before);
    }

    private void choice(String id, int index) throws Exception {
        if (id.equals("appearance")) {
            click("menu-view");
            // A submenu opens on hover. Clicking it after the hover delay can close it again.
            fx(() -> {
                var item = node("#menu-appearance");
                var bounds = item.localToScreen(item.getBoundsInLocal());
                robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
                return null;
            });
            long submenuDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!fx(() -> ((Menu) command(workbench, "menu-appearance")).isShowing())
                    && System.nanoTime() < submenuDeadline) Thread.sleep(25);
            assertThat(fx(() -> ((Menu) command(workbench, "menu-appearance")).isShowing()))
                    .as("Appearance submenu opens on native hover")
                    .isTrue();
            click("appearance-" + List.of("system", "light", "dark").get(index));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (fx(() -> ((MenuBar) workbench.getScene().lookup("#main-menu"))
                            .getMenus().stream().anyMatch(Menu::isShowing))
                    && System.nanoTime() < deadline) Thread.sleep(25);
            assertThat(fx(() -> ((MenuBar) workbench.getScene().lookup("#main-menu"))
                            .getMenus().stream().anyMatch(Menu::isShowing)))
                    .as("Appearance menus closed after selection")
                    .isFalse();
            assertThat(fx(() -> ((RadioMenuItem) command(
                                    workbench,
                                    "appearance-"
                                            + List.of("system", "light", "dark").get(index)))
                            .isSelected()))
                    .isTrue();
            return;
        }
        click(id);
        // The native popup can appear after a later pulse when switching workspace tabs.
        // Wait for the observed popup; do not replay the click or set its selection in code.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!fx(() -> ((ComboBox<?>) node("#" + id)).isShowing()) && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(fx(() -> ((ComboBox<?>) node("#" + id)).isShowing()))
                .as("Popup opened: " + id)
                .isTrue();
        // Popup windows can select a different DPI monitor than their owner in Windows Robot.
        // Exercise the actual opened popup through native keys, without setting selection in code.
        key(KeyCode.HOME);
        for (int row = 0; row < index; row++) key(KeyCode.DOWN);
        key(KeyCode.ENTER);
        assertThat(fx(() -> ((ComboBox<?>) node("#" + id)).getSelectionModel().getSelectedIndex()))
                .as("Combo selection: " + id)
                .isEqualTo(index);
    }

    private void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (fx(() -> !workbench.controller().rebuilding()
                    && !workbench.controller().ioBusy())) {
                Thread.sleep(150);
                return;
            }
            Thread.sleep(25);
        }
        fail("GUI operation timed out");
    }

    private CadDocument document() throws Exception {
        return fx(() -> workbench.controller().snapshot().document());
    }

    @Test
    void aboutDetailsAndUpdateLinkInBothPalettes() throws Exception {
        assertThat(updates.requests.get())
                .as("Startup must not contact the release service")
                .isEqualTo(0);
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            click("about");
            waitForAbout("New version available: 1.1.0");
            assertThat(fx(() -> ((Label) node("#about-version")).getText()))
                    .isEqualTo("Version " + dev.voxelbrew.util.Version.version());
            fx(() -> {
                for (String id :
                        List.of("about-check", "about-updates", "about-license", "about-notices", "about-close")) {
                    var control = (Control) node("#" + id);
                    assertThat(control.getTooltip()).as(id).isNotNull();
                    assertThat(control.getAccessibleHelp().isBlank()).as(id).isFalse();
                }
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"), "about-" + palette + ".png"));
                return null;
            });
            click("about-license");
            assertThat(fx(() -> ((TextArea) node("#about-legal-text"))
                            .getText()
                            .contains("GNU AFFERO GENERAL PUBLIC LICENSE")))
                    .isTrue();
            assertThat(fx(() -> ((TextArea) node("#about-legal-text")).isEditable()))
                    .isFalse();
            click("about-legal-close");
            click("about-notices");
            assertThat(fx(() -> ((TextArea) node("#about-legal-text")).getText().contains("Open CASCADE")))
                    .isTrue();
            click("about-legal-close");
            click("about-updates");
            assertThat(fx(() -> node("#update-check"))).isNotNull();
            click("update-cancel");
            ready();
        }
    }

    @Test
    void aboutCheckFailureRetryAndCancellation() throws Exception {
        updates.slow = true;
        click("about");
        waitForAbout("Checking for updates");
        // Synchronize with the server: initial HTTP-client startup can exceed five seconds on CI.
        assertThat(updates.requestReceived.await(30, TimeUnit.SECONDS))
                .as("Cancel a lookup already waiting for a response")
                .isTrue();
        assertThat(fx(() -> node("#about-check").isDisabled())).isTrue();
        click("about-close");
        ready();
        updates.failureStatus = 503;
        updates.slow = false;
        click("about");
        waitForAbout("Could not check for updates");
        assertThat(fx(() -> node("#about-check").isDisabled())).isFalse();
        updates.failureStatus = 0;
        updates.current = true;
        click("about-check");
        waitForAbout("You are up to date. Latest published version: 1.0.0");
        updates.current = false;
        updates.development = true;
        click("about-check");
        waitForAbout("Development runs are not replaced");
        assertThat(fx(() -> ((Label) node("#about-status")).getText().startsWith("New version available: 1.1.0")))
                .isTrue();
        click("about-close");
        ready();
    }

    private void waitForAbout(String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!fx(() -> ((Label) node("#about-status")).getText().contains(message)) && System.nanoTime() < deadline)
            Thread.sleep(50);
        assertThat(fx(() -> ((Label) node("#about-status")).getText().contains(message)))
                .as(message)
                .isTrue();
    }

    @Test
    void updatesCheckCancelAndProtectUnsavedDocument() throws Exception {
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            updates.slow = true;
            click("updates");
            click("update-check");
            int request = updates.requests.get();
            click("update-cancel");
            updates.slow = false;
            click("updates");
            text("update-version", "1.1.0");
            click("update-force");
            click("update-check");
            waitForUpdate("Update available");
            assertThat(updates.requests.get() > request).isTrue();
            assertThat(updates.requested).isEqualTo(dev.voxelbrew.util.update.ReleaseVersion.release("1.1.0"));
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"), "updates-" + palette + ".png"));
                return null;
            });
            click("update-cancel");
        }
        fx(() -> {
            workbench
                    .controller()
                    .add(new Feature.Sketch(
                            "update-sketch",
                            "profile",
                            new Feature.Plane("xy", 0),
                            new Feature.Rectangle(0, 0, 20, 10)));
            return null;
        });
        ready();
        var before = document();
        click("updates");
        click("update-check");
        waitForUpdate("Update available");
        click("update-download");
        waitForUpdate("Verified and ready");
        click("update-install");
        click("unsaved-cancel");
        assertThat(document()).isEqualTo(before);
        assertThat(fx(() -> stage.isShowing())).isTrue();
        assertThat(fx(() -> node("#update-install").isDisabled())).isFalse();
        click("update-cancel");
        ready();
        assertThat(document()).isEqualTo(before);
        // Cancel removes staging after background work has stopped.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean exists;
        do {
            try (var files = Files.walk(directory.resolve("update fixture"))) {
                exists = files.anyMatch(path -> path.getFileName().toString().startsWith(".VoxelBrew-update-"));
            }
            if (exists) Thread.sleep(50);
        } while (exists && System.nanoTime() < deadline);
        assertThat(exists).isFalse();
    }

    @Test
    void nativeAndJsonImportsOpenUnsavedEditableDesignsAndCancelWithoutChanges() throws Exception {
        Path original = PreparedDocuments.copy("plate-5mm.vxb", directory);
        var expected = DocumentReader.load(original);
        Path json = directory.resolve("editable plate.json");
        Files.writeString(json, dev.voxelbrew.engine.persistence.DocumentCodec.encodeJson(expected));
        ready();
        for (var source : List.of(original, json)) {
            var before = document();
            click("import");
            chooseImporter(source.equals(json) ? "json" : "vxb");
            click("import-confirm");
            key(KeyCode.ESCAPE);
            ready();
            assertThat(document()).isEqualTo(before);
            assertThat(fx(() -> workbench.controller().dirty())).isFalse();
            click("import");
            chooseImporter(source.equals(json) ? "json" : "vxb");
            click("import-confirm");
            text("import-path", source.toString());
            click("import-confirm");
            ready();
            assertThat(document().features()).isEqualTo(expected.features());
            assertThat(fx(() -> workbench.controller().dirty())).isTrue();
            assertThat(fx(() -> workbench.controller().path())).isNull();
            assertThat(volume()).isCloseTo(12000, org.assertj.core.data.Offset.offset(.01));
            save(directory.resolve(source.equals(json) ? "json-copy.vxb" : "native-copy.vxb"));
        }
        assertThat(DocumentReader.load(original).features()).isEqualTo(expected.features());
        assertThat(Files.readString(json))
                .isEqualTo(dev.voxelbrew.engine.persistence.DocumentCodec.encodeJson(expected));
    }

    @Test
    void externalImportDialogUsesOptionsAndProtectsSourceInBothPalettes() throws Exception {
        Path source = directory.resolve("external plate.vbplate");
        Files.writeString(source, "60,40,4");
        ready();
        choice("appearance", 1);
        shortcut(KeyCode.I, false);
        chooseImporter("plate");
        click("import-confirm");
        text("import-path", source.toString());
        text("import-scale", "invalid");
        click("import-confirm");
        assertThat(fx(() -> node("#import-scale")
                        .getParent()
                        .lookup(".import-dialog-inline-error")
                        .isVisible()))
                .isTrue();
        text("import-scale", "2");
        assertThat(fx(() -> node("#import-scale")
                        .getParent()
                        .lookup(".import-dialog-inline-error")
                        .isVisible()))
                .isFalse();
        assertButtonDescriptions();
        click("import-confirm");
        ready();
        assertThat(document().id()).isEqualTo("imported-plate");
        assertThat(fx(() -> workbench.controller().dirty())).isTrue();
        assertThat(fx(() -> workbench.controller().path())).isNull();
        assertThat(fx(() -> workbench.controller().build().bodies().getFirst().volume()))
                .isCloseTo(76800, org.assertj.core.data.Offset.offset(.01));
        Path saved = directory.resolve("imported design.vxb");
        save(saved);
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
        var before = document();
        choice("appearance", 2);
        click("import");
        click("import-confirm");
        assertButtonDescriptions();
        text("import-path", source.toString());
        click("import-confirm"); // First provider deliberately fails.
        ready();
        assertThat(document()).isEqualTo(before);
        assertThat(fx(() -> workbench.controller().error().contains("Deliberate importer failure")))
                .isTrue();
        assertThat(Files.readString(source)).isEqualTo("60,40,4");
        assertThat(DocumentReader.load(saved)).isEqualTo(before);
    }

    @SuppressWarnings("unchecked")
    private void chooseImporter(String id) throws Exception {
        int index = fx(() -> {
            var choices = (ComboBox<dev.voxelbrew.api.importer.ImporterDescriptor>) node("#import-format");
            return java.util.stream.IntStream.range(0, choices.getItems().size())
                    .filter(i -> choices.getItems().get(i).id().equals(id))
                    .findFirst()
                    .orElseThrow();
        });
        choice("import-format", index);
    }

    private void waitForUpdate(String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (fx(() -> ((Label) node("#update-status")).getText().contains(message))) return;
            Thread.sleep(30);
        }
        fail(fx(() -> ((Label) node("#update-status")).getText()));
    }

    private double volume() throws Exception {
        return fx(() -> workbench.controller().build().bodies().stream()
                .mapToDouble(DocumentController.Body::volume)
                .sum());
    }

    private void modelClick(double x, double y, double z) throws Exception {
        fx(() -> {
            var point = workbench.viewport().modelToScreen(new Point3D(x, y, z));
            assertThat(point).isNotNull();
            robot.mouseMove(point);
            return null;
        });
        Thread.sleep(80);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(60);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(180);
    }

    private void openPrepared(String name) throws Exception {
        Path copy = PreparedDocuments.copy(name, directory);
        open(copy);
        click("history-0");
        standardView(1);
        click("menu-fit");
    }

    private void rectangle(double offset) throws Exception {
        rectangleDraft(60, 40, 0);
        click("constraint-finish");
        ready();
        if (offset != 0) {
            text("dimension-offsetZ", Double.toString(offset));
            key(KeyCode.ENTER);
            ready();
        }
        standardView(1);
        click("menu-fit");
    }

    private void rectangleDraft(double width, double height, double offset) throws Exception {
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertThat(offset).isEqualTo(0);
        assertThat(fx(() -> stage.getScene().lookup("#constraint-plane"))).isNull();
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        sketchNumericPoint(0, 0);
        sketchNumericPoint(width, height);
        assertThat(sketchGeometry().entities().size()).isEqualTo(4);
    }

    private void circle() throws Exception {
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.CIRCLE_CENTER);
        sketchNumericPoint(10, 10);
        sketchNumericPoint(12, 10);
        click("constraint-finish");
        ready();
    }

    private void openLegacyRectangle() throws Exception {
        // Existing primitive sketches remain editable and convertible to constraints.
        // New profiles are always created through the sketch editor in the other workflows.
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch(
                "legacy", "legacy.profile", new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 60, 40)));
        Path file = directory.resolve("existing rectangle.vxb");
        DocumentWriter.save(session.document(), file, false);
        open(file);
        click("history-0");
    }

    private void save(Path path) throws Exception {
        click("save-as");
        text("file-path", path.toString());
        click("file-confirm");
        ready();
        assertThat(Files.exists(path)).isTrue();
    }

    private void sketchReady() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> ((SketchEditor) node("#sketch-editor")).controller().busy()) && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor")).controller().busy()))
                .isFalse();
        Thread.sleep(100);
    }

    private void sketchClick(double x, double y, boolean shift) throws Exception {
        fx(() -> {
            var point = ((SketchEditor) node("#sketch-editor")).modelToScreen(new SketchGeometry.Vec(x, y));
            assertThat(point).isNotNull();
            robot.mouseMove(point);
            if (shift) robot.keyPress(KeyCode.SHIFT);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(60);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            if (shift) robot.keyRelease(KeyCode.SHIFT);
            return null;
        });
        Thread.sleep(150);
    }

    private void sketchDimension(int type, String value) throws Exception {
        choice("sketch-dimension-type", type);
        text("sketch-dimension-value", value);
        click("sketch-apply-dimension");
        sketchReady();
    }

    private void sketchType(SketchTools.Tool tool) throws Exception {
        click("sketch-create-type");
        fx(() -> {
            ((ListView<?>) node("#sketch-type-results")).getSelectionModel().select(tool.ordinal());
            return null;
        });
        click("sketch-type-confirm");
    }

    private void open(Path file) throws Exception {
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
    }

    private void sketchNumericPoint(double x, double y) throws Exception {
        text("sketch-point-x", Double.toString(x));
        text("sketch-point-y", Double.toString(y));
        click("sketch-add-point");
        sketchReady();
    }

    private SketchGeometry sketchGeometry() throws Exception {
        return fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
    }

    private void parameter(String name, String expression) throws Exception {
        click("parameter-new");
        text("parameter-name", name);
        text("parameter-expression", expression);
        click("parameter-save");
        assertThat(fx(() -> ((Label) node("#parameters-error")).getText())).isEqualTo("");
    }

    private void parameterTab(int index) throws Exception {
        var position = fx(() -> {
            var tabs = (TabPane) node("#parameters-tabs");
            var headers = tabs.lookupAll(".tab").stream()
                    .filter(Node::isVisible)
                    .sorted(java.util.Comparator.comparingDouble(
                            n -> n.localToScreen(n.getBoundsInLocal()).getMinX()))
                    .toList();
            return headers.get(index).localToScreen(headers.get(index).getBoundsInLocal());
        });
        fx(() -> {
            robot.mouseMove(position.getCenterX(), position.getCenterY());
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(200);
        assertThat(fx(() ->
                        ((TabPane) node("#parameters-tabs")).getSelectionModel().getSelectedIndex()))
                .isEqualTo(index);
    }

    private void tableRow(String id, int index) throws Exception {
        click(id);
        key(KeyCode.HOME);
        for (int i = 0; i < index; i++) key(KeyCode.DOWN);
        assertThat(fx(() -> ((TableView<?>) node("#" + id)).getSelectionModel().getSelectedIndex()))
                .isEqualTo(index);
    }

    @Test
    void parameterCompletionFiltersAtCaretAndInsertsWithoutCommittingAcrossEditors() throws Exception {
        choice("appearance", 1);
        click("parameters");
        parameter("fooWidth", "60");
        parameter("fooHeight", "40");
        parameter("bar", "10");
        click("parameters-apply");
        ready();
        var original = document();
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        text("sketch-point-x", "=$");
        var allNames = completionNames();
        assertThat(allNames.stream().filter(name -> !name.startsWith("vb.")).toList())
                .isEqualTo(List.of("bar", "fooHeight", "fooWidth"));
        assertThat(allNames.contains("vb.project.version")).isTrue();
        key(KeyCode.F);
        key(KeyCode.O);
        key(KeyCode.O);
        assertThat(fx(() -> ((TextField) node("#sketch-point-x")).getText())).isEqualTo("=$foo");
        assertThat(completionNames()).isEqualTo(List.of("fooHeight", "fooWidth"));
        completionScreenshot("parameter-completion-light.png");
        key(KeyCode.DOWN);
        key(KeyCode.ENTER);
        assertNoCompletion();
        assertThat(fx(() -> ((TextField) node("#sketch-point-x")).getText())).isEqualTo("=$fooWidth");
        assertThat(document())
                .as("Accepting a suggestion creates no feature or history entry")
                .isEqualTo(original);
        shortcut(KeyCode.Z, false);
        assertThat(fx(() -> ((TextField) node("#sketch-point-x")).getText())).isEqualTo("=$foo");
        assertNoCompletion(); // Text undo selects the replaced reference; resume at its end.
        key(KeyCode.RIGHT);
        assertThat(completionNames()).isEqualTo(List.of("fooHeight", "fooWidth"));
        shortcut(KeyCode.Y, false);
        assertThat(fx(() -> ((TextField) node("#sketch-point-x")).getText())).isEqualTo("=$fooWidth");
        completionNames();
        key(KeyCode.ESCAPE);
        assertNoCompletion();
        assertThat(fx(() -> node("#sketch-point-x"))).isNotNull();
        text("sketch-point-x", "=$fooH");
        assertThat(completionNames()).isEqualTo(List.of("fooHeight"));
        key(KeyCode.TAB);
        assertThat(fx(() -> ((TextField) node("#sketch-point-x")).getText())).isEqualTo("=$fooHeight");
        assertThat(fx(() -> node("#sketch-point-x").isFocused())).isTrue();
        assertThat(document()).isEqualTo(original);
        text("sketch-point-x", "=$vb.project.ver");
        assertThat(completionNames()).isEqualTo(List.of("vb.project.version"));
        key(KeyCode.ENTER);
        assertNoCompletion();
        assertThat(fx(() -> ((FormulaField) node("#sketch-point-x")).getText())).isEqualTo("=$vb.project.version");
        assertThat(fx(() -> ((FormulaField) node("#sketch-point-x")).value())).isEqualTo(0);
        sketchNumericPoint(0, 0);
        sketchNumericPoint(60, 40);
        click("constraint-finish");
        ready();
        assertThat(document().features().size()).isEqualTo(1);
        var part = document();

        text("dimension-offsetZ", "=$fooWidth/2+1");
        key(KeyCode.HOME);
        for (int i = 0; i < 5; i++) key(KeyCode.RIGHT);
        assertThat(completionNames()).isEqualTo(List.of("fooHeight", "fooWidth"));
        click("formula-suggestion-fooHeight");
        assertNoCompletion();
        assertThat(fx(() -> ((TextField) node("#dimension-offsetZ")).getText())).isEqualTo("=$fooHeight/2+1");
        assertThat(document())
                .as("Mouse completion also preserves uncommitted editing")
                .isEqualTo(part);
        text("dimension-offsetZ", "=$missing");
        assertThat(completionNames()).isEqualTo(List.of());
        key(KeyCode.ESCAPE);
        assertNoCompletion();
        assertThat(fx(() -> ((TextField) node("#dimension-offsetZ")).getText())).isEqualTo("=$missing");
        click("history-0");

        choice("appearance", 2);
        click("parameters");
        click("parameter-new");
        text("parameter-comment", "=\"Width: \" + $fooW");
        assertThat(completionNames()).isEqualTo(List.of("fooWidth"));
        completionScreenshot("parameter-completion-dark.png");
        key(KeyCode.ENTER);
        assertNoCompletion();
        assertThat(fx(() -> ((TextField) node("#parameter-comment")).getText())).isEqualTo("=\"Width: \" + $fooWidth");
        assertThat(fx(() -> ((FormulaField) node("#parameter-comment")).evaluatedText()))
                .isEqualTo("Width: 60");
        assertThat(fx(() -> node("#parameters-apply")))
                .as("Completing text does not apply or close a dialog")
                .isNotNull();
        text("parameter-comment", "=\"Version: \" + $vb.project.ver");
        assertThat(completionNames()).isEqualTo(List.of("vb.project.version"));
        key(KeyCode.ENTER);
        assertNoCompletion();
        assertThat(fx(() -> ((FormulaField) node("#parameter-comment")).evaluatedText()))
                .isEqualTo("Version: 0");
        text("parameter-comment", "=\"literal $foo\"");
        assertNoCompletion();
        parameter("fooLength", "5");
        text("parameter-comment", "=$foo");
        assertThat(completionNames()).isEqualTo(List.of("fooHeight", "fooLength", "fooWidth"));
        key(KeyCode.ESCAPE);
        assertNoCompletion();
        assertThat(fx(() -> node("#parameters-cancel")))
                .as("Escape does not cancel the parameter draft")
                .isNotNull();
        click("parameters-cancel");
        assertThat(document()).isEqualTo(part);

        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        text("sketch-point-x", "=$foo");
        assertThat(completionNames()).isEqualTo(List.of("fooHeight", "fooWidth"));
        key(KeyCode.ESCAPE);
        assertNoCompletion();
        assertThat(fx(() -> ((TextField) node("#sketch-point-x")).getText())).isEqualTo("=$foo");
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        text("sketch-point-x", "=$fooW");
        completionNames();
        click("constraint-cancel");
        assertNoCompletion();
        assertThat(document())
                .as("Closing a sketch hides completion and discards the draft")
                .isEqualTo(part);
    }

    private List<String> completionNames() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!fx(this::completionShowing) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(fx(this::completionShowing))
                .as("Parameter suggestions are visible")
                .isTrue();
        return fx(() -> ((ListView<?>) node("#formula-suggestions"))
                .getItems().stream().map(Object::toString).toList());
    }

    private boolean completionShowing() {
        return Window.getWindows().stream()
                .anyMatch(window -> window instanceof PopupWindow
                        && window.isShowing()
                        && window.getScene().lookup("#formula-suggestions") != null);
    }

    private void assertNoCompletion() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (fx(this::completionShowing) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(fx(this::completionShowing))
                .as("Suggestions must close without another key or click")
                .isFalse();
    }

    private void completionScreenshot(String name) throws Exception {
        fx(() -> {
            Screenshot.save(
                    node("#formula-suggestions").getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), name));
            return null;
        });
    }

    @Test
    void numericFormulaFieldsSupportMiddleButtonDragging() throws Exception {
        fx(() -> {
            var field = FormulaField.number("1.25", ParameterSet::empty, ParameterUnit.CM);
            java.util.function.BiConsumer<javafx.event.EventType<MouseEvent>, Double> middle =
                    (type, x) -> field.fireEvent(new MouseEvent(
                            type,
                            x,
                            0,
                            x,
                            0,
                            MouseButton.MIDDLE,
                            1,
                            false,
                            false,
                            false,
                            false,
                            false,
                            type != MouseEvent.MOUSE_RELEASED,
                            false,
                            false,
                            false,
                            false,
                            null));
            middle.accept(MouseEvent.MOUSE_PRESSED, 100.0);
            middle.accept(MouseEvent.MOUSE_DRAGGED, 120.0);
            assertThat(field.getText()).isEqualTo("3.25");
            assertThat(field.value()).isCloseTo(32.5, org.assertj.core.data.Offset.offset(1e-9));
            middle.accept(MouseEvent.MOUSE_DRAGGED, 80.0);
            assertThat(field.getText()).isEqualTo("-0.75");
            field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            assertThat(field.getText()).isEqualTo("1.25");
            middle.accept(MouseEvent.MOUSE_DRAGGED, 140.0);
            assertThat(field.getText()).isEqualTo("1.25");
            middle.accept(MouseEvent.MOUSE_PRESSED, 100.0);
            middle.accept(MouseEvent.MOUSE_DRAGGED, 110.0);
            middle.accept(MouseEvent.MOUSE_RELEASED, 110.0);
            middle.accept(MouseEvent.MOUSE_DRAGGED, 150.0);
            assertThat(field.getText()).isEqualTo("2.25");
            for (double invalidStep : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
                assertThatThrownBy(() -> field.dragStep(invalidStep)).isInstanceOf(IllegalArgumentException.class);
            }
            field.dragStep(0.01).bounds(0.05, true, 0.3, true);
            field.setText("0.2");
            middle.accept(MouseEvent.MOUSE_PRESSED, 100.0);
            middle.accept(MouseEvent.MOUSE_DRAGGED, 101.0);
            assertThat(field.getText()).isEqualTo("0.21");
            field.dragStep(1);
            middle.accept(MouseEvent.MOUSE_DRAGGED, 102.0);
            assertThat(field.getText())
                    .as("Step is fixed for the active gesture")
                    .isEqualTo("0.22");
            middle.accept(MouseEvent.MOUSE_RELEASED, 102.0);
            field.bounds(1, true, 20, true);
            field.setText("2");
            middle.accept(MouseEvent.MOUSE_PRESSED, 100.0);
            middle.accept(MouseEvent.MOUSE_DRAGGED, 101.0);
            assertThat(field.getText()).isEqualTo("3");
            middle.accept(MouseEvent.MOUSE_DRAGGED, 99.0);
            assertThat(field.getText()).isEqualTo("1");
            middle.accept(MouseEvent.MOUSE_RELEASED, 99.0);
            field.bounds(0, false, 5, false);
            field.setText("2");
            middle.accept(MouseEvent.MOUSE_PRESSED, 100.0);
            middle.accept(MouseEvent.MOUSE_DRAGGED, 1000.0);
            assertThat(field.getText()).isEqualTo("4");
            middle.accept(MouseEvent.MOUSE_DRAGGED, -1000.0);
            assertThat(field.getText()).isEqualTo("1");
            middle.accept(MouseEvent.MOUSE_RELEASED, -1000.0);
            field.dragStep(0.1);
            for (boolean inclusive : new boolean[] {true, false}) {
                field.bounds(1, inclusive, 2, inclusive);
                field.setText("1.25");
                middle.accept(MouseEvent.MOUSE_PRESSED, 100.0);
                middle.accept(MouseEvent.MOUSE_DRAGGED, 1000.0);
                assertThat(field.getText()).isEqualTo(inclusive ? "2" : "1.95");
                assertThat(field.value()).isCloseTo(inclusive ? 20 : 19.5, org.assertj.core.data.Offset.offset(1e-9));
                middle.accept(MouseEvent.MOUSE_DRAGGED, -1000.0);
                assertThat(field.getText()).isEqualTo(inclusive ? "1" : "1.05");
                assertThat(field.value()).isCloseTo(inclusive ? 10 : 10.5, org.assertj.core.data.Offset.offset(1e-9));
                middle.accept(MouseEvent.MOUSE_DRAGGED, 101.0);
                assertThat(field.getText()).isEqualTo("1.35");
                field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                assertThat(field.getText()).isEqualTo("1.25");
            }
            field.bounds(0.05, true, 0.3, true);
            field.setText("0.2");
            middle.accept(MouseEvent.MOUSE_PRESSED, 100.0);
            middle.accept(MouseEvent.MOUSE_DRAGGED, 200.0);
            assertThat(field.getText()).isEqualTo("0.3");
            middle.accept(MouseEvent.MOUSE_DRAGGED, 0.0);
            assertThat(field.getText()).isEqualTo("0.05");
            middle.accept(MouseEvent.MOUSE_RELEASED, 0.0);
            field.bounds(0, false, Double.POSITIVE_INFINITY, false);
            field.setText("0.5");
            middle.accept(MouseEvent.MOUSE_PRESSED, 100.0);
            middle.accept(MouseEvent.MOUSE_DRAGGED, -1000.0);
            assertThat(field.getText()).isEqualTo("0.1");
            middle.accept(MouseEvent.MOUSE_RELEASED, -1000.0);
            field.bounds(Double.NEGATIVE_INFINITY, false, 1, false);
            field.setText("0.5");
            middle.accept(MouseEvent.MOUSE_PRESSED, 100.0);
            middle.accept(MouseEvent.MOUSE_DRAGGED, 1000.0);
            assertThat(field.getText()).isEqualTo("0.9");
            middle.accept(MouseEvent.MOUSE_RELEASED, 1000.0);
            field.bounds(Double.NEGATIVE_INFINITY, false, Double.POSITIVE_INFINITY, false);
            for (String unchanged : List.of("=5", "  =5", "$width", "", "NaN", "1e999", "2 mm")) {
                field.setText(unchanged);
                middle.accept(MouseEvent.MOUSE_PRESSED, 100.0);
                middle.accept(MouseEvent.MOUSE_DRAGGED, 120.0);
                middle.accept(MouseEvent.MOUSE_RELEASED, 120.0);
                assertThat(field.getText()).isEqualTo(unchanged);
            }
            field.setText("5");
            field.setEditable(false);
            middle.accept(MouseEvent.MOUSE_PRESSED, 100.0);
            middle.accept(MouseEvent.MOUSE_DRAGGED, 120.0);
            assertThat(field.getText()).isEqualTo("5");
            return null;
        });
    }

    @Test
    void formulaFieldsPreserveNumbersTextParametersHistoryAndFiles() throws Exception {
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        choice("appearance", 1);
        click("parameters");
        parameter("width", "=60");
        click("parameters-apply");
        ready();
        var before = document();
        rectangleDraft(30, 40, 0);
        sketchReference(sketchGeometry().entities().getFirst().id());
        text("sketch-dimension-value", "=$width/2");
        fx(() -> {
            var field = (FormulaField) node("#sketch-dimension-value");
            assertThat(field.value()).isEqualTo(30);
            assertThat(field.getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("formula")))
                    .isTrue();
            assertThat(field.expressionType()).isEqualTo(FormulaField.ExpressionType.NUMBER);
            assertThat(((Label) field.lookup(".formula-mark")).getText()).isEqualTo("ƒx");
            assertThat(field.getTooltip().getText().contains("30")).isTrue();
            var centimeters = FormulaField.number(
                    "=$width/2",
                    () -> workbench.controller().snapshot().document().parameters(),
                    ParameterUnit.CM);
            assertThat(centimeters.value()).isEqualTo(30);
            assertThat(centimeters.declaredValue()).isEqualTo(3);
            Screenshot.save(stage.getScene(), artifacts.resolve("formulas-light.png"));
            return null;
        });
        sketchDimension(0, "=$width/2");
        click("sketch-clear-selection");
        sketchReference(sketchGeometry().entities().get(1).id());
        text("sketch-dimension-value", "=1/0");
        click("sketch-apply-dimension");
        assertThat(document()).isEqualTo(before);
        assertThat(fx(() -> node("#sketch-dimension-value")
                        .getPseudoClassStates()
                        .contains(javafx.css.PseudoClass.getPseudoClass("formula-invalid"))))
                .isTrue();
        sketchDimension(0, "=20+20");
        click("constraint-finish");
        ready();
        assertThat(document().parameters().bindings().size()).isEqualTo(2);
        click("extrude");
        text("extrude-distance", "=1+2/3");
        click("commit-extrusion");
        ready();
        assertThat(volume()).isCloseTo(2000, org.assertj.core.data.Offset.offset(.01));
        click("undo");
        ready();
        assertThat(document().features().size()).isEqualTo(1);
        click("redo");
        ready();
        choice("appearance", 2);
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.TEXT);
        text("sketch-text-content", "=\"Width: \" + 5");
        text("sketch-text-height", "=$width/12");
        fx(() -> {
            assertThat(((FormulaField) node("#sketch-text-content")).evaluatedText())
                    .isEqualTo("Width: 5");
            var stringField = (FormulaField) node("#sketch-text-content");
            assertThat(stringField.expressionType()).isEqualTo(FormulaField.ExpressionType.STRING);
            assertThat(stringField.getStyleClass().contains("string-expression"))
                    .isTrue();
            assertThat(((Label) stringField.lookup(".formula-mark")).getText()).isEqualTo("ƒs");
            Screenshot.save(stage.getScene(), artifacts.resolve("formulas-dark.png"));
            return null;
        });
        sketchNumericPoint(0, 50);
        var textEntity = sketchGeometry().entities().stream()
                .filter(e -> e.kind() == SketchGeometry.Kind.TEXT)
                .findFirst()
                .orElseThrow();
        assertThat(textEntity.text().content()).isEqualTo("Width: 5");
        assertThat(textEntity.value(2)).isCloseTo(5, org.assertj.core.data.Offset.offset(.00001));
        sketchReference(textEntity.id());
        click("sketch-edit-text");
        assertThat(fx(() -> ((TextField) node("#sketch-edit-text-content")).getText()))
                .isEqualTo("=\"Width: \" + 5");
        text("sketch-edit-text-content", "=\"Width: \" + $missing");
        assertThat(fx(() -> ((TextField) node("#sketch-edit-text-content"))
                        .getBorder()
                        .getStrokes()
                        .getFirst()
                        .getTopStroke()))
                .isEqualTo(javafx.scene.paint.Color.web("#d65c46"));
        key(KeyCode.ENTER);
        assertThat(sketchGeometry().entity(textEntity.id()).text().content()).isEqualTo("Width: 5");
        text("sketch-edit-text-content", "=\"Width: \" + $width");
        key(KeyCode.ENTER); // Accept the parameter suggestion first; the next Enter applies.
        key(KeyCode.ENTER);
        sketchReady();
        assertThat(sketchGeometry().entity(textEntity.id()).text().content()).isEqualTo("Width: 60");
        click("sketch-undo");
        sketchReady();
        assertThat(sketchGeometry().entity(textEntity.id()).text().content()).isEqualTo("Width: 5");
        click("sketch-redo");
        sketchReady();
        click("constraint-finish");
        ready();
        click("parameters");
        parameter("width", "=80");
        click("parameters-apply");
        ready();
        assertThat(volume()).isCloseTo(8000.0 / 3, org.assertj.core.data.Offset.offset(.01));
        assertThat( // Four rectangle edges and one text entity are rendered as sketch curves.
                        fx(() -> ((SubScene) workbench.viewport().getChildren().getFirst())
                                .getRoot()
                                .lookupAll(".sketch-curves")
                                .size()))
                .isEqualTo(5);
        var file = directory.resolve("formula fields.vxb");
        save(file);
        var saved = document();
        open(file);
        assertThat(document()).isEqualTo(saved);
        assertThat(DocumentReader.load(file).parameters().bindings().stream()
                        .anyMatch(b -> b.expression().equals("=\"Width: \" + $width")))
                .isTrue();
        click("history-2");
        click("edit-sketch-constraints");
        sketchReady();
        assertThat(sketchGeometry().entity(textEntity.id()).text().content()).isEqualTo("Width: 80");
        assertThat(sketchGeometry().entity(textEntity.id()).value(2))
                .isCloseTo(80.0 / 12, org.assertj.core.data.Offset.offset(.00001));
        sketchReference(textEntity.id());
        click("sketch-edit-text");
        text("sketch-edit-text-content", "=\"Cancelled\"");
        key(KeyCode.ESCAPE);
        click("constraint-cancel");
        ready();
        assertThat(document()).isEqualTo(saved);
        Files.copy(file, artifacts.resolve("formula-fields.vxb"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(artifacts.resolve("formula-cli-inspection.txt"), cli("designer", "inspect", file.toString()));
    }

    @Test
    void namedSketchParametersAnd3dConfigurationsDriveOneDesign() throws Exception {
        choice("appearance", 2);
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        click("sketch-parameters");
        choice("parameter-unit", 1); // centimeters, converted to model millimeters by JSR 385
        parameter("width", "6");
        choice("parameter-unit", 0);
        parameter("thickness", "5");
        click("parameters-apply");
        sketchReady();
        click("sketch-tool-rectangle");
        sketchClick(0, 0, false);
        sketchClick(60, 40, false);
        sketchReady();
        String bottom = sketchGeometry().entities().getFirst().id();
        sketchReference(bottom);
        choice("sketch-dimension-type", 0);
        text("sketch-dimension-value", "width");
        click("sketch-apply-dimension");
        sketchReady();
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor"))
                        .controller()
                        .parameters()
                        .bindings()
                        .getFirst()
                        .expression()))
                .isEqualTo("width");
        click("sketch-clear-selection");
        sketchReference(sketchGeometry().entities().get(1).id());
        text("sketch-dimension-value", "40");
        click("sketch-apply-dimension");
        sketchReady();
        click("constraint-finish");
        ready();
        click("extrude");
        text("extrude-distance", "thickness");
        click("commit-extrusion");
        ready();
        assertThat(volume()).isCloseTo(12000, org.assertj.core.data.Offset.offset(.01));
        String body =
                fx(() -> workbench.controller().build().bodies().getFirst().id());
        var featureIds = fx(() -> workbench.controller().snapshot().document().features().stream()
                .map(Feature::id)
                .toList());
        click("parameters");
        parameterTab(1);
        text("configuration-name", "Large");
        click("configuration-add");
        tableRow("configuration-table", 1);
        choice("configuration-parameter", 0);
        text("configuration-expression", "8");
        click("configuration-set");
        choice("configuration-parameter", 1);
        text("configuration-expression", "8");
        click("configuration-set");
        click("configuration-activate");
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("parameter-configurations-dark.png"));
            return null;
        });
        click("parameters-apply");
        ready();
        assertThat(volume()).isCloseTo(25600, org.assertj.core.data.Offset.offset(.02));
        assertThat(fx(() -> workbench.controller().build().bodies().getFirst().id()))
                .isEqualTo(body);
        click("undo");
        ready();
        assertThat(volume()).isCloseTo(12000, org.assertj.core.data.Offset.offset(.02));
        click("redo");
        ready();
        assertThat(volume()).isCloseTo(25600, org.assertj.core.data.Offset.offset(.02));
        assertThat(fx(() -> workbench.controller().snapshot().document().features().stream()
                        .map(Feature::id)
                        .toList()))
                .isEqualTo(featureIds);
        Path file = directory.resolve("parameter versions.vxb");
        save(file);
        open(file);
        ready();
        assertThat(fx(() -> workbench
                        .controller()
                        .snapshot()
                        .document()
                        .parameters()
                        .activeName()))
                .isEqualTo("Large");
        choice("active-configuration", 0);
        ready();
        assertThat(volume()).isCloseTo(12000, org.assertj.core.data.Offset.offset(.02));
        choice("active-configuration", 1);
        ready();
        assertThat(volume()).isCloseTo(25600, org.assertj.core.data.Offset.offset(.02));
        click("save");
        ready();
        String inspection = cli("designer", "inspect", file.toString());
        var measured = java.util.regex.Pattern.compile("volume=([0-9.eE+\\-]+)").matcher(inspection);
        assertThat(measured.find()).as(inspection).isTrue();
        assertThat(Double.parseDouble(measured.group(1))).isCloseTo(25600, org.assertj.core.data.Offset.offset(.02));
        Files.copy(file, artifacts.resolve("gui-parameter-variants.vxb"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(artifacts.resolve("parameter-cli-inspection.txt"), inspection);
    }

    @Test
    void parameterRepairRenameAndSketchCancellationUseRealControls() throws Exception {
        choice("appearance", 1);
        openLegacyRectangle();
        click("parameters");
        parameter("width", "60");
        click("parameters-apply");
        ready();
        text("dimension-width", "width");
        click("apply-width");
        ready();
        var before = fx(() -> workbench.controller().snapshot());
        click("parameters");
        tableRow("parameter-table", 0);
        text("parameter-expression", "missing");
        click("parameter-save");
        assertThat(fx(() -> ((Label) node("#parameters-error")).getText().contains("Unknown parameter")))
                .isTrue();
        text("parameter-expression", "60");
        choice("parameter-unit", 5); // reject an angle parameter bound to a length
        click("parameter-save");
        assertThat(fx(() -> ((Label) node("#parameters-error"))
                        .getText()
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("incompatible")))
                .isTrue();
        click("parameters-cancel");
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(before);
        click("parameters");
        tableRow("parameter-table", 0);
        text("parameter-name", "plateWidth");
        click("parameter-save");
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "parameters-light.png"));
            return null;
        });
        click("parameters-apply");
        ready();
        assertThat(fx(() -> ((TextField) node("#dimension-width")).getText())).isEqualTo("plateWidth");
        before = fx(() -> workbench.controller().snapshot());
        click("edit-sketch-constraints");
        sketchReady();
        click("sketch-parameters");
        parameter("temporary", "12");
        click("parameters-apply");
        sketchReady();
        click("constraint-cancel");
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(before);
        click("edit-sketch-constraints");
        sketchReady();
        click("constraint-finish");
        ready();
        assertThat(fx(() -> workbench
                        .controller()
                        .snapshot()
                        .document()
                        .parameters()
                        .bindings()
                        .getFirst()
                        .target()
                        .field()))
                .isEqualTo("constraint:width");
        click("extrude");
        click("commit-extrusion");
        ready();
        assertThat(volume()).isCloseTo(12000, org.assertj.core.data.Offset.offset(.01));
        click("parameters");
        parameterTab(1);
        text("configuration-name", "Invalid");
        click("configuration-add");
        tableRow("configuration-table", 1);
        text("configuration-expression", "0");
        click("configuration-set");
        click("configuration-activate");
        click("parameters-apply");
        ready();
        assertThat(fx(() -> workbench.controller().build().valid())).isFalse();
        assertThat(fx(() -> workbench.controller().build().bodies().isEmpty())).isTrue();
        assertDesignRejectsMeshExport("Rebuild failed");
        choice("active-configuration", 0);
        ready();
        assertThat(volume()).isCloseTo(12000, org.assertj.core.data.Offset.offset(.01));
        assertThat(fx(() -> workbench.controller().canExport())).isTrue();
    }

    @Test
    void ellipseSlotsAndConicsUseRealSketchTools() throws Exception {
        choice("appearance", 1);
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.ELLIPSE);
        sketchClick(15, 10, false);
        sketchClick(25, 10, false);
        sketchClick(15, 15, false);
        sketchReady();
        String ellipse = sketchGeometry().entities().getFirst().id();
        sketchReference(ellipse);
        sketchDimension(7, "10");
        sketchDimension(8, "5");
        sketchType(SketchTools.Tool.SLOT_CENTER_TO_CENTER);
        sketchNumericPoint(5, 30);
        sketchNumericPoint(25, 30);
        sketchNumericPoint(15, 32);
        sketchType(SketchTools.Tool.CONIC);
        text("sketch-tool-rho", Double.toString(Math.sqrt(.5) / (1 + Math.sqrt(.5))));
        sketchNumericPoint(45, 10);
        sketchNumericPoint(35, 20);
        sketchNumericPoint(45, 20);
        click("sketch-tool-line");
        sketchClick(35, 20, false);
        sketchClick(45, 10, false);
        sketchReady();
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor")).controller().canFinish()))
                .as(fx(() -> ((Label) node("#sketch-error")).getText()))
                .isTrue();
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-types-ellipse-slot-conic.png"));
            return null;
        });
        click("constraint-finish");
        ready();
        click("extrude");
        ready();
        key(KeyCode.ENTER);
        ready();
        assertThat(fx(() -> workbench.controller().build().bodies().size())).isEqualTo(3);
        assertThat(volume())
                .isCloseTo(
                        (50 * Math.PI + 80 + 4 * Math.PI + 25 * Math.PI - 50) * 5,
                        org.assertj.core.data.Offset.offset(.02));
        var file = directory.resolve("ellipse slot conic.vxb");
        save(file);
        open(file);
        ready();
        assertThat(fx(() -> workbench.controller().build().bodies().size())).isEqualTo(3);
    }

    @Test
    void fitControlSplinesAndTextUseRealSketchTools() throws Exception {
        choice("appearance", 2);
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.FIT_SPLINE);
        click("sketch-closed-curve");
        sketchNumericPoint(0, 0);
        sketchNumericPoint(15, 0);
        sketchNumericPoint(15, 15);
        sketchNumericPoint(0, 15);
        click("sketch-complete-curve");
        sketchReady();
        sketchType(SketchTools.Tool.CONTROL_SPLINE);
        choice("sketch-spline-degree", 1);
        sketchNumericPoint(0, 35);
        sketchNumericPoint(0, 45);
        sketchNumericPoint(10, 55);
        sketchNumericPoint(20, 55);
        sketchNumericPoint(30, 45);
        sketchNumericPoint(30, 35);
        click("sketch-complete-curve");
        sketchReady();
        var before = sketchGeometry();
        sketchDrag(0, 35, 2, 37, false);
        var control = sketchGeometry().entities().stream()
                .filter(e -> e.kind() == SketchGeometry.Kind.CONTROL_SPLINE)
                .findFirst()
                .orElseThrow();
        assertThat(control.value(0)).isCloseTo(2, org.assertj.core.data.Offset.offset(.3));
        assertThat(control.value(1)).isCloseTo(37, org.assertj.core.data.Offset.offset(.3));
        click("sketch-undo");
        sketchReady();
        assertThat(sketchGeometry()).isEqualTo(before);
        sketchType(SketchTools.Tool.TEXT);
        text("sketch-text-content", "OB");
        text("sketch-text-height", "8");
        sketchNumericPoint(40, 10);
        String textId = sketchGeometry().entities().stream()
                .filter(e -> e.kind() == SketchGeometry.Kind.TEXT)
                .findFirst()
                .orElseThrow()
                .id();
        sketchReference(textId);
        click("sketch-edit-text");
        text("sketch-edit-text-content", "OC");
        key(KeyCode.ENTER);
        sketchReady();
        assertThat(sketchGeometry().entity(textId).text().content()).isEqualTo("OC");
        click("sketch-fit");
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-types-splines-text-dark.png"));
            return null;
        });
        click("constraint-finish");
        ready();
        click("extrude");
        ready();
        key(KeyCode.ENTER);
        ready();
        assertThat(fx(() -> workbench.controller().build().bodies().size())).isEqualTo(4);
        var file = directory.resolve("splines and editable text.vxb");
        save(file);
        var saved = document();
        click("undo");
        ready();
        assertThat(fx(() -> workbench.controller().build().bodies().isEmpty())).isTrue();
        click("redo");
        ready();
        assertThat(document()).isEqualTo(saved);
        open(file);
        ready();
        assertThat(document()).isEqualTo(saved);
        Files.writeString(
                artifacts.resolve("sketch-types-cli-inspection.txt"),
                cli("designer", "inspect", file.toString(), "--point", "5,5,2"));
    }

    @Test
    void tangentCirclesAndAssociativeTextPathsUseRealControls() throws Exception {
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        click("sketch-construction");
        click("sketch-tool-line");
        sketchClick(0, 0, false);
        sketchClick(50, 0, false);
        sketchReady();
        click("sketch-tool-line");
        sketchClick(0, 0, false);
        sketchClick(0, 50, false);
        sketchReady();
        var lines = sketchGeometry().entities();
        click("sketch-construction");
        sketchReference(lines.get(0).id());
        sketchReference(lines.get(1).id());
        sketchType(SketchTools.Tool.CIRCLE_TWO_TANGENT);
        sketchClick(8, 8, false);
        sketchReady();
        var circle = sketchGeometry().entities().stream()
                .filter(e -> e.kind() == SketchGeometry.Kind.CIRCLE)
                .findFirst()
                .orElseThrow();
        assertThat(circle.point(SketchGeometry.Part.CENTER).distance(new SketchGeometry.Vec(5, 5)))
                .isCloseTo(0, org.assertj.core.data.Offset.offset(.05));
        click("sketch-construction");
        click("sketch-tool-arc");
        sketchClick(30, 20, false);
        sketchClick(50, 20, false);
        sketchClick(30, 40, false);
        sketchReady();
        click("sketch-construction");
        String arc = sketchGeometry().entities().stream()
                .filter(e -> e.kind() == SketchGeometry.Kind.ARC)
                .findFirst()
                .orElseThrow()
                .id();
        sketchReference(arc);
        sketchType(SketchTools.Tool.TEXT_ON_PATH);
        text("sketch-text-content", "CAD");
        text("sketch-text-height", "4");
        sketchNumericPoint(50, 20);
        var text = sketchGeometry().entities().stream()
                .filter(e -> e.kind() == SketchGeometry.Kind.TEXT)
                .findFirst()
                .orElseThrow();
        assertThat(text.text().pathId()).isEqualTo(arc);
        var before = sketchGeometry().curves(text).getFirst().at(0);
        sketchDrag(30, 20, 35, 20, false);
        assertThat(before.distance(sketchGeometry().curves(text).getFirst().at(0)) > 4)
                .isTrue();
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-types-tangent-and-path-text.png"));
            return null;
        });
        sketchReference(text.id());
        click("sketch-edit-text");
        choice("sketch-edit-text-path", 0);
        key(KeyCode.ENTER);
        sketchReady();
        assertThat(sketchGeometry().entity(text.id()).text().pathId()).isEqualTo("");
        click("sketch-undo");
        sketchReady();
        assertThat(sketchGeometry().entity(text.id()).text().pathId()).isEqualTo(arc);
        click("constraint-finish");
        ready();
        click("extrude");
        ready();
        key(KeyCode.ENTER);
        ready();
        assertThat(fx(() -> workbench.controller().build().bodies().size())).isEqualTo(4);
        var saved = document();
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.CONIC);
        sketchClick(0, 0, false);
        key(KeyCode.ESCAPE);
        click("constraint-cancel");
        assertThat(document()).isEqualTo(saved);
    }

    @Test
    void constrainedSketchDimensionsConflictsHistoryAndFilesUseRealControls() throws Exception {
        choice("appearance", 1);
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        click("sketch-tool-rectangle");
        sketchClick(0, 0, false);
        sketchClick(60, 40, false);
        sketchReady();
        sketchClick(0, 0, false);
        click("constraint-fix");
        sketchReady();
        sketchClick(30, 0, false);
        sketchDimension(0, "60");
        sketchClick(0, 20, false);
        sketchDimension(0, "40");
        assertThat(fx(() -> ((Label) node("#sketch-solve-status")).getText())).isEqualTo("Fully constrained");
        sketchClick(30, 0, false);
        sketchDimension(0, "80");
        assertThat(fx(() -> node("#constraint-finish").isDisabled())).isTrue();
        assertThat(fx(() -> ((Label) node("#sketch-error")).getText().contains("cannot satisfy")))
                .isTrue();
        click("sketch-undo");
        sketchReady();
        assertThat(fx(() -> node("#constraint-finish").isDisabled())).isFalse();
        click("sketch-redo");
        sketchReady();
        assertThat(fx(() -> node("#constraint-finish").isDisabled())).isTrue();
        click("sketch-undo");
        sketchReady();
        click("sketch-tool-circle");
        sketchClick(10, 10, false);
        sketchClick(12, 10, false);
        sketchReady();
        sketchClick(12, 10, false);
        sketchDimension(5, "4");
        // Dimension annotations can overlap the origin; choose the point references explicitly.
        click("sketch-clear-selection");
        var circleId = sketchGeometry().entities().stream()
                .filter(entity -> entity.kind() == SketchGeometry.Kind.CIRCLE)
                .findFirst()
                .orElseThrow()
                .id();
        sketchReference(circleId + ".center");
        sketchReference("origin.point");
        sketchDimension(2, "-10");
        sketchDimension(3, "-10");
        assertThat(fx(() -> ((Label) node("#sketch-solve-status")).getText())).isEqualTo("Fully constrained");
        assertThat(document().features().size())
                .as("Sketch editing stays transient until Finish")
                .isEqualTo(0);
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-constraints-light.png"));
            return null;
        });
        click("constraint-finish");
        ready();
        assertThat(document().features().size()).isEqualTo(1);
        assertThat(((Feature.Sketch) document().features().getFirst()).profile())
                .isInstanceOf(Feature.Constrained.class);
        click("extrude");
        ready();
        text("extrude-distance", "8");
        key(KeyCode.ENTER);
        ready();
        assertThat(volume()).isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(0.01));
        Path file = directory.resolve("GUI constrained plate.vxb");
        save(file);
        var saved = DocumentReader.load(file);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertThat(document()).isEqualTo(saved);
        String inspection = cli("designer", "inspect", file.toString(), "--point", "10,10,4");
        assertThat(inspection.contains("contains=false")).isTrue();
        assertThat(cli("designer", "inspect", file.toString(), "--point", "30,20,4")
                        .contains("contains=true"))
                .isTrue();
        choice("appearance", 2);
        click("history-0");
        click("edit-sketch-constraints");
        sketchReady();
        int widthIndex = fx(() -> {
            var values = ((SketchEditor) node("#sketch-editor"))
                    .controller()
                    .definition()
                    .constraints();
            for (int i = 0; i < values.size(); i++)
                if (values.get(i).type() == SketchGeometry.Type.LENGTH
                        && values.get(i).refs().getFirst().entityId().endsWith("bottom")) return i;
            throw new AssertionError("Missing width dimension");
        });
        click("sketch-constraints");
        key(KeyCode.HOME);
        for (int i = 0; i < widthIndex; i++) key(KeyCode.DOWN);
        text("sketch-dimension-value", "80");
        click("sketch-apply-dimension");
        sketchReady();
        assertThat(fx(() -> ((Label) node("#sketch-solve-status")).getText())).isEqualTo("Fully constrained");
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-constraints-dark.png"));
            return null;
        });
        click("constraint-finish");
        ready();
        assertThat(volume()).isCloseTo((80 * 40 - 4 * Math.PI) * 8, org.assertj.core.data.Offset.offset(0.01));
        assertThat(document().features().getFirst().id())
                .isEqualTo(saved.features().getFirst().id());
        click("undo");
        ready();
        assertThat(document()).isEqualTo(saved);
        click("redo");
        ready();
        assertThat(volume()).isCloseTo((80 * 40 - 4 * Math.PI) * 8, org.assertj.core.data.Offset.offset(0.01));
        var beforeCancel = fx(() -> workbench.controller().snapshot());
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        click("sketch-tool-line");
        sketchClick(10, 10, false);
        key(KeyCode.ESCAPE);
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor"))
                        .controller()
                        .definition()
                        .entities()
                        .isEmpty()))
                .isTrue();
        click("constraint-cancel");
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(beforeCancel);
        Files.copy(file, artifacts.resolve("gui-constrained-plate.vxb"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(artifacts.resolve("constrained-cli-inspection.txt"), inspection);
    }

    private void sketchReference(String ref) throws Exception {
        int index = fx(() -> {
            var items = ((ComboBox<?>) node("#sketch-reference")).getItems();
            for (int i = 0; i < items.size(); i++) if (items.get(i).toString().equals(ref)) return i;
            throw new AssertionError("Missing sketch reference " + ref);
        });
        choice("sketch-reference", index);
        click("sketch-select-ref");
    }

    @Test
    void sketchDirectionalSelectionAndControlClicksUseRealGestures() throws Exception {
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        fx(() -> {
            var editor = (SketchEditor) node("#sketch-editor");
            editor.controller()
                    .edit(new SketchGeometry(
                            List.of(
                                    Sketches.entity("inside", SketchGeometry.Kind.LINE, false, 20, 15, 30, 25),
                                    Sketches.entity("crossing", SketchGeometry.Kind.LINE, false, 5, 20, 50, 20)),
                            List.of(),
                            List.of("inside", "crossing")));
            return null;
        });
        sketchReady();
        click("sketch-tool-select");
        var emptySelection = fx(() -> ((Label) node("#sketch-selection")).getText());
        sketchDrag(15, 30, 25, 10, false);
        assertThat(fx(() -> ((Label) node("#sketch-selection")).getText())).isEqualTo(emptySelection);
        sketchDrag(25, 10, 15, 30, false);
        assertThat(fx(() -> ((Label) node("#sketch-selection")).getText())).isEqualTo("inside\ncrossing");
        sketchDrag(15, 30, 40, 10, false);
        assertThat(fx(() -> ((Label) node("#sketch-selection")).getText())).isEqualTo("inside");
        sketchDrag(40, 10, 15, 30, false);
        assertThat(fx(() -> ((Label) node("#sketch-selection")).getText())).isEqualTo("inside\ncrossing");
        fx(() -> {
            robot.keyPress(KeyCode.CONTROL);
            return null;
        });
        try {
            sketchClick(23, 18, false);
        } finally {
            fx(() -> {
                robot.keyRelease(KeyCode.CONTROL);
                return null;
            });
        }
        assertThat(fx(() -> ((Label) node("#sketch-selection")).getText())).isEqualTo("crossing");
        click("sketch-tool-line");
        sketchClick(55, 35, false);
        sketchDrag(15, 30, 40, 10, false);
        assertThat(fx(() -> ((Label) node("#sketch-selection")).getText())).isEqualTo("inside");
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor"))
                        .controller()
                        .definition()
                        .entities()
                        .size()))
                .isEqualTo(2);
        click("constraint-cancel");
    }

    private void sketchDrag(double x, double y, double toX, double toY, boolean cancel) throws Exception {
        var positions = fx(() -> {
            var editor = (SketchEditor) node("#sketch-editor");
            return new javafx.geometry.Point2D[] {
                editor.modelToScreen(new SketchGeometry.Vec(x, y)),
                editor.modelToScreen(new SketchGeometry.Vec(toX, toY))
            };
        });
        fx(() -> {
            robot.mouseMove(positions[0]);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mouseMove(positions[1]);
            return null;
        });
        Thread.sleep(180);
        if (cancel) key(KeyCode.ESCAPE);
        var released = new CountDownLatch(1);
        javafx.event.EventHandler<MouseEvent> releaseObserved = event -> {
            if (event.getButton() == MouseButton.PRIMARY) released.countDown();
        };
        var canvas = fx(() -> node("#sketch-canvas"));
        try {
            fx(() -> {
                canvas.addEventFilter(MouseEvent.MOUSE_RELEASED, releaseObserved);
                robot.mouseRelease(MouseButton.PRIMARY);
                return null;
            });
            assertThat(released.await(10, TimeUnit.SECONDS))
                    .as("Native sketch mouse release was not delivered")
                    .isTrue();
        } finally {
            // This FX dispatch also waits until the release handler has submitted its solve.
            fx(() -> {
                canvas.removeEventFilter(MouseEvent.MOUSE_RELEASED, releaseObserved);
                return null;
            });
        }
        sketchReady();
    }

    @Test
    void sketchPointDragsCurvatureAndRegularPolygonsUseRealControls() throws Exception {
        choice("appearance", 1);
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        click("sketch-tool-line");
        sketchClick(10, 10, false);
        sketchClick(30, 10, false);
        sketchReady();
        var before =
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
        sketchDrag(30, 10, 35, 15, true);
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition()))
                .isEqualTo(before);
        sketchDrag(30, 10, 35, 15, false);
        var moved =
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
        assertThat(moved.entities().getFirst().at(1).x()).isCloseTo(35, org.assertj.core.data.Offset.offset(0.3));
        assertThat(moved.entities().getFirst().at(1).y()).isCloseTo(15, org.assertj.core.data.Offset.offset(0.3));
        assertThat(moved.entities().getFirst().at(1).y())
                .isCloseTo(moved.entities().getFirst().at(0).y(), org.assertj.core.data.Offset.offset(1e-6));
        click("sketch-undo");
        sketchReady();
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition()))
                .isEqualTo(before);
        click("sketch-redo");
        sketchReady();
        click("constraint-cancel");
        assertThat(document().features().isEmpty()).isTrue();
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        click("sketch-tool-arc");
        sketchClick(0, 0, false);
        sketchClick(10, 0, false);
        sketchClick(0, 10, false);
        sketchReady();
        click("sketch-tool-spline");
        sketchClick(0, 10, false);
        sketchClick(-3, 10, false);
        sketchClick(-8, 6, false);
        sketchClick(-10, 0, false);
        sketchReady();
        String arcId = fx(() -> ((SketchEditor) node("#sketch-editor"))
                .controller().definition().entities().stream()
                        .filter(e -> e.kind() == SketchGeometry.Kind.ARC)
                        .findFirst()
                        .orElseThrow()
                        .id());
        String splineId = fx(() -> ((SketchEditor) node("#sketch-editor"))
                .controller().definition().entities().stream()
                        .filter(e -> e.kind() == SketchGeometry.Kind.SPLINE)
                        .findFirst()
                        .orElseThrow()
                        .id());
        sketchReference(arcId);
        click("constraint-fix");
        sketchReady();
        click("sketch-clear-selection");
        sketchReference(arcId + ".end");
        sketchReference(splineId + ".start");
        click("constraint-curvature");
        sketchReady();
        var geometry =
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor")).controller().canFinish()))
                .as(fx(() -> ((Label) node("#sketch-error")).getText()))
                .isTrue();
        assertThat(geometry.constraints().stream().anyMatch(c -> c.type() == SketchGeometry.Type.CURVATURE))
                .isTrue();
        assertThat(geometry.entity(arcId)
                        .at(1)
                        .distance(geometry.entity(splineId).at(0)))
                .isCloseTo(0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(geometry.entity(splineId).curvature(0))
                .isCloseTo(geometry.entity(arcId).curvature(1), org.assertj.core.data.Offset.offset(1e-8));
        click("sketch-tool-line");
        sketchClick(-10, 0, false);
        sketchClick(10, 0, false);
        sketchReady();
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-spline-curvature.png"));
            return null;
        });
        click("constraint-finish");
        ready();
        click("extrude");
        ready();
        key(KeyCode.ENTER);
        ready();
        assertThat(volume() > 100).isTrue();
        choice("appearance", 2);
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        click("sketch-tool-polygon");
        sketchClick(30, 20, false);
        sketchClick(45, 20, false);
        sketchReady();
        geometry = fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
        assertThat(geometry.entities().size()).isEqualTo(6);
        assertThat(geometry.constraints().getFirst().type()).isEqualTo(SketchGeometry.Type.POLYGON);
        assertThat(fx(() -> ((SketchEditor) node("#sketch-editor"))
                        .controller()
                        .result()
                        .degreesOfFreedom()))
                .isEqualTo(4);
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-regular-polygon.png"));
            return null;
        });
        click("constraint-finish");
        ready();
        assertThat(document().features().size()).isEqualTo(3);
        click("undo");
        ready();
        assertThat(document().features().size()).isEqualTo(2);
        click("redo");
        ready();
        assertThat(document().features().size()).isEqualTo(3);
    }

    private void cubeClick(String region, ViewOrientation expected) throws Exception {
        fx(() -> {
            var polygon = (Polygon) node("#view-cube-" + region);
            double x = 0, y = 0;
            var points = polygon.getPoints();
            for (int i = 0; i < points.size(); i += 2) {
                x += points.get(i);
                y += points.get(i + 1);
            }
            robot.mouseMove(polygon.localToScreen(x * 2 / points.size(), y * 2 / points.size()));
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(60);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(180);
        assertThat(fx(() -> workbench.viewport().orientationProperty().get()))
                .as(region)
                .isEqualTo(expected);
    }

    @Test
    void gridDragRotatesWithoutTakingOverPickingSketchesOrDocumentHistory() throws Exception {
        ready();
        var empty = fx(() -> workbench.controller().snapshot());
        var background = fx(() -> {
            var viewport = workbench.viewport();
            return viewport.localToScreen(viewport.getWidth() * 0.25, viewport.getHeight() * 0.7);
        });
        primaryDrag(background, 40, 24);
        assertOrbit(new ViewOrientation(40, -55), "An empty document's grid can be grabbed and rotated right/down");
        assertThat(fx(() -> node("#viewport-scene").getCursor()))
                .as("Releasing a grid drag restores the cursor")
                .isEqualTo(Cursor.DEFAULT);
        primaryDrag(background, -40, -24);
        assertOrbit(ViewOrientation.HOME, "Reverse drag returns to the original view");
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(empty);
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();

        openPrepared("rectangle-0.vxb");
        var top = fx(() -> workbench.viewport().orientationProperty().get());
        double fittedWidth = fx(() -> workbench
                .viewport()
                .modelToScreen(new Point3D(0, 0, 0))
                .distance(workbench.viewport().modelToScreen(new Point3D(60, 0, 0))));
        // At Fit the sketch stroke is subpixel on the Linux test display. Zoom in
        // before picking its curve so Robot rounding cannot hit the adjacent grid.
        fx(() -> {
            robot.mouseMove(workbench.viewport().modelToScreen(new Point3D(30, 20, 0)));
            robot.mouseWheel(-8);
            return null;
        });
        Thread.sleep(200);
        assertThat(fx(() -> workbench
                                .viewport()
                                .modelToScreen(new Point3D(0, 0, 0))
                                .distance(workbench.viewport().modelToScreen(new Point3D(60, 0, 0))))
                        > fittedWidth)
                .as("Zoom enlarges the curve before native picking")
                .isTrue();
        primaryDrag(fx(() -> workbench.viewport().modelToScreen(new Point3D(30, 0, .04))), 80, 24);
        assertThat(fx(() -> workbench.viewport().orientationProperty().get()))
                .as("A drag that starts on a sketch does not become a grid orbit")
                .isEqualTo(top);
        primaryDrag(
                fx(() -> workbench
                        .viewport()
                        .modelToScreen(new Point3D(30, 0, .04))
                        .add(0, 2)),
                40,
                12);
        assertThat(fx(() -> workbench.viewport().orientationProperty().get()))
                .as("A small screen-space picking tolerance protects thin curves after viewport resizing")
                .isEqualTo(top);
        click("menu-fit");
        click("extrude");
        ready();
        key(KeyCode.ENTER);
        ready();
        save(directory.resolve("grid navigation.vxb"));
        click("view-cube-home");
        modelClick(30, 20, 5);
        var selection = fx(() -> ((Label) workbench.lookup(".selection-title")).getText());
        assertThat(selection.startsWith("Face"))
                .as("Single-click still picks a face")
                .isTrue();
        var saved = fx(() -> workbench.controller().snapshot());
        primaryDrag(fx(() -> workbench.viewport().modelToScreen(new Point3D(30, 20, 5))), 40, 24);
        assertThat(fx(() -> workbench.viewport().orientationProperty().get()))
                .as("Dragging from a body does not orbit, even when leaving its surface")
                .isEqualTo(ViewOrientation.HOME);
        primaryDrag(fx(() -> workbench.viewport().modelToScreen(new Point3D(-15, 10, 0))), 40, 24);
        assertOrbit(new ViewOrientation(40, -55), "Grid rotation also works with a model present");
        assertThat(fx(() -> ((Label) workbench.lookup(".selection-title")).getText()))
                .as("Releasing a grid drag does not change selection")
                .isEqualTo(selection);
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(saved);
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
        fx(() -> {
            Screenshot.save(stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "grid-orbit.png"));
            return null;
        });

        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        var drawingView = fx(() -> workbench.viewport().orientationProperty().get());
        click("sketch-tool-rectangle");
        var drawingPoint =
                fx(() -> ((SketchEditor) node("#sketch-editor")).modelToScreen(new SketchGeometry.Vec(0, 0)));
        primaryDrag(drawingPoint, 40, 24);
        assertThat(fx(() -> workbench.viewport().orientationProperty().get()))
                .as("Drawing in the sketch editor cannot orbit the underlying model")
                .isEqualTo(drawingView);
        key(KeyCode.ESCAPE);
        click("constraint-cancel");
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(saved);
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
    }

    @Test
    void viewCubeSnapsOrbitsAndFitsWithoutChangingTheDocument() throws Exception {
        openPrepared("plate-5mm.vxb");
        save(directory.resolve("navigation.vxb"));
        var snapshot = fx(() -> workbench.controller().snapshot());
        click("view-cube-home");
        var point = new Point3D(60, 40, 5);
        var homeProjection = fx(() -> workbench.viewport().modelToScreen(point));
        cubeClick("front", ViewOrientation.named("Front"));
        cubeClick("front-right", ViewOrientation.lookingFrom(1, -1, 0));
        cubeClick("right", ViewOrientation.named("Right"));
        cubeClick("back-right", ViewOrientation.lookingFrom(1, 1, 0));
        cubeClick("back", ViewOrientation.named("Back"));
        cubeClick("back-left", ViewOrientation.lookingFrom(-1, 1, 0));
        cubeClick("left", ViewOrientation.named("Left"));
        cubeClick("top-left", ViewOrientation.lookingFrom(-1, 0, 1));
        cubeClick("top", ViewOrientation.named("Top"));
        cubeClick("top-front", ViewOrientation.lookingFrom(0, -1, 1));
        cubeClick("front", ViewOrientation.named("Front"));
        cubeClick("bottom-front", ViewOrientation.lookingFrom(0, -1, -1));
        cubeClick("bottom", ViewOrientation.named("Bottom"));
        cubeClick("bottom-front", ViewOrientation.lookingFrom(0, -1, -1));
        cubeClick("front", ViewOrientation.named("Front"));
        cubeClick("top-front-right", ViewOrientation.lookingFrom(1, -1, 1));
        var beforeOrbit = fx(() -> List.copyOf(((Polygon) node("#view-cube-top")).getPoints()));
        var beforePan = fx(() -> workbench.viewport().modelToScreen(point));
        navigate(false);
        assertThat(fx(() -> List.copyOf(((Polygon) node("#view-cube-top")).getPoints())))
                .as("Middle drag does not rotate the cube")
                .isEqualTo(beforeOrbit);
        assertThat(beforePan.distance(fx(() -> workbench.viewport().modelToScreen(point))) > 2)
                .isTrue();
        var orientation = fx(() -> workbench.viewport().orientationProperty().get());
        navigate(true);
        fx(() -> {
            robot.mouseWheel(4);
            return null;
        });
        Thread.sleep(200);
        assertThat(fx(() -> workbench.viewport().orientationProperty().get()))
                .as("Pan and zoom do not rotate the cube")
                .isEqualTo(orientation);
        assertThat(homeProjection.distance(fx(() -> workbench.viewport().modelToScreen(point))) > 2)
                .isTrue();
        click("view-cube-home");
        assertThat(homeProjection.distance(fx(() -> workbench.viewport().modelToScreen(point))))
                .isCloseTo(0, org.assertj.core.data.Offset.offset(0.01));
        var beforeDrag = fx(() -> workbench.viewport().modelToScreen(point));
        fx(() -> {
            var face = node("#view-cube-front");
            var bounds = face.localToScreen(face.getBoundsInLocal());
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mouseMove(robot.getMouseX() + 30, robot.getMouseY() + 20);
            return null;
        });
        Thread.sleep(150);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(180);
        assertThat(beforeDrag.distance(fx(() -> workbench.viewport().modelToScreen(point))) > 2)
                .as("Dragging cube orbits the main view")
                .isTrue();
        assertOrbit(
                new ViewOrientation(38, -50), "Right/down drag follows the pointer and must not also snap on release");
        click("view-cube-menu");
        assertThat(fx(() -> ((MenuButton) node("#view-cube-menu")).isShowing())).isTrue();
        key(KeyCode.DOWN);
        key(KeyCode.DOWN);
        key(KeyCode.ENTER);
        assertThat(fx(() -> workbench.viewport().orientationProperty().get())).isEqualTo(ViewOrientation.named("Top"));
        // The sketch editor owns input while the design viewport is disabled.
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertThat(fx(workbench::isDisabled)).isTrue();
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(snapshot);
        click("constraint-cancel");
        click("view-cube-home");
        modelClick(30, 20, 5);
        assertThat(fx(() ->
                        ((Label) workbench.lookup(".selection-title")).getText().startsWith("Face")))
                .as("Model picking still works around the overlay")
                .isTrue();
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(snapshot);
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
        for (int palette = 1; palette <= 2; palette++) {
            choice("appearance", palette);
            String name = palette == 1 ? "view-cube-light.png" : "view-cube-dark.png";
            fx(() -> {
                Screenshot.save(stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), name));
                return null;
            });
        }
    }

    @Test
    void fullReferenceWorkflowWithIndependentCliAndExternalExporter() throws Exception {
        rectangle(0);
        click("extrude");
        ready();
        text("extrude-distance", "5");
        key(KeyCode.ENTER);
        ready();
        assertThat(document().features().size()).isEqualTo(2);
        circle();
        click("cut");
        click("cut-confirm");
        ready();
        assertThat(volume()).isCloseTo(11937.168146928205, org.assertj.core.data.Offset.offset(0.01));
        Path file = directory.resolve("GUI reference.vxb");
        save(file);
        var before = DocumentReader.load(file);
        Path cliFile = directory.resolve("CLI reference.vxb");
        Files.copy(file, cliFile);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertThat(document()).isEqualTo(before);
        click("history-1");
        text("dimension-distance", "8");
        key(KeyCode.ENTER);
        ready();
        assertThat(volume()).isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(0.01));
        click("history-cursor");
        key(KeyCode.HOME);
        key(KeyCode.RIGHT);
        key(KeyCode.RIGHT);
        key(KeyCode.RIGHT);
        ready();
        assertThat(document().cursor()).isEqualTo(3);
        assertThat(volume()).isCloseTo(19200, org.assertj.core.data.Offset.offset(0.01));
        key(KeyCode.RIGHT);
        ready();
        assertThat(document().cursor()).isEqualTo(4);
        assertThat(volume()).isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(0.01));
        click("undo");
        ready();
        assertThat(volume()).isCloseTo(11937.168146928205, org.assertj.core.data.Offset.offset(0.01));
        click("redo");
        ready();
        assertThat(volume()).isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(0.01));
        click("save");
        ready();
        assertThat(document().features().stream().map(Feature::id).toList())
                .isEqualTo(before.features().stream().map(Feature::id).toList());
        Path exported = directory.resolve("GUI reference.3mf");
        click("export");
        chooseExporter("3mf");
        text("export-path", exported.toString());
        click("export-confirm");
        ready();
        assertThat(Files.size(exported) > 500).isTrue();
        Path external = directory.resolve("external report.txt");
        click("export");
        chooseExporter("document-probe");
        text("file-path", external.toString());
        click("file-confirm");
        ready();
        assertThat(Files.readString(external).startsWith("document=")).isTrue();
        cli(
                "designer",
                "feature",
                "set",
                cliFile.toString(),
                "--feature",
                before.features().get(1).id(),
                "--field",
                "distance",
                "--value",
                "8");
        assertThat(DocumentReader.load(file)).isEqualTo(DocumentReader.load(cliFile));
        String inspection = cli("designer", "inspect", file.toString(), "--point", "10,10,4");
        assertThat(inspection.contains("volume=19099.469035085127")).isTrue();
        assertThat(inspection.contains("contains=false")).isTrue();
        assertThat(inspection.contains(
                        "bounds=0.000000000,0.000000000,0.000000000,60.000000000,40.000000000,8.000000000"))
                .isTrue();
        assertThat(cli("designer", "inspect", file.toString(), "--point", "30,20,4")
                        .contains("contains=true"))
                .isTrue();
        standardView(0);
        modelClick(30, 20, 8);
        assertThat(fx(() ->
                        ((Label) workbench.lookup(".selection-title")).getText().startsWith("Face")))
                .isTrue();
        var selectedTree = fx(() -> ((TreeView<?>) node("#model-tree"))
                .getSelectionModel()
                .getSelectedItem()
                .getValue()
                .toString());
        assertThat(selectedTree).isEqualTo("Body 1");
        var navigationDocument = document();
        var point = new Point3D(60, 40, 8);
        var beforeOrientation =
                fx(() -> workbench.viewport().orientationProperty().get());
        var beforePan = fx(() -> workbench.viewport().modelToScreen(point));
        navigate(false);
        var afterMiddlePan = fx(() -> workbench.viewport().modelToScreen(point));
        assertThat(fx(() -> workbench.viewport().orientationProperty().get())).isEqualTo(beforeOrientation);
        assertThat(beforePan.distance(afterMiddlePan) > 2)
                .as("Middle drag pans the view")
                .isTrue();
        navigate(true);
        var afterPan = fx(() -> workbench.viewport().modelToScreen(point));
        assertThat(afterMiddlePan.distance(afterPan) > 2)
                .as("Right drag pans the view")
                .isTrue();
        fx(() -> {
            robot.mouseWheel(4);
            return null;
        });
        Thread.sleep(250);
        assertThat(afterPan.distance(fx(() -> workbench.viewport().modelToScreen(point))) > 2)
                .as("Zoom changes projection")
                .isTrue();
        assertThat(document()).isEqualTo(navigationDocument);
        standardView(1);
        standardView(0);
        click("menu-fit");
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        Files.createDirectories(artifacts);
        choice("appearance", 1);
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("workbench-light.png"));
            return null;
        });
        choice("appearance", 2);
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("workbench-dark.png"));
            return null;
        });
        assertThat(Arrays.equals(
                        Files.readAllBytes(artifacts.resolve("workbench-light.png")),
                        Files.readAllBytes(artifacts.resolve("workbench-dark.png"))))
                .isFalse();
        Files.copy(file, artifacts.resolve("gui-reference-8mm.vxb"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(exported, artifacts.resolve("gui-reference.3mf"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(external, artifacts.resolve("external-provider.txt"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(artifacts.resolve("cli-inspection.txt"), inspection);
    }

    @Test
    void customSpacerCreationEditingAndMissingPluginRecoveryUseRealControls() throws Exception {
        click("custom-feature");
        choice("custom-type", 1); // Sorted descriptors: failed fixture, then the spacer.
        text("custom-innerDiameter", "30");
        click("custom-add");
        assertThat(document().features().isEmpty()).isTrue();
        assertThat(fx(() -> ((Label) node("#custom-error")).getText().contains("innerDiameter")))
                .isTrue();
        assertThat(fx(() -> node("#custom-innerDiameter").getStyleClass().contains("invalid")))
                .isTrue();
        click("custom-cancel");
        assertThat(fx(() -> workbench.controller().snapshot().canUndo())).isFalse();
        click("custom-feature");
        choice("custom-type", 1);
        text("custom-height", "5");
        click("custom-add");
        ready();
        assertThat(document().features().size()).isEqualTo(1);
        assertThat(volume()).isCloseTo(Math.PI * 96 * 5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(fx(() -> ((Button) node("#history-0")).getText().contains("spacer")))
                .isTrue();
        text("dimension-height", "8");
        key(KeyCode.ENTER);
        ready();
        assertThat(volume()).isCloseTo(Math.PI * 96 * 8, org.assertj.core.data.Offset.offset(0.01));
        click("undo");
        ready();
        assertThat(volume()).isCloseTo(Math.PI * 96 * 5, org.assertj.core.data.Offset.offset(0.01));
        click("redo");
        ready();
        Path file = directory.resolve("GUI spacer.vxb");
        save(file);
        var before = DocumentReader.load(file);
        Path plugins = directory.resolve("plugins with spaces"), jar = plugins.resolve("provider.jar");
        fx(() -> {
            workbench.close();
            return null;
        });
        workbench.whenClosed().toCompletableFuture().get(20, TimeUnit.SECONDS);
        Files.delete(jar);
        fx(() -> {
            workbench = Workbench.create(stage, plugins);
            stage.setScene(workbench.createScene(964, 648));
            stage.toFront();
            stage.requestFocus();
            return null;
        });
        Thread.sleep(1200);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertThat(fx(() -> workbench.controller().build().valid())).isFalse();
        assertThat(volume()).isCloseTo(0, org.assertj.core.data.Offset.offset(0.0));
        assertDesignRejectsMeshExport("Rebuild failed");
        click("save");
        ready();
        assertThat(DocumentReader.load(file)).isEqualTo(before);
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), jar);
        click("history-0");
        click("custom-rebuild");
        ready();
        assertThat(fx(() -> workbench.controller().canExport())).isTrue();
        assertThat(volume()).isCloseTo(Math.PI * 96 * 8, org.assertj.core.data.Offset.offset(0.01));
        assertThat(document()).isEqualTo(before);
        Path exported = directory.resolve("GUI spacer.3mf");
        click("export");
        chooseExporter("3mf");
        text("export-path", exported.toString());
        click("export-confirm");
        ready();
        assertThat(Files.size(exported) > 500).isTrue();
        assertThat(cli("designer", "inspect", file.toString(), "--point", "0,0,4", "--plugin-dir", plugins.toString())
                        .contains("contains=false"))
                .isTrue();
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "custom-spacer.png"));
            return null;
        });
    }

    private void assertOrbit(ViewOrientation expected, String message) throws Exception {
        var actual = fx(() -> workbench.viewport().orientationProperty().get());
        // Native mouse coordinates can accumulate sub-pixel floating-point rounding.
        assertThat(actual.tilt()).isCloseTo(expected.tilt(), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(actual.orbit()).isCloseTo(expected.orbit(), org.assertj.core.data.Offset.offset(1e-9));
    }

    private void primaryDrag(Point2D start, double dx, double dy) throws Exception {
        fx(() -> {
            robot.mouseMove(start);
            return null;
        });
        Thread.sleep(120);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        try {
            Thread.sleep(100);
            fx(() -> {
                robot.mouseMove(start.add(dx, dy));
                return null;
            });
            Thread.sleep(150);
        } finally {
            fx(() -> {
                robot.mouseRelease(MouseButton.PRIMARY);
                return null;
            });
        }
        Thread.sleep(180);
    }

    private void navigate(boolean pan) throws Exception {
        fx(() -> {
            var bounds = workbench.viewport().localToScreen(workbench.viewport().getBoundsInLocal());
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            robot.mousePress(pan ? MouseButton.SECONDARY : MouseButton.MIDDLE);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mouseMove(robot.getMouseX() + 60, robot.getMouseY() + 35);
            return null;
        });
        Thread.sleep(150);
        fx(() -> {
            robot.mouseRelease(pan ? MouseButton.SECONDARY : MouseButton.MIDDLE);
            return null;
        });
        Thread.sleep(150);
    }

    @Test
    void signedExtrusionDragAndEscapeHaveExactUndoSemantics() throws Exception {
        openPrepared("rectangle-12.vxb");
        click("extrude");
        ready();
        text("extrude-distance", "-3");
        key(KeyCode.ENTER);
        ready();
        assertThat(document().features().size()).isEqualTo(2);
        try (var evaluation = Evaluator.evaluate(document())) {
            var bounds = evaluation.bodies().getFirst().solid().bounds();
            assertThat(bounds.minZ()).isCloseTo(9, org.assertj.core.data.Offset.offset(0.001));
            assertThat(bounds.maxZ()).isCloseTo(12, org.assertj.core.data.Offset.offset(0.001));
        }
        click("undo");
        ready();
        click("history-0");
        click("extrude");
        ready();
        var before = document();
        fx(() -> {
            var point = workbench.viewport().handleScreenPosition();
            assertThat(point).isNotNull();
            robot.mouseMove(point);
            robot.mousePress(MouseButton.PRIMARY);
            robot.mouseMove(point.getX(), point.getY() - 60);
            return null;
        });
        Thread.sleep(200);
        key(KeyCode.ESCAPE);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        ready();
        assertThat(document()).isEqualTo(before);
        assertThat(fx(() -> workbench.controller().hasPreview())).isFalse();
        click("history-0");
        click("extrude");
        ready();
        fx(() -> {
            var point = workbench.viewport().handleScreenPosition();
            robot.mouseMove(point);
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            var point = workbench.viewport().handleScreenPosition();
            robot.mouseMove(point.getX(), point.getY() - 70);
            return null;
        });
        Thread.sleep(200);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        ready();
        assertThat(document().features().size()).isEqualTo(2);
        assertThat(((Feature.Extrude) document().features().getLast()).distance() > 5)
                .isTrue();
        click("undo");
        ready();
        assertThat(document().features().size()).isEqualTo(1);
        click("redo");
        ready();
        assertThat(document().features().size()).isEqualTo(2);
    }

    @Test
    void invalidDimensionsAndBrokenInputRepairStayEditable() throws Exception {
        openPrepared("rectangle-12.vxb"); // A parallel offset plane is editable; the origin plane is fixed.
        var original = document();
        text("dimension-offsetZ", "NaN");
        key(KeyCode.ENTER);
        assertThat(document()).isEqualTo(original);
        assertThat(fx(() -> ((Label) node("#tool-error")).getText().contains("finite")))
                .isTrue();
        var sketch = (Feature.Sketch) original.features().getFirst();
        var broken = new CadDocument(
                "broken", List.of(sketch, new Feature.Extrude("bad", new Feature.ProfileRef("missing", "none"), 8)), 2);
        Path file = directory.resolve("broken.vxb");
        DocumentWriter.save(broken, file, false);
        // Open through the actual file action after saving the current document to avoid an
        // unrelated prompt.
        save(directory.resolve("scratch.vxb"));
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertThat(fx(() -> workbench.controller().build().valid())).isFalse();
        assertThat(fx(() -> workbench.controller().build().bodies().isEmpty())).isTrue();
        click("history-1");
        assertThat(fx(() -> ((javafx.scene.layout.VBox) node("#feature-diagnostics"))
                        .getChildren()
                        .isEmpty()))
                .isFalse();
        choice("repair-sketch", 0);
        click("repair");
        ready();
        assertThat(volume()).isCloseTo(19200, org.assertj.core.data.Offset.offset(0.01));
        assertThat(document().features().getLast().id()).isEqualTo("bad");
        assertThat(fx(() -> ((javafx.scene.layout.VBox) node("#feature-diagnostics"))
                        .getChildren()
                        .isEmpty()))
                .isTrue();
    }

    @Test
    void searchableSketchTypesSupportKeyboardSelectionAndCancellation() throws Exception {
        ready();
        key(KeyCode.K);
        click("choose-plane-xy");
        sketchReady();
        key(KeyCode.M);
        assertThat(fx(() -> ((TextField) node("#sketch-type-search")).getText()))
                .isEqualTo("");
        text("sketch-type-search", "CiRcLe");
        assertThat(fx(() ->
                        ((ListView<?>) node("#sketch-type-results")).getItems().size()))
                .isEqualTo(5);
        text("sketch-type-search", "no such tool");
        assertThat(fx(() -> node("#sketch-type-confirm").isDisabled())).isTrue();
        key(KeyCode.ESCAPE);
        assertThat(sketchGeometry().entities().isEmpty()).isTrue();
        click("sketch-create-type");
        text("sketch-type-search", "2-point rectangle");
        key(KeyCode.ENTER);
        sketchNumericPoint(0, 0);
        sketchNumericPoint(60, 40);
        assertThat(sketchGeometry().entities().size()).isEqualTo(4);
        click("constraint-cancel");
        ready();
    }

    @Test
    void keyboardModelingNavigationAndSketchDraftsRespectFocusAndModalBoundaries() throws Exception {
        ready();
        click("operations-menu");
        fx(() -> {
            var menu = (MenuButton) node("#operations-menu");
            for (var id : List.of("extrude", "split-body")) {
                var item = menu.getItems().stream()
                        .filter(value -> id.equals(value.getId()))
                        .findFirst()
                        .orElseThrow();
                assertThat(item.getAccelerator())
                        .as("Visible operation shortcut: " + id)
                        .isEqualTo(KeyboardShortcuts.primary(id));
                assertThat(node("#" + id)).isNotNull();
            }
            return null;
        });
        key(KeyCode.ESCAPE);
        click("menu-fit");
        key(KeyCode.E);
        assertThat(fx(() -> workbench.lookup("#extrude-distance")))
                .as("Disabled extrusion stays unavailable")
                .isNull();
        shortcut(KeyCode.E, false);
        assertThat(fx(workbench::isDisabled))
                .as("An empty design can still use document exporters")
                .isTrue();
        assertThat(fx(() -> node("#export-format"))).isNotNull();
        key(KeyCode.ESCAPE);
        assertThat(fx(workbench::isDisabled))
                .as("Cancelling export restores the workbench")
                .isFalse();
        assertThat(fx(() -> workbench.lookup("#rectangle"))).isNull();
        assertThat(fx(() -> workbench.lookup("#circle"))).isNull();
        var empty = fx(() -> workbench.controller().snapshot());
        key(KeyCode.R);
        key(KeyCode.C);
        assertThat(fx(() -> workbench.controller().snapshot())).isEqualTo(empty);
        assertThat(fx(workbench::isDisabled))
                .as("R/C cannot open a standalone design tool")
                .isFalse();
        key(KeyCode.K);
        click("choose-plane-xy");
        sketchReady();
        assertShortcutCaption("sketch-tool-rectangle", "Rectangle", "R");
        assertShortcutCaption("sketch-tool-circle", "Circle", "C");
        assertShortcutCaption("sketch-tool-select", "Select (V)", "V");
        assertShortcutCaption("sketch-tool-polygon", "Polygon", "g");
        saveShortcutLabels("en-light");
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        sketchNumericPoint(0, 0);
        sketchNumericPoint(60, 40);
        click("constraint-finish");
        ready();
        assertThat(document().features().size()).isEqualTo(1);
        var rectangle = document();
        text("dimension-offsetZ", "=30+30");
        shortcut(KeyCode.Z, false);
        assertThat(document()).as("Typing undo must not remove a CAD feature").isEqualTo(rectangle);
        assertThat(fx(() -> ((TextField) node("#dimension-offsetZ")).getText())).isNotEqualTo("=30+30");
        shortcut(KeyCode.Y, false);
        assertThat(fx(() -> ((TextField) node("#dimension-offsetZ")).getText())).isEqualTo("=30+30");
        text("dimension-offsetZ", "circle");
        assertThat(fx(() -> workbench.lookup("#sketch-diameter")))
                .as("Letters stay in the field")
                .isNull();
        text("dimension-offsetZ", "0");
        click("menu-fit");
        key(KeyCode.E);
        assertThat(fx(() -> node("#extrude-distance"))).isNotNull();
        key(KeyCode.ESCAPE);
        assertThat(document())
                .as("Cancelled extrusion creates no history entry")
                .isEqualTo(rectangle);
        click("menu-fit");
        key(KeyCode.E);
        text("extrude-distance", "-5");
        key(KeyCode.ENTER);
        ready();
        assertThat(document().features().size()).isEqualTo(2);
        assertThat(volume()).isCloseTo(12000, org.assertj.core.data.Offset.offset(.01));
        click("menu-fit");
        shortcut(KeyCode.Z, false);
        ready();
        assertThat(document().features().size()).isEqualTo(1);
        shortcut(KeyCode.Z, true);
        ready();
        assertThat(document().features().size()).isEqualTo(2);
        var part = document();
        for (var entry : Map.of(
                        KeyCode.DIGIT0,
                        "Isometric",
                        KeyCode.DIGIT1,
                        "Top",
                        KeyCode.DIGIT2,
                        "Front",
                        KeyCode.DIGIT3,
                        "Right",
                        KeyCode.DIGIT4,
                        "Back",
                        KeyCode.DIGIT5,
                        "Left",
                        KeyCode.DIGIT6,
                        "Bottom")
                .entrySet()) {
            key(entry.getKey());
            assertThat(fx(() -> workbench.viewport().orientationProperty().get().name()))
                    .isEqualTo(entry.getValue());
        }
        key(KeyCode.F);
        assertThat(document()).as("Navigation must not edit the document").isEqualTo(part);
        save(directory.resolve("keyboard-workspace.vxb"));
        shortcut(KeyCode.DIGIT2, false);
        assertThat(fx(() -> ((TabPane) node("#workspaces"))
                        .getSelectionModel()
                        .getSelectedItem()
                        .getId()))
                .isEqualTo("workspace-print");
        key(KeyCode.K);
        assertThat(fx(() -> stage.getScene().lookup("#sketch-editor")))
                .as("Design tools are inactive in Print")
                .isNull();
        shortcut(KeyCode.DIGIT1, false);
        shortcut(KeyCode.P, true);
        assertThat(fx(() -> node("#parameters-cancel"))).isNotNull();
        shortcut(KeyCode.N, false);
        assertThat(document()).isEqualTo(part);
        click("parameters-cancel");
        shortcut(KeyCode.E, false);
        assertThat(fx(() -> node("#export-format"))).isNotNull();
        key(KeyCode.ESCAPE);
        click("menu-fit");
        key(KeyCode.K);
        click("choose-plane-xy");
        sketchReady();
        assertThat(fx(() -> stage.getScene().lookup("#constraint-plane"))).isNull();
        key(KeyCode.R);
        sketchClick(0, 0, false);
        sketchClick(20, 10, false);
        sketchReady();
        assertThat(sketchGeometry().entities().size()).isEqualTo(4);
        shortcut(KeyCode.Z, false);
        sketchReady();
        assertThat(sketchGeometry().entities().isEmpty()).isTrue();
        shortcut(KeyCode.Y, false);
        sketchReady();
        assertThat(sketchGeometry().entities().size()).isEqualTo(4);
        assertThat(document()).as("Sketch undo/redo stays in the draft").isEqualTo(part);
        int constraints = sketchGeometry().constraints().size();
        assertThat(constraints > 0).isTrue();
        click("sketch-constraints");
        key(KeyCode.HOME);
        key(KeyCode.DELETE);
        sketchReady();
        assertThat(sketchGeometry().constraints().size())
                .as("Delete works on the focused constraint list")
                .isEqualTo(constraints - 1);
        shortcut(KeyCode.Z, false);
        sketchReady();
        assertThat(sketchGeometry().constraints().size()).isEqualTo(constraints);
        openShortcutHelp();
        assertThat(fx(() -> node("#keyboard-shortcuts-list"))).isNotNull();
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "keyboard-shortcuts-light.png"));
            return null;
        });
        shortcut(KeyCode.ENTER, false);
        assertThat(document()).as("Help overlay blocks finish-sketch").isEqualTo(part);
        closeShortcutHelp();
        shortcut(KeyCode.ENTER, false);
        ready();
        assertThat(document().features().size())
                .as("Finish sketch commits exactly one operation")
                .isEqualTo(3);
        click("menu-fit");
        shortcut(KeyCode.Z, false);
        ready();
        assertThat(document()).isEqualTo(part);
        click("history-cursor");
        key(KeyCode.HOME);
        ready();
        assertThat(document().cursor()).isEqualTo(0);
        key(KeyCode.K);
        assertThat(fx(() -> stage.getScene().lookup("#sketch-editor")))
                .as("Rollback blocks new features")
                .isNull();
        shortcut(KeyCode.E, false);
        assertThat(fx(() -> node("#export-format")))
                .as("Rollback still allows document exporters")
                .isNotNull();
        key(KeyCode.ESCAPE);
        assertDesignRejectsMeshExport("Return timeline cursor to the end");
        fx(() -> {
            node("#history-cursor").requestFocus();
            return null;
        });
        key(KeyCode.END);
        ready();
        assertThat(document()).isEqualTo(part);
        choice("appearance", 2);
        openShortcutHelp();
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "keyboard-shortcuts-dark.png"));
            return null;
        });
        closeShortcutHelp();
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertShortcutCaption("sketch-tool-rectangle", "Rectangle", "R");
        assertShortcutCaption("sketch-tool-select", "Select (V)", "V");
        saveShortcutLabels("en-dark");
        click("constraint-cancel");
    }

    private void assertShortcutCaption(String id, String expected, String letter) throws Exception {
        fx(() -> {
            var button = (ButtonBase) node("#" + id);
            button.getScene().getRoot().applyCss();
            button.getScene().getRoot().layout();
            var caption = (javafx.scene.layout.HBox) button.lookup(".shortcut-caption");
            assertThat(caption).as(id).isNotNull();
            var fragments = caption.getChildren().stream()
                    .map(javafx.scene.text.Text.class::cast)
                    .toList();
            assertThat(fragments.stream()
                            .map(javafx.scene.text.Text::getText)
                            .collect(java.util.stream.Collectors.joining()))
                    .as(id)
                    .isEqualTo(expected);
            var marked = fragments.stream()
                    .filter(javafx.scene.text.Text::isUnderline)
                    .toList();
            assertThat(marked.size()).as(id).isEqualTo(1);
            assertThat(marked.getFirst().getText()).as(id).isEqualTo(letter);
            assertThat(marked.getFirst().getFill()).as(id).isEqualTo(button.getTextFill());
            assertThat(marked.getFirst().getFont()).as(id).isEqualTo(button.getFont());
            assertThat(button.isMnemonicParsing())
                    .as("Do not register unrelated Alt mnemonics")
                    .isFalse();
            var bounds = button.localToScene(button.getLayoutBounds());
            var textBounds = caption.localToScene(caption.getLayoutBounds());
            assertThat(textBounds.getMinX() >= bounds.getMinX() - 1 && textBounds.getMaxX() <= bounds.getMaxX() + 1)
                    .as("Full caption fits: " + id)
                    .isTrue();
            assertThat(textBounds.getMinY() >= bounds.getMinY() - 1 && textBounds.getMaxY() <= bounds.getMaxY() + 1)
                    .as("Caption height fits: " + id)
                    .isTrue();
            return null;
        });
    }

    private void saveShortcutLabels(String languageAndPalette) throws Exception {
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(
                            System.getProperty("voxelbrew.ui.artifacts"),
                            "shortcut-labels-" + languageAndPalette + ".png"));
            return null;
        });
    }

    @Test
    void fileShortcutsAndUnsavedSaveDiscardCancelProtectDocuments() throws Exception {
        openPrepared("rectangle-12.vxb");
        text("dimension-offsetZ", "13");
        key(KeyCode.ENTER);
        ready();
        var original = document();
        assertThat(fx(() -> stage.getTitle().startsWith("●"))).isTrue();
        click("new");
        click("unsaved-cancel");
        assertThat(document()).isEqualTo(original);
        Path file = directory.resolve("Save As ~ design.vxb");
        shortcut(KeyCode.S, true);
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
        text("dimension-offsetZ", "1");
        key(KeyCode.ENTER);
        ready();
        assertThat(fx(() -> workbench.controller().dirty())).isTrue();
        shortcut(KeyCode.N, false);
        click("unsaved-save");
        ready();
        assertThat(document().features().isEmpty()).isTrue();
        assertThat(((Feature.Sketch) DocumentReader.load(file).features().getFirst())
                        .plane()
                        .offsetZ())
                .isEqualTo(1);
        shortcut(KeyCode.O, false);
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        click("history-0");
        text("dimension-offsetZ", "2");
        key(KeyCode.ENTER);
        ready();
        click("new");
        click("unsaved-discard");
        ready();
        assertThat(document().features().isEmpty()).isTrue();
        assertThat(((Feature.Sketch) DocumentReader.load(file).features().getFirst())
                        .plane()
                        .offsetZ())
                .isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void printingSplitPlacementPetgAndActualPreviewMatchCli() throws Exception {
        ready();
        click("workspace-design");
        key(KeyCode.F1);
        Thread.sleep(500);
        assertThat(fx(() -> ((Label) node("#context-help-content")).getText().contains("Create a sketch")))
                .isTrue();
        key(KeyCode.ESCAPE);
        assertThat(fx(() -> ((TabPane) node("#workspaces"))
                        .getTabs().stream()
                                .filter(tab -> tab.getId().equals("workspace-print"))
                                .findFirst()
                                .orElseThrow()
                                .isDisable()))
                .isTrue();
        assertThat(fx(() -> ((TabPane) node("#workspaces"))
                        .getSelectionModel()
                        .getSelectedItem()
                        .getId()
                        .equals("workspace-design")))
                .isTrue();
        openPrepared("rectangle-0.vxb");
        click("extrude");
        ready();
        text("extrude-distance", "8");
        key(KeyCode.ENTER);
        ready();
        circle();
        click("cut");
        click("cut-confirm");
        ready();
        click("split-body");
        text("split-offset", "30");
        click("split-confirm");
        ready();
        assertThat(fx(() -> workbench.controller().build().bodies().size())).isEqualTo(2);
        assertThat(volume()).isCloseTo(19099.469035085127, org.assertj.core.data.Offset.offset(.01));
        click("undo");
        ready();
        assertThat(fx(() -> workbench.controller().build().bodies().size())).isEqualTo(1);
        choice("appearance", 2);
        click("workspace-print");
        ready();
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
        assertThat(fx(() -> node("#print-instance").isDisabled())).isTrue();
        assertThat(fx(() -> workbench.lookup("#print-support-instance").isDisabled()))
                .isTrue();
        assertThat(fx(() -> workbench.lookup("#print-support-id").isDisabled())).isTrue();
        assertThat(fx(() -> {
                    var choice = (ComboBox<String>) node("#print-body");
                    return choice.getConverter().toString(choice.getValue());
                }))
                .isEqualTo("Body 1");
        key(KeyCode.F1);
        assertThat(fx(() -> ((Label) node("#context-help-content")).getText().contains("Add a CAD body")))
                .isTrue();
        key(KeyCode.ESCAPE);
        click("print-view-3d");
        assertThat(fx(() -> ((RadioButton) node("#print-view-3d")).isSelected()))
                .isTrue();
        var plateCaption = centerOf("print-plate-size");
        fx(() -> {
            robot.mouseMove(plateCaption);
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(80);
        fx(() -> {
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(250);
        text("print-bed-width", "250");
        text("print-bed-depth", "220");
        key(KeyCode.ENTER);
        ready();
        assertThat(PrintProfile.resolve(document().printSetup())
                        .printer()
                        .bed()
                        .get(2)
                        .x())
                .isEqualTo(250);
        assertThat(PrintProfile.resolve(document().printSetup())
                        .printer()
                        .bed()
                        .get(2)
                        .y())
                .isEqualTo(220);
        click("print-view-2d");
        click("print-add");
        ready();
        click("print-view-3d");
        fx(() -> {
            assertThat(((ComboBox<?>) node("#print-body")).getButtonCell().getText())
                    .isEqualTo("Body 1");
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "printing-3d-placement.png"));
            return null;
        });
        click("print-view-2d");
        text("print-x", "80");
        text("print-y", "30");
        text("print-rotate-z", "90");
        click("print-place");
        ready();
        click("print-add");
        ready();
        assertThat(document().printSetup().instances().size()).isEqualTo(2);
        click("print-remove");
        ready();
        assertThat(document().printSetup().instances().size()).isEqualTo(1);
        click("print-tab-process");
        choice("print-material", 1);
        click("print-tab-job");
        ready();
        assertThat(document().printSetup().materialId()).isEqualTo("PETG");
        Path file = directory.resolve("GUI print setup.vxb");
        save(file);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertThat(document()).isEqualTo(DocumentReader.load(file));
        click("workspace-print");
        click("print-tab-job");
        assertThat(PrintProfile.resolve(document().printSetup())
                        .printer()
                        .bed()
                        .get(2)
                        .x())
                .isEqualTo(250);
        Path job = directory.resolve("GUI printer job.gcode");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> node("#print-slice").isDisabled()) && System.nanoTime() < deadline) Thread.sleep(25);
        click("print-slice");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().running()) && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertThat(fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()))
                .as(fx(() -> ((Label) node("#print-status")).getText()))
                .isEqualTo(PrintController.State.SUCCEEDED);
        try (var registry = java.util.ServiceLoader.load(dev.voxelbrew.api.exporter.ExporterProviderRegistry.class)
                .findFirst()
                .orElseThrow()
                .withDirectory(directory)) {
            fx(() -> workbench
                            .controller()
                            .exportDocument(
                                    registry.require("gcode"),
                                    dev.voxelbrew.util.testing.FileExportWindows.destination(job, false)))
                    .get(20, TimeUnit.SECONDS);
        }
        var actual = GcodeReader.read(job);
        assertThat(actual.modelLayers().size()).isEqualTo(40);
        assertThat(actual.report().get("material")).isEqualTo("PETG");
        click("print-layer");
        key(KeyCode.END);
        click("print-legend");
        Thread.sleep(500); // Let the TitledPane expansion finish before targeting its last row.
        click("print-role-travel");
        click("print-role-perimeter");
        Path evidence = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(() -> {
            Screenshot.save(stage.getScene(), evidence.resolve("printing-dark.png"));
            return null;
        });
        click("workspace-design");
        choice("appearance", 1);
        click("workspace-print");
        fx(() -> {
            Screenshot.save(stage.getScene(), evidence.resolve("printing-light.png"));
            return null;
        });
        Path cliJob = directory.resolve("CLI printer job.gcode");
        assertThat(cli("slicer", "validate", file.toString()).contains("VALID")).isTrue();
        cli(
                "slicer",
                "job",
                "slice",
                file.toString(),
                "--output",
                cliJob.toString(),
                "--slicer",
                PrusaSlicerBackend.defaultExecutable().toString());
        assertThat(GcodeReader.read(cliJob).segments()).isEqualTo(actual.segments());
        Files.copy(file, evidence.resolve("GUI print setup.vxb"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(job, evidence.resolve("GUI printer job.gcode"), StandardCopyOption.REPLACE_EXISTING);
        click("print-tab-job");
        click("print-clean-slicing");
        click("print-tab-process");
        text("print-layer-height", "0.3");
        click("print-tab-job");
        ready();
        assertThat(fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()))
                .isEqualTo(PrintController.State.IDLE);
        assertThat(fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().result()))
                .isNull();
    }

    @Test
    void futureFileErrorsAreVisibleAndKeepTheCurrentDesign() throws Exception {
        // Version compatibility and byte preservation are covered headlessly in DocumentControllerTest.
        // Keep only the real file dialog -> status label -> current design wiring here.
        Path file = directory.resolve("current design.vxb");
        DocumentWriter.save(
                new CadDocument(
                        "current",
                        List.of(new Feature.Sketch(
                                "plate", "profile", Feature.Plane.origin("XY"), new Feature.Rectangle(0, 0, 60, 40))),
                        1),
                file,
                false);
        open(file);
        var before = document();
        var saved = Files.readAllBytes(file);
        var message = DocumentProto.Document.parseFrom(Arrays.copyOfRange(saved, 44, saved.length));
        var futureFile = directory.resolve("future design.vxb");
        Files.write(futureFile, versionedDocument(message, "65534.65534.65534"));
        click("open");
        text("file-path", futureFile.toString());
        click("file-confirm");
        ready();
        String error = fx(() -> ((Label) node("#status")).getText());
        assertThat(error.contains("VoxelBrew 65534.65534.65534")).as(error).isTrue();
        assertThat(error.contains("supported version " + Version.version()))
                .as(error)
                .isTrue();
        assertThat(error.contains("Update VoxelBrew")).as(error).isTrue();
        assertThat(document()).isEqualTo(before);
        assertThat(fx(() -> workbench.controller().path())).isEqualTo(file.toAbsolutePath());
        assertThat(fx(() -> workbench.controller().dirty())).isFalse();
    }

    private static byte[] versionedDocument(DocumentProto.Document document, String version) throws Exception {
        byte[] payload =
                document.toBuilder().setApplicationVersion(version).build().toByteArray();
        return ByteBuffer.allocate(44 + payload.length)
                .put(new byte[] {'J', 'C', 'A', 'D', 13, 37, 13, 37})
                .putInt(payload.length)
                .put(MessageDigest.getInstance("SHA-256").digest(payload))
                .put(payload)
                .array();
    }

    private String cli(String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dvoxelbrew.native.path=" + System.getProperty("voxelbrew.native.path"));
        command.add("-Dvoxelbrew.home=" + directory.resolve("cli-settings"));
        command.add("-cp");
        command.add(Path.of(System.getProperty("voxelbrew.cli.distribution"), "lib") + java.io.File.separator + "*");
        command.add("dev.voxelbrew.cli.VoxelBrew");
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(directory, "cli-", ".txt");
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String output = Files.readString(log);
        assertThat(process.exitValue()).as(output).isEqualTo(0);
        return output;
    }
}
