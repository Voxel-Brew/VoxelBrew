package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.api.model.Transform;
import dev.voxelbrew.api.model.mesh.MeshData;
import dev.voxelbrew.engine.model.BodyExtrusion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.*;
import javafx.scene.text.Text;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import lombok.NonNull;

/** Z-up CAD coordinates map to FX (x,-z,y); camera orbit never changes model coordinates. */
public final class Viewport extends StackPane {
    private final Group bodies = new Group(),
            outlines = new Group(),
            sketches = new Group(),
            aids = new Group(),
            world = new Group();
    private final Rotate tilt = new Rotate(ViewOrientation.HOME.tilt(), Rotate.X_AXIS);
    private final Rotate orbit = new Rotate(ViewOrientation.HOME.orbit(), Rotate.Y_AXIS);
    private final Translate center = new Translate(), pan = new Translate();

    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final MeshView groundGrid = new MeshView();
    private final List<GridAxis> axes = List.of(
            new GridAxis("x", toView(1, 0, 0), "#bb6666"),
            new GridAxis("y", toView(0, 1, 0), "#55a184"),
            new GridAxis("z", toView(0, 0, 1), "#5b86d6"));

    private final Map<String, MeshView> bodyViews = new LinkedHashMap<>();

    private final Map<String, MeshView> edgeViews = new LinkedHashMap<>();

    private final Map<String, TechnicalEdges> edgeData = new LinkedHashMap<>();

    private final SubScene scene;

    private final ReadOnlyObjectWrapper<ViewOrientation> orientation =
            new ReadOnlyObjectWrapper<>(ViewOrientation.HOME);

    private final ViewCube viewCube;

    private final TranslationService translations;

    private final javafx.scene.layout.Pane planeLabels = new javafx.scene.layout.Pane();
    private final Map<GridAxis, Text> axisLabels = new LinkedHashMap<>();

    private final Map<javafx.scene.control.Label, Feature.Plane> originLabels = new LinkedHashMap<>();

    private final java.util.ArrayList<SketchPick> sketchPicks = new java.util.ArrayList<>();
    private final Map<SketchDisplay, SketchOverlay> constrainedOverlays = new LinkedHashMap<>();

    private final Group handle = new Group();
    private final Group profileHighlights = new Group();
    private final javafx.scene.canvas.Canvas sketchCanvas = new javafx.scene.canvas.Canvas();

    private final Group planeTargets = new Group(), faceHighlight = new Group(), selectedEdges = new Group();

    private final Map<String, double[]> edgeSamples = new LinkedHashMap<>();

    private final ConstructionView construction = new ConstructionView(), constructionPreview = new ConstructionView();

    private Rendering.Style rendering = Rendering.Style.SHADED;
    private List<Path> profileCurves = List.of();

    private boolean dark, edgesPending;

    private Map<String, MeshData> currentMeshes = Map.of();

    private double lastX, lastY;

    private boolean primaryOrbit;

    private double distance = 180;
    private CameraView beforeProfileEdit;

    private BiConsumer<String, Integer> selection = (id, face) -> {};

    private BiConsumer<String, Integer> edgeSelection = (id, edge) -> {};

    private boolean edgePickEnabled;

    private String selected;

    private List<Feature> shownSketches = List.of();

    private String shownSketchSelection;

    private String pressedSketch;

    private Consumer<Point3D> planeClick;

    private double planeZ;

    private Consumer<MouseEvent> extrusionPick;

    private double handleDistance;

    private Point3D handleAnchor, handleNormal;

    private BiConsumer<String, Integer> planeHover;

    private String highlightedBody;

    private int highlightedFace = -1;

    private Map<String, dev.voxelbrew.api.model.BodyStyle> bodyStyles = Map.of();

    private record SketchPick(String featureId, Point3D a, Point3D b) {}

    private record SketchDisplay(String id, boolean selected, boolean outline) {}

    private record SketchOverlay(Group group, List<SketchPick> picks) {}

    private record CameraView(ViewOrientation orientation, Point3D center, Point3D pan, double distance) {}

    public static Point3D toView(double x, double y, double z) {
        return new Point3D(x, -z, y);
    }

    public static Point3D toModel(Point3D p) {
        return new Point3D(p.getX(), p.getZ(), -p.getY());
    }

    /** Convert a CAD placement to FX axes without changing handedness or millimeter scale. */
    static Affine viewTransform(dev.voxelbrew.api.model.Transform frame) {
        var m = frame.matrix();
        return new Affine(m[0], -m[2], m[1], m[3], -m[8], m[10], -m[9], -m[11], m[4], -m[6], m[5], m[7]);
    }

    private static Point3D sketchPoint(Feature.Plane plane, dev.voxelbrew.api.model.sketch.SketchGeometry.Vec point) {
        var model = plane.frame().apply(point.x(), point.y(), plane.offsetZ() + .04);
        return new Point3D(model[0], model[1], model[2]);
    }

    private static Point3D transformPoint(Transform transform, Point3D point) {
        var result = transform.apply(point.getX(), point.getY(), point.getZ());
        return new Point3D(result[0], result[1], result[2]);
    }

    private static Cylinder line(Point3D a, Point3D b, double radius, Color color) {
        Point3D delta = b.subtract(a), middle = a.midpoint(b);
        var line = new Cylinder(radius, delta.magnitude(), 6);
        line.setMaterial(new PhongMaterial(color));
        line.setTranslateX(middle.getX());
        line.setTranslateY(middle.getY());
        line.setTranslateZ(middle.getZ());
        Point3D axis = Rotate.Y_AXIS.crossProduct(delta);
        double angle = Rotate.Y_AXIS.angle(delta);
        if (axis.magnitude() > 1e-9) line.getTransforms().add(new Rotate(angle, axis));
        return line;
    }

    private static MeshView meshView(MeshData data) {
        var mesh = new TriangleMesh();
        float[] points = new float[data.vertexCount() * 3];
        for (int v = 0; v < data.vertexCount(); v++) {
            var p = toView(data.coordinate(v, 0), data.coordinate(v, 1), data.coordinate(v, 2));
            points[3 * v] = (float) p.getX();
            points[3 * v + 1] = (float) p.getY();
            points[3 * v + 2] = (float) p.getZ();
        }
        int[] faces = new int[data.triangleCount() * 6];
        for (int t = 0; t < data.triangleCount(); t++) {
            for (int c = 0; c < 3; c++) faces[6 * t + 2 * c] = data.vertexIndex(t, c);
        }
        mesh.getPoints().setAll(points);
        mesh.getTexCoords().setAll(0, 0);
        mesh.getFaces().setAll(faces);
        mesh.getFaceSmoothingGroups().setAll(new int[data.triangleCount()]);
        var result = new MeshView(mesh);
        result.setCullFace(CullFace.BACK);
        result.setMaterial(new PhongMaterial(Color.web("#91a8b9")));
        return result;
    }

    private static void addProfileHighlight(
            Group group, dev.voxelbrew.api.model.sketch.SketchGeometry geometry, double offset) {
        try {
            group.getChildren()
                    .add(profileHighlight(
                            dev.voxelbrew.engine.sketch.SketchProfiles.profileContours(geometry), offset));
        } catch (IllegalArgumentException incompleteProfile) {
            // An open sketch still displays its curves.
        }
    }

    private static Path profileHighlight(
            List<List<dev.voxelbrew.api.model.sketch.SketchGeometry.Vec>> rings, double offset) {
        var fill = new Path();
        for (var ring : rings) {
            var first = ring.getFirst();
            fill.getElements().add(new MoveTo(first.x(), first.y()));
            for (var point : ring.subList(1, ring.size())) {
                fill.getElements().add(new LineTo(point.x(), point.y()));
            }
            fill.getElements().add(new ClosePath());
        }
        fill.setFillRule(FillRule.EVEN_ODD);
        fill.setStroke(null);
        fill.setFill(Color.web("#eead48", 0.52));
        fill.setMouseTransparent(true);
        fill.getStyleClass().add("sketch-profile-highlight");
        fill.getTransforms().add(new Rotate(90, Rotate.X_AXIS));
        fill.setTranslateY(-offset - 0.035);
        return fill;
    }

    private static List<SketchStrokes.Segment> sketchSegments(
            List<dev.voxelbrew.api.model.sketch.SketchGeometry.Entity> curves, boolean construction) {
        var segments = new java.util.ArrayList<SketchStrokes.Segment>();
        for (var curve : curves) {
            var points = curve.sample();
            for (int i = 1; i < points.size(); i++) {
                if (construction && i % 4 >= 2) continue;
                var a = points.get(i - 1);
                var b = points.get(i);
                if (a.distance(b) < 1e-9) continue;
                segments.add(new SketchStrokes.Segment(a, b));
            }
        }
        return segments;
    }

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "The viewport shares the window's translation service.")
    public Viewport(TranslationService translations) {
        this.translations = translations;
        setId("viewport");
        setMinSize(300, 250);
        world.getChildren()
                .addAll(
                        aids,
                        bodies,
                        outlines,
                        profileHighlights,
                        sketches,
                        handle,
                        planeTargets,
                        faceHighlight,
                        selectedEdges,
                        construction,
                        constructionPreview);
        outlines.setMouseTransparent(true);
        outlines.setDepthTest(DepthTest.ENABLE);
        faceHighlight.setMouseTransparent(true);
        faceHighlight.setDepthTest(DepthTest.DISABLE);
        selectedEdges.setMouseTransparent(true);
        selectedEdges.setDepthTest(DepthTest.DISABLE);
        world.getTransforms().addAll(pan, tilt, orbit, center);
        scene = new SubScene(
                new Group(world, new AmbientLight(Color.WHITE)), 800, 600, true, SceneAntialiasing.BALANCED);
        scene.setId("viewport-scene");
        scene.setCamera(camera);
        camera.setNearClip(0.01);
        camera.setFarClip(100000);
        camera.setTranslateZ(-distance);
        camera.setFieldOfView(35);
        scene.widthProperty().bind(widthProperty());
        scene.heightProperty().bind(heightProperty());
        planeLabels.setPickOnBounds(false);
        var axisOverlay = new javafx.scene.layout.Pane();
        axisOverlay.setMouseTransparent(true);
        axisOverlay.visibleProperty().bind(aids.visibleProperty());
        for (var axis : axes) {
            var label = new Text(axis.getAccessibleText());
            label.setId(axis.getId() + "-label");
            label.setManaged(false);
            label.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
            label.setFill(((PhongMaterial) axis.getMaterial()).getDiffuseColor());
            axisLabels.put(axis, label);
            axisOverlay.getChildren().add(label);
        }
        sketchCanvas.setManaged(false);
        sketchCanvas.setMouseTransparent(true);
        getChildren().addAll(scene, sketchCanvas, axisOverlay, planeLabels);
        world.localToSceneTransformProperty().addListener((o, a, b) -> {
            positionPlaneLabels();
            requestLayout();
            scheduleEdges();
        });
        camera.translateZProperty().addListener((o, a, b) -> {
            positionPlaneLabels();
            requestLayout();
            scheduleEdges();
        });
        scene.heightProperty().addListener((o, a, b) -> scheduleEdges());
        viewCube =
                new ViewCube(this::setOrientation, this::orbitBy, this::home, () -> fit(currentMeshes), translations);
        StackPane.setAlignment(viewCube, Pos.TOP_RIGHT);
        StackPane.setMargin(viewCube, new Insets(8));
        getChildren().add(viewCube);
        setDark(false);
        grid();
        installPanHandlers();
        scene.setOnMousePressed(this::beginOrbit);
        scene.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - lastX, dy = e.getSceneY() - lastY;
            lastX = e.getSceneX();
            lastY = e.getSceneY();
            if (primaryOrbit && e.isPrimaryButtonDown() && planeClick == null && !e.isStillSincePress()) {
                scene.setCursor(Cursor.CLOSED_HAND);
                orbitBy(dx, dy);
                e.consume();
            }
        });
        scene.setOnMouseReleased(e -> {
            primaryOrbit = false;
            scene.setCursor(Cursor.DEFAULT);
        });
        scene.setOnScroll(e -> {
            distance = Math.clamp(distance * Math.exp(-e.getDeltaY() * 0.0015), 2, 100000);
            camera.setTranslateZ(-distance);
            e.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_CLICKED, this::pickSketchOnClick);
    }

    private void pickSketchOnClick(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || !e.isStillSincePress()) return;
        if (extrusionPick != null) {
            extrusionPick.accept(e);
            e.consume();
        } else if (planeClick != null) {
            var local = scene.screenToLocal(e.getScreenX(), e.getScreenY());
            var point = pointOnPlane(local.getX(), local.getY(), planeZ);
            if (point != null) planeClick.accept(point);
            e.consume();
        } else if (pressedSketch != null) {
            var featureId = pressedSketch;
            pressedSketch = null;
            selection.accept(featureId, -1);
            e.consume();
        }
    }

    QuickMenu quickMenu(
            Parent theme,
            java.util.function.Supplier<QuickMenu.Content> content,
            java.util.function.Supplier<List<QuickMenu.Action>> workspaces) {
        return new QuickMenu(
                scene,
                theme,
                translations,
                content,
                workspaces,
                (dx, dy) -> {
                    primaryOrbit = false;
                    pressedSketch = null;
                    pan.setX(pan.getX() + dx * distance / 700);
                    pan.setY(pan.getY() + dy * distance / 700);
                },
                target -> true);
    }

    private boolean isNavigationBackground(Node picked) {
        for (Node node = picked; node != null; node = node.getParent()) {
            if (node == bodies || node == sketches || node == handle || node == planeTargets) return false;
        }
        return true;
    }

    /** Thin visible curves remain pickable despite native mouse rounding and viewport resizing. */
    private String nearbySketch(double x, double y) {
        if (sketches.isMouseTransparent() || !sketches.isVisible()) return null;
        double nearest = 4 * 4; // logical screen pixels, independent of zoom and CAD millimeters
        String feature = null;
        for (var candidate : sketchPicks) {
            if (!inFrontOfCamera(candidate.a()) || !inFrontOfCamera(candidate.b())) continue;
            var a = modelToScreen(candidate.a());
            var b = modelToScreen(candidate.b());
            if (a == null || b == null) continue;
            double dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
            double lengthSquared = dx * dx + dy * dy;
            double t = lengthSquared == 0
                    ? 0
                    : Math.clamp(((x - a.getX()) * dx + (y - a.getY()) * dy) / lengthSquared, 0, 1);
            double px = x - a.getX() - t * dx, py = y - a.getY() - t * dy;
            double squared = px * px + py * py;
            if (squared < nearest) {
                nearest = squared;
                feature = candidate.featureId();
            }
        }
        return feature;
    }

    private boolean inFrontOfCamera(Point3D model) {
        var point = camera.sceneToLocal(world.localToScene(toView(model.getX(), model.getY(), model.getZ())));
        return point.getZ() >= camera.getNearClip() && point.getZ() <= camera.getFarClip();
    }

    public void onSelection(BiConsumer<String, Integer> listener) {
        selection = listener;
    }

    public void onEdgeSelection(BiConsumer<String, Integer> listener) {
        edgeSelection = listener;
    }

    public void pickEdges(boolean enabled) {
        edgePickEnabled = enabled;
    }

    public void highlightSelectedEdges(String body, java.util.Set<Integer> indices) {
        selectedEdges.getChildren().clear();
        var samples = edgeSamples.get(body);
        if (samples == null) return;
        for (int i = 4; i < samples.length; i += 4) {
            if (samples[i] != samples[i - 4] || !indices.contains((int) samples[i])) continue;
            var a = toView(samples[i - 3], samples[i - 2], samples[i - 1]);
            var b = toView(samples[i + 1], samples[i + 2], samples[i + 3]);
            if (a.distance(b) > 1e-9) selectedEdges.getChildren().add(line(a, b, .2, Color.web("#f3ad3d")));
        }
    }

    private int nearbyEdge(double[] samples, java.util.List<Integer> allowed, double x, double y) {
        int closest = -1;
        double best = 7 * 7;
        for (int i = 4; i < samples.length; i += 4) {
            if (samples[i] != samples[i - 4]) continue;
            if (!allowed.contains((int) samples[i])) continue;
            var a = modelToScreen(new Point3D(samples[i - 3], samples[i - 2], samples[i - 1]));
            var b = modelToScreen(new Point3D(samples[i + 1], samples[i + 2], samples[i + 3]));
            if (a == null || b == null) continue;
            double dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
            double length = dx * dx + dy * dy;
            double t = length == 0 ? 0 : Math.clamp(((x - a.getX()) * dx + (y - a.getY()) * dy) / length, 0, 1);
            double squared = Math.pow(x - a.getX() - t * dx, 2) + Math.pow(y - a.getY() - t * dy, 2);
            if (squared < best) {
                best = squared;
                closest = (int) samples[i];
            }
        }
        return closest;
    }

    public void showConstruction(
            java.util.Map<String, dev.voxelbrew.api.model.construction.ConstructionGeometry.Datum> values,
            Consumer<String> selected) {
        construction.show(values, selected);
    }

    public void previewConstruction(dev.voxelbrew.api.model.construction.ConstructionGeometry.Datum value) {
        constructionPreview.preview(value);
    }

    public void drawOnPlane(double z, Consumer<Point3D> listener) {
        planeZ = z;
        planeClick = listener;
    }

    public void stopDrawing() {
        planeClick = null;
    }

    public void pickSketchPlane(Consumer<Feature.Plane> choose, BiConsumer<String, Integer> hover) {
        stopPlaneSelection();
        planeHover = hover;
        sketches.setMouseTransparent(true);
        home();
        String[] names = {"XY", "XZ", "YZ"};
        String[] colors = {"#df9e35", "#5297d8", "#42a783"};
        for (int i = 0; i < names.length; i++) {
            var plane = Feature.Plane.origin(names[i]);
            var color = Color.web(colors[i]);
            var mesh = new TriangleMesh();
            mesh.getPoints().setAll(2, 0, 2, 30, 0, 2, 30, 0, 30, 2, 0, 30);
            mesh.getTexCoords().setAll(0, 0);
            mesh.getFaces().setAll(0, 0, 1, 0, 2, 0, 0, 0, 2, 0, 3, 0);
            var face = new MeshView(mesh);
            face.setCullFace(CullFace.NONE);
            face.setId("sketch-plane-" + names[i].toLowerCase(java.util.Locale.ROOT));
            face.setMaterial(new PhongMaterial(color.deriveColor(0, 1, 1, .32)));
            var group = new Group(face);
            group.getTransforms().add(viewTransform(plane.frame()));
            Point3D[] corners = {
                new Point3D(2, 0, 2), new Point3D(30, 0, 2), new Point3D(30, 0, 30), new Point3D(2, 0, 30)
            };
            for (int c = 0; c < 4; c++) {
                var edge = line(corners[c], corners[(c + 1) % 4], .10, color);
                edge.setMouseTransparent(true);
                group.getChildren().add(edge);
            }
            var label = new javafx.scene.control.Label(names[i]);
            label.setId("sketch-plane-label-" + names[i].toLowerCase(java.util.Locale.ROOT));
            label.getStyleClass().add("sketch-plane-label");
            label.setStyle("-fx-border-color: " + colors[i] + ";");
            label.setManaged(false);
            label.setCursor(Cursor.HAND);
            UiHelp.describe(label, translations.translate("help.choose-plane"));
            label.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
                    choose.accept(plane);
                    e.consume();
                }
            });
            originLabels.put(label, plane);
            planeLabels.getChildren().add(label);
            face.setOnMouseEntered(e -> face.setMaterial(new PhongMaterial(color.deriveColor(0, 1, 1, .70))));
            face.setOnMouseExited(e -> face.setMaterial(new PhongMaterial(color.deriveColor(0, 1, 1, .32))));
            face.setCursor(Cursor.HAND);
            face.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
                    choose.accept(plane);
                    e.consume();
                }
            });
            planeTargets.getChildren().add(group);
        }
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        positionPlaneLabels();
        updateGrid();
        drawProfile();
    }

    private void drawProfile() {
        boolean visible =
                !profileCurves.isEmpty() || !profileHighlights.getChildren().isEmpty();
        sketchCanvas.setVisible(visible);
        if (!visible) return;
        sketchCanvas.setWidth(scene.getWidth());
        sketchCanvas.setHeight(scene.getHeight());
        var gc = sketchCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, scene.getWidth(), scene.getHeight());
        double focalLength = scene.getHeight() / (2 * Math.tan(Math.toRadians(camera.getFieldOfView() / 2)));
        for (var node : profileHighlights.getChildren()) {
            if (node.isVisible()) drawProfilePath((Path) node, focalLength);
        }
        profileCurves.forEach(path -> drawProfilePath(path, focalLength));
    }

    // Project vector geometry before drawing so line width and antialiasing are in screen pixels.
    private void drawProfilePath(Path path, double focalLength) {
        var projection = path.getLocalToSceneTransform();
        var gc = sketchCanvas.getGraphicsContext2D();
        gc.beginPath();
        for (var element : path.getElements()) {
            boolean start = element instanceof MoveTo;
            Point3D point;
            if (element instanceof MoveTo move) {
                point = projection.transform(move.getX(), move.getY(), 0);
            } else if (element instanceof LineTo line) {
                point = projection.transform(line.getX(), line.getY(), 0);
            } else {
                gc.closePath();
                continue;
            }
            double depth = distance + point.getZ();
            if (depth < camera.getNearClip()) return;
            double x = scene.getWidth() / 2 + point.getX() * focalLength / depth;
            double y = scene.getHeight() / 2 + point.getY() * focalLength / depth;
            if (start) gc.moveTo(x, y);
            else gc.lineTo(x, y);
        }
        if (path.getFill() != null) {
            gc.setFillRule(path.getFillRule());
            gc.setFill(path.getFill());
            gc.fill();
        } else {
            gc.setStroke(path.getStroke());
            gc.setLineWidth(1.5);
            gc.stroke();
        }
    }

    private void positionPlaneLabels() {
        originLabels.forEach((label, plane) -> {
            var p = plane.frame().apply(16, 16, 0);
            var screen = modelToScreen(new Point3D(p[0], p[1], p[2]));
            if (screen == null) {
                label.setVisible(false);
                return;
            }
            var point = screenToLocal(screen);
            label.autosize();
            label.setVisible(point != null
                    && point.getX() >= 0
                    && point.getX() <= getWidth()
                    && point.getY() >= 0
                    && point.getY() <= getHeight());
            if (point != null) {
                label.relocate(point.getX() - label.getWidth() / 2, point.getY() - label.getHeight() / 2);
            }
        });
    }

    public void stopPlaneSelection() {
        planeTargets.getChildren().clear();
        originLabels.clear();
        planeLabels.getChildren().clear();
        highlightFace(null, -1);
        planeHover = null;
        sketches.setMouseTransparent(false);
    }

    public void highlightFace(String body, int face) {
        if (java.util.Objects.equals(body, highlightedBody) && face == highlightedFace) return;
        highlightedBody = body;
        highlightedFace = face;
        faceHighlight.getChildren().clear();
        if (body == null) return;
        var data = currentMeshes.get(body);
        if (data == null || face < 0) return;
        var indices = new java.util.ArrayList<Integer>();
        for (int t = 0; t < data.triangleCount(); t++) {
            if (data.faceIndex(t) == face) {
                for (int c = 0; c < 3; c++) {
                    indices.add(data.vertexIndex(t, c));
                }
            }
        }
        if (indices.isEmpty()) return;
        var overlay = meshView(new MeshData(
                data.vertices(), indices.stream().mapToInt(Integer::intValue).toArray(), new int[indices.size() / 3]));
        overlay.setMaterial(new PhongMaterial(Color.web("#efa93e", .55)));
        overlay.setCullFace(CullFace.NONE);
        faceHighlight.getChildren().add(overlay);
    }

    javafx.geometry.Point2D modelToScreen(Point3D point) {
        return world.localToScreen(toView(point.getX(), point.getY(), point.getZ()));
    }

    javafx.geometry.Point2D handleScreenPosition() {
        return handle.getChildren().isEmpty()
                ? null
                : handle.getChildren().getLast().localToScreen(Point3D.ZERO);
    }

    public Point3D pointOnPlane(double x, double y, double z) {
        return pointOnPlane(x, y, z, Transform.IDENTITY);
    }

    private Point3D pointOnPlane(double x, double y, double z, Transform worldToPlane) {
        double scale = 2
                * Math.tan(dev.voxelbrew.api.model.units.Measurements.radians(camera.getFieldOfView() / 2))
                / scene.getHeight();
        Point3D a = toModel(world.parentToLocal(new Point3D(0, 0, -distance)));
        Point3D b = toModel(world.parentToLocal(
                new Point3D((x - scene.getWidth() / 2) * scale, (y - scene.getHeight() / 2) * scale, 1 - distance)));
        a = transformPoint(worldToPlane, a);
        b = transformPoint(worldToPlane, b);
        Point3D direction = b.subtract(a);
        if (Math.abs(direction.getZ()) < 1e-10) return null;
        double t = (z - a.getZ()) / direction.getZ();
        return t > 0 ? a.add(direction.multiply(t)) : null;
    }

    public void showSketches(List<Feature> features, String selectedSketch) {
        sketches.setDepthTest(planeClick == null && beforeProfileEdit == null ? DepthTest.INHERIT : DepthTest.DISABLE);
        if (shownSketches.equals(features)) {
            if (java.util.Objects.equals(shownSketchSelection, selectedSketch)) return;
        } else {
            constrainedOverlays.clear();
        }
        shownSketches = List.copyOf(features);
        shownSketchSelection = selectedSketch;
        sketches.getChildren().clear();
        sketchPicks.clear();
        pressedSketch = null;
        String sourceSketch = features.stream()
                .filter(f -> f.id().equals(selectedSketch))
                .filter(f -> f instanceof Feature.Extrude || BodyExtrusion.modifiesBody(f))
                .map(f -> BodyExtrusion.from(f).extrusion().profile().sketchId())
                .findFirst()
                .orElse(null);
        var consumed = consumedSketches(features);
        for (var feature : features) {
            if (feature instanceof Feature.Sketch sketch) {
                boolean outline = beforeProfileEdit != null || sketch.id().equals(sourceSketch);
                if (consumed.contains(sketch.id()) && !sketch.id().equals(selectedSketch) && !outline) continue;
                if (sketch.profile() instanceof Feature.Constrained constrained) {
                    var display = new SketchDisplay(sketch.id(), sketch.id().equals(selectedSketch), outline);
                    var overlay = constrainedOverlays.computeIfAbsent(
                            display, key -> constrainedOverlay(sketch, constrained.geometry(), key));
                    sketches.getChildren().add(overlay.group());
                    sketchPicks.addAll(overlay.picks());
                    continue;
                }
                showProfile(sketch, selectedSketch, outline);
            }
        }
        profileCurves = sketches.lookupAll(".sketch-curves").stream()
                .filter(Path.class::isInstance)
                .map(Path.class::cast)
                .toList();
        requestLayout();
    }

    private java.util.Set<String> consumedSketches(List<Feature> features) {
        if (rendering != Rendering.Style.TECHNICAL) return java.util.Set.of();
        var consumed = new java.util.HashSet<String>();
        for (var feature : features) {
            if (feature instanceof Feature.Extrude || BodyExtrusion.modifiesBody(feature)) {
                consumed.add(BodyExtrusion.from(feature).extrusion().profile().sketchId());
            } else if (feature instanceof Feature.ThroughCut cut) {
                consumed.add(cut.profile().sketchId());
            }
        }
        return consumed;
    }

    private SketchOverlay constrainedOverlay(
            Feature.Sketch sketch, dev.voxelbrew.api.model.sketch.SketchGeometry geometry, SketchDisplay display) {
        boolean chosen = display.selected();
        var group = new Group();
        var picks = new java.util.ArrayList<SketchPick>();
        group.getTransforms().add(viewTransform(sketch.plane().frame()));
        if (chosen) addProfileHighlight(group, geometry, sketch.plane().offsetZ());
        for (var entity : geometry.entities()) {
            java.util.List<dev.voxelbrew.api.model.sketch.SketchGeometry.Entity> curves;
            try {
                curves = geometry.curves(entity);
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            var segments = sketchSegments(curves, entity.construction());
            if (!segments.isEmpty()) {
                String stroke = entity.construction() ? "#8795a3" : "#2d839f";
                if (display.outline()) {
                    var path = new Path();
                    for (var segment : segments) {
                        path.getElements()
                                .addAll(
                                        new MoveTo(segment.a().x(), segment.a().y()),
                                        new LineTo(segment.b().x(), segment.b().y()));
                    }
                    group.getChildren().add(profileOutline(path, sketch.plane(), stroke));
                    continue;
                }
                for (var segment : segments) {
                    picks.add(new SketchPick(
                            sketch.id(),
                            sketchPoint(sketch.plane(), segment.a()),
                            sketchPoint(sketch.plane(), segment.b())));
                }
                var view = meshView(SketchStrokes.mesh(segments, sketch.plane().offsetZ() + .04, chosen ? .14 : .075));
                view.getStyleClass().add("sketch-curves");
                view.setMaterial(new PhongMaterial(Color.web(chosen ? "#dc8d18" : stroke)));
                group.getChildren().add(view);
            }
        }
        group.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
                selection.accept(sketch.id(), -1);
                e.consume();
            }
        });
        return new SketchOverlay(group, List.copyOf(picks));
    }

    private Path profileOutline(Path path, Feature.Plane plane, String stroke) {
        path.setFill(null);
        path.setStroke(Color.web(stroke));
        path.setDepthTest(DepthTest.DISABLE);
        path.getTransforms().add(new Rotate(90, Rotate.X_AXIS));
        path.setOpacity(0);
        path.setTranslateY(-plane.offsetZ());
        path.setMouseTransparent(true);
        path.getStyleClass().add("sketch-curves");
        return path;
    }

    void pickExtrusionRegion(Feature.Plane plane, Consumer<Point3D> pick) {
        var inverse = plane.placement().inverse();
        extrusionPick = event -> {
            var local = scene.screenToLocal(event.getScreenX(), event.getScreenY());
            var point = pointOnPlane(local.getX(), local.getY(), 0, inverse);
            if (point != null) pick.accept(point);
        };
    }

    void isolateProfile(boolean isolate) {
        if (isolate == (beforeProfileEdit != null)) return;
        if (isolate) {
            beforeProfileEdit = new CameraView(
                    orientation.get(),
                    new Point3D(center.getX(), center.getY(), center.getZ()),
                    new Point3D(pan.getX(), pan.getY(), 0),
                    distance);
        } else {
            var saved = beforeProfileEdit;
            beforeProfileEdit = null;
            setOrientation(saved.orientation());
            positionCamera(saved.center(), saved.pan(), saved.distance());
        }
        shownSketches = List.of();
        constrainedOverlays.clear();
        sketches.getChildren().clear();
        sketchPicks.clear();
        profileCurves = List.of();
        aids.setVisible(!isolate && rendering != Rendering.Style.TECHNICAL);
        viewCube.setVisible(!isolate);
        bodies.setVisible(!isolate);
        outlines.setVisible(!isolate);
        construction.setVisible(!isolate);
    }

    void frameProfile(Feature.Sketch sketch) {
        var frame = sketch.plane().frame().matrix();
        var direction = ViewOrientation.lookingFrom(frame[2], frame[6], frame[10]);
        profileHighlights.getTransforms().setAll(viewTransform(sketch.plane().frame()));
        setOrientation(direction);
        var bounds = sketches.getBoundsInLocal();
        if (bounds.isEmpty()) return;
        var middle = new Point3D(bounds.getCenterX(), bounds.getCenterY(), bounds.getCenterZ());
        var projected = tilt.createConcatenation(orbit).transform(bounds);
        double aspect = Math.max(1, scene.getWidth()) / Math.max(1, scene.getHeight());
        double span = Math.max(projected.getHeight(), projected.getWidth() / aspect);
        positionCamera(
                middle.multiply(-1),
                Point3D.ZERO,
                Math.max(2, .6 * span / Math.tan(Math.toRadians(camera.getFieldOfView() / 2))));
    }

    private void positionCamera(Point3D translation, Point3D offset, double depth) {
        center.setX(translation.getX());
        center.setY(translation.getY());
        center.setZ(translation.getZ());
        pan.setX(offset.getX());
        pan.setY(offset.getY());
        distance = depth;
        camera.setTranslateZ(-depth);
    }

    Path editingHighlight(Feature.Plane plane, List<List<dev.voxelbrew.api.model.sketch.SketchGeometry.Vec>> rings) {
        var path = profileHighlight(rings, plane.offsetZ());
        path.setOpacity(0);
        path.visibleProperty().addListener(observable -> requestLayout());
        profileHighlights.getChildren().add(path);
        requestLayout();
        return path;
    }

    void editingWholeHighlight(
            Feature.Sketch sketch,
            dev.voxelbrew.api.model.sketch.SketchGeometry geometry,
            javafx.beans.value.ObservableBooleanValue whole) {
        try {
            editingHighlight(sketch.plane(), dev.voxelbrew.engine.sketch.SketchProfiles.profileContours(geometry))
                    .visibleProperty()
                    .bind(whole);
        } catch (IllegalArgumentException incomplete) {
            // Individual regions can still be selected when the entire sketch cannot form a profile.
        }
    }

    public void clearHandle() {
        extrusionPick = null;
        profileHighlights.getChildren().clear();
        requestLayout();
        handle.getChildren().clear();
    }

    public void updateExtrusionDistance(double value) {
        handleDistance = value;
        if (handle.getChildren().isEmpty()) return;
        var tip = handle.getChildren().getLast();
        double end = value + Math.copySign(5, value);
        var endpoint = handleAnchor.add(handleNormal.multiply(end));
        tip.setTranslateX(endpoint.getX());
        tip.setTranslateY(endpoint.getY());
        tip.setTranslateZ(endpoint.getZ());
        var shaft = line(handleAnchor, endpoint, .35, Color.web("#f3ad3d"));
        shaft.setMouseTransparent(true);
        handle.getChildren().set(0, shaft);
    }

    public void extrusionHandle(Feature.Sketch sketch, double value, DoubleConsumer preview, Runnable accept) {
        if (!handle.getChildren().isEmpty()) return;
        double x, y;
        if (sketch.profile() instanceof Feature.Rectangle r) {
            x = r.x() + r.width() / 2;
            y = r.y() + r.height() / 2;
        } else if (sketch.profile() instanceof Feature.Circle c) {
            x = c.centerX();
            y = c.centerY();
        } else {
            var geometry = ((Feature.Constrained) sketch.profile()).geometry();
            var points = geometry.entities().stream()
                    .flatMap(e -> geometry.curves(e).stream())
                    .flatMap(e -> e.sample().stream())
                    .toList();
            x = points.stream()
                    .mapToDouble(dev.voxelbrew.api.model.sketch.SketchGeometry.Vec::x)
                    .average()
                    .orElse(0);
            y = points.stream()
                    .mapToDouble(dev.voxelbrew.api.model.sketch.SketchGeometry.Vec::y)
                    .average()
                    .orElse(0);
        }
        var point = sketch.plane().placement().apply(x, y, 0);
        var matrix = sketch.plane().frame().matrix();
        var anchor = toView(point[0], point[1], point[2]);
        handleAnchor = anchor;
        handleNormal = toView(matrix[2], matrix[6], matrix[10]);
        var tip = new Sphere(1.5, 12);
        tip.setId("extrusion-handle");
        tip.setMaterial(new PhongMaterial(Color.web("#f3ad3d")));
        var shaft = line(
                anchor, anchor.add(handleNormal.multiply(value + Math.copySign(5, value))), 0.35, Color.web("#f3ad3d"));
        shaft.setMouseTransparent(true);
        final double[] start = new double[5];
        tip.setOnMousePressed(e -> {
            start[0] = e.getScreenX();
            start[1] = e.getScreenY();
            start[2] = handleDistance;
            var a = world.localToScreen(handleAnchor);
            var b = world.localToScreen(handleAnchor.add(handleNormal));
            start[3] = b.getX() - a.getX();
            start[4] = b.getY() - a.getY();
            if (Math.hypot(start[3], start[4]) < .1) {
                start[3] = 0;
                start[4] = -Math.max(scene.getHeight(), 1) / distance;
            }
            e.consume();
        });
        tip.setOnMouseDragged(e -> {
            double next = start[2]
                    + ((e.getScreenX() - start[0]) * start[3] + (e.getScreenY() - start[1]) * start[4])
                            / (start[3] * start[3] + start[4] * start[4]);
            if (Math.abs(next) < 0.01) next = 0.01;
            updateExtrusionDistance(next);
            preview.accept(next);
            e.consume();
        });
        tip.setOnMouseReleased(e -> {
            accept.run();
            e.consume();
        });
        handle.getChildren().addAll(shaft, tip);
        updateExtrusionDistance(value);
    }

    public void setDark(boolean dark) {
        this.dark = dark;
        var background = Color.web(dark ? "#1e1f22" : "#f2f3f5");
        scene.setFill(rendering == Rendering.Style.TECHNICAL ? Color.WHITE : background);
        viewCube.setDark(dark);
    }

    Rendering.Style rendering() {
        return rendering;
    }

    void setRendering(@NonNull Rendering.Style style) {
        rendering = style;
        boolean technical = style == Rendering.Style.TECHNICAL;
        aids.setVisible(!technical && beforeProfileEdit == null);
        requestLayout();
        setDark(dark);
        select(selected);
        var previousSketches = shownSketches;
        shownSketches = List.of();
        showSketches(previousSketches, shownSketchSelection);
        scheduleEdges();
    }

    public void showBodies(List<DocumentController.Body> evaluated) {
        var meshes = new LinkedHashMap<String, MeshData>();
        evaluated.forEach(body -> meshes.put(body.id(), body.mesh()));
        currentMeshes = Map.copyOf(meshes);
        bodies.getChildren().clear();
        bodyViews.clear();
        outlines.getChildren().clear();
        edgeViews.clear();
        edgeData.clear();
        edgeSamples.clear();
        selectedEdges.getChildren().clear();
        for (var body : evaluated) {
            edgeData.put(body.id(), body.edges());
            edgeSamples.put(body.id(), body.edgeSamples());
            var mesh = new TriangleMesh();
            mesh.getTexCoords().setAll(0, 0);
            var edges = new MeshView(mesh);
            edges.setId("outline-" + body.id());
            edges.setCullFace(CullFace.NONE);
            edgeViews.put(body.id(), edges);
            outlines.getChildren().add(edges);
        }
        meshes.forEach((id, data) -> {
            MeshView view = meshView(data);
            bodyViews.put(id, view);
            view.setId("body-" + id);
            view.setOnMouseMoved(e -> {
                if (planeHover != null) {
                    int triangle = e.getPickResult().getIntersectedFace();
                    planeHover.accept(id, triangle >= 0 ? data.faceIndex(triangle) : -1);
                }
            });
            view.setOnMouseExited(e -> highlightFace(null, -1));
            view.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
                    int triangle = e.getPickResult().getIntersectedFace();
                    if (edgePickEnabled) {
                        var body = evaluated.stream()
                                .filter(b -> b.id().equals(id))
                                .findFirst()
                                .orElseThrow();
                        int edge = nearbyEdge(
                                body.edgeSamples(),
                                triangle < 0 ? java.util.List.of() : body.edgesOnFace(data.faceIndex(triangle)),
                                e.getScreenX(),
                                e.getScreenY());
                        if (edge > 0) {
                            edgeSelection.accept(id, edge);
                            e.consume();
                            return;
                        }
                    }
                    selection.accept(id, triangle >= 0 ? data.faceIndex(triangle) : -1);
                    e.consume();
                }
            });
            bodies.getChildren().add(view);
        });
        select(selected);
        scheduleEdges();
    }

    private void scheduleEdges() {
        if (edgesPending || edgeData.isEmpty()) return;
        edgesPending = true;
        // Coalesce the tilt/orbit/pan/fit transform changes into one update per UI pulse.
        javafx.application.Platform.runLater(() -> {
            edgesPending = false;
            updateEdges();
        });
    }

    private void updateEdges() {
        if (scene.getHeight() <= 0) return;
        // Preserve useful depth precision at the current zoom. The fixed 0.01 mm near
        // plane otherwise makes coplanar outlines speckle on large/distant parts.
        camera.setNearClip(Math.max(.01, distance / 1000));
        var eye = world.parentToLocal(new Point3D(0, 0, -distance));
        var modelEye = toModel(eye);
        var sight = new TechnicalEdges.Point(modelEye.getX(), modelEye.getY(), modelEye.getZ());
        var forward = world.parentToLocal(new Point3D(0, 0, 1 - distance))
                .subtract(eye)
                .normalize();
        double pixelScale = 2 * Math.tan(Math.toRadians(camera.getFieldOfView() / 2)) / scene.getHeight();
        edgeData.forEach((id, data) -> {
            var visible = data.edges().stream()
                    .filter(edge -> edge.visibleFrom(sight))
                    .toList();
            var points = new float[visible.size() * 12];
            var faces = new int[visible.size() * 12];
            int count = 0;
            for (var edge : visible) {
                var a = toView(edge.a().x(), edge.a().y(), edge.a().z());
                var b = toView(edge.b().x(), edge.b().y(), edge.b().z());
                var middle = a.midpoint(b);
                double depth = middle.subtract(eye).dotProduct(forward);
                if (depth <= camera.getNearClip()) continue;
                var side = b.subtract(a).crossProduct(eye.subtract(middle));
                if (side.magnitude() < 1e-12) continue;
                double radius = depth * pixelScale * .75;
                side = side.normalize().multiply(radius);
                // A roughly one-pixel bias prevents coplanar z-fighting; the body depth
                // buffer
                // still conceals rear edges, including edges behind other bodies.
                a = a.add(eye.subtract(a).normalize().multiply(radius * 1.5));
                b = b.add(eye.subtract(b).normalize().multiply(radius * 1.5));
                Point3D[] quad = {a.add(side), a.subtract(side), b.add(side), b.subtract(side)};
                for (int v = 0; v < 4; v++) {
                    points[count * 12 + v * 3] = (float) quad[v].getX();
                    points[count * 12 + v * 3 + 1] = (float) quad[v].getY();
                    points[count * 12 + v * 3 + 2] = (float) quad[v].getZ();
                }
                int[] corners = {0, 1, 2, 2, 1, 3};
                for (int c = 0; c < 6; c++) faces[count * 12 + c * 2] = count * 4 + corners[c];
                count++;
            }
            var mesh = (TriangleMesh) edgeViews.get(id).getMesh();
            mesh.getPoints().setAll(java.util.Arrays.copyOf(points, count * 12));
            mesh.getFaces().setAll(java.util.Arrays.copyOf(faces, count * 12));
        });
    }

    public void select(String id) {
        selected = id;
        bodyViews.forEach((key, view) -> {
            var base = Color.web(bodyStyles
                    .getOrDefault(key, dev.voxelbrew.api.model.BodyStyle.DEFAULT)
                    .color());
            var material = new PhongMaterial(key.equals(id) ? base.interpolate(Color.web("#edae49"), 0.3) : base);
            material.setSpecularColor(key.equals(id) ? Color.web("#fff1ba") : Color.WHITE);
            material.setSpecularPower(key.equals(id) ? 64 : 32);
            if (rendering == Rendering.Style.TECHNICAL) {
                material = new PhongMaterial(key.equals(id) ? Color.web("#fff7e6") : Color.WHITE);
                material.setSpecularColor(Color.BLACK);
            }
            view.setMaterial(material);
        });
        edgeViews.forEach((key, view) ->
                view.setMaterial(new PhongMaterial(key.equals(id) ? Color.web("#a86600") : Color.web("#202124"))));
    }

    public void setBodyStyles(Map<String, dev.voxelbrew.api.model.BodyStyle> styles) {
        if (bodyStyles.equals(styles)) return;
        bodyStyles = Map.copyOf(styles);
        select(selected);
    }

    public void fit(Map<String, MeshData> meshes) {
        if (beforeProfileEdit != null
                && shownSketches.size() == 1
                && shownSketches.getFirst() instanceof Feature.Sketch sketch) {
            frameProfile(sketch);
            return;
        }
        if (meshes.isEmpty()) {
            center.setX(-30);
            center.setY(0);
            center.setZ(-20);
            distance = 180;
        } else {
            double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
                    max = {-1e99, -1e99, -1e99};
            for (var mesh : meshes.values()) {
                for (int v = 0; v < mesh.vertexCount(); v++) {
                    for (int a = 0; a < 3; a++) {
                        double x = mesh.coordinate(v, a);
                        min[a] = Math.min(min[a], x);
                        max[a] = Math.max(max[a], x);
                    }
                }
            }
            var p = toView((min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2);
            center.setX(-p.getX());
            center.setY(-p.getY());
            center.setZ(-p.getZ());
            distance = Math.max(20, Math.max(Math.max(max[0] - min[0], max[1] - min[1]), max[2] - min[2]) * 3.2);
        }
        pan.setX(0);
        pan.setY(0);
        camera.setTranslateZ(-distance);
    }

    public void standardView(String name) {
        setOrientation(ViewOrientation.named(name));
    }

    ReadOnlyObjectProperty<ViewOrientation> orientationProperty() {
        return orientation.getReadOnlyProperty();
    }

    private void setOrientation(ViewOrientation value) {
        tilt.setAngle(value.tilt());
        orbit.setAngle(value.orbit());
        viewCube.setOrientation(value);
        orientation.set(value);
    }

    private void orbitBy(double dx, double dy) {
        setOrientation(orientation.get().dragged(dx, dy));
    }

    private void home() {
        setOrientation(ViewOrientation.HOME);
        fit(currentMeshes);
    }

    private void grid() {
        groundGrid.setMaterial(new PhongMaterial(Color.web("#8795a3")));
        groundGrid.setCullFace(CullFace.NONE);
        groundGrid.setManaged(false);
        aids.getChildren().add(groundGrid);
        aids.getChildren().addAll(axes);
        aids.setMouseTransparent(true);
    }

    private void updateGrid() {
        if (!aids.isVisible() || scene.getHeight() <= 0 || scene.getWidth() <= 0) return;
        double slope = Math.tan(Math.toRadians(camera.getFieldOfView() / 2));
        double slopeX = slope * scene.getWidth() / scene.getHeight();
        var occupied = new java.util.ArrayList<javafx.geometry.Bounds>();
        occupied.add(viewCube.getBoundsInParent());
        for (var axis : axes) {
            var ends = axis.update(
                    world.getLocalToParentTransform(),
                    distance,
                    slopeX,
                    slope,
                    scene.getHeight(),
                    camera.getNearClip(),
                    camera.getFarClip());
            var label = axisLabels.get(axis);
            label.setVisible(false);
            var size = label.getLayoutBounds();
            if (scene.getWidth() < size.getWidth() + 12 || scene.getHeight() < size.getHeight() + 12) continue;
            for (var end : ends) {
                double x = Math.clamp(end.getX() + 4, 6, scene.getWidth() - size.getWidth() - 6);
                double y = Math.clamp(end.getY() - size.getHeight() - 4, 6, scene.getHeight() - size.getHeight() - 6);
                label.relocate(x, y);
                var bounds = label.getBoundsInParent();
                if (occupied.stream().anyMatch(bounds::intersects)) continue;
                occupied.add(bounds);
                label.setVisible(true);
                break;
            }
        }
        groundGrid.setMesh(GroundGrid.mesh(
                world::parentToLocal,
                distance,
                slopeX,
                slope,
                scene.getHeight(),
                camera.getNearClip(),
                camera.getFarClip()));
    }

    private void installPanHandlers() {
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.SECONDARY) return;
            lastX = e.getSceneX();
            lastY = e.getSceneY();
            primaryOrbit = false;
            pressedSketch = null;
            requestFocus();
            scene.setCursor(Cursor.CLOSED_HAND);
            e.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!e.isSecondaryButtonDown()) return;
            pan.setX(pan.getX() + (e.getSceneX() - lastX) * distance / 700);
            pan.setY(pan.getY() + (e.getSceneY() - lastY) * distance / 700);
            lastX = e.getSceneX();
            lastY = e.getSceneY();
            e.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (e.getButton() != MouseButton.SECONDARY) return;
            scene.setCursor(Cursor.DEFAULT);
            e.consume();
        });
    }

    private void beginOrbit(MouseEvent e) {
        lastX = e.getSceneX();
        lastY = e.getSceneY();
        boolean background = isNavigationBackground(e.getPickResult().getIntersectedNode());
        pressedSketch = e.getButton() == MouseButton.PRIMARY && planeClick == null && background
                ? nearbySketch(e.getScreenX(), e.getScreenY())
                : null;
        primaryOrbit =
                e.getButton() == MouseButton.PRIMARY && planeClick == null && background && pressedSketch == null;
        requestFocus();
    }

    private void showProfile(Feature.Sketch sketch, String selectedSketch, boolean showOutline) {
        double[] xy;
        if (sketch.profile() instanceof Feature.Rectangle r) {
            xy = new double[] {
                r.x(), r.y(), r.x() + r.width(), r.y(), r.x() + r.width(), r.y() + r.height(), r.x(), r.y() + r.height()
            };
        } else {
            var c = (Feature.Circle) sketch.profile();
            xy = new double[128];
            for (int n = 0; n < 64; n++) {
                xy[2 * n] = c.centerX() + c.diameter() / 2 * Math.cos(2 * Math.PI * n / 64);
                xy[2 * n + 1] = c.centerY() + c.diameter() / 2 * Math.sin(2 * Math.PI * n / 64);
            }
        }
        int count = xy.length / 2;
        float[] points = new float[count * 3];
        for (int i = 0; i < count; i++) {
            var p = toView(xy[2 * i], xy[2 * i + 1], sketch.plane().offsetZ() + 0.035);
            points[3 * i] = (float) p.getX();
            points[3 * i + 1] = (float) p.getY();
            points[3 * i + 2] = (float) p.getZ();
        }
        var mesh = new TriangleMesh();
        mesh.getPoints().setAll(points);
        mesh.getTexCoords().setAll(0, 0);
        int[] faces = new int[(count - 2) * 6];
        for (int i = 0; i < count - 2; i++) {
            faces[6 * i] = 0;
            faces[6 * i + 2] = i + 1;
            faces[6 * i + 4] = i + 2;
        }
        mesh.getFaces().setAll(faces);
        var profile = new MeshView(mesh);
        profile.setCullFace(CullFace.NONE);
        boolean chosen = sketch.id().equals(selectedSketch);
        profile.setMaterial(new PhongMaterial(Color.web(chosen ? "#eead48" : "#388eae", chosen ? 0.52 : 0.18)));
        profile.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
                selection.accept(sketch.id(), -1);
                e.consume();
            }
        });
        var group = new Group(profile);
        group.getTransforms().add(viewTransform(sketch.plane().frame()));
        sketches.getChildren().add(group);
        if (showOutline) {
            var outline = new Path();
            outline.getElements().add(new MoveTo(xy[0], xy[1]));
            for (int i = 1; i < count; i++) {
                outline.getElements().add(new LineTo(xy[2 * i], xy[2 * i + 1]));
            }
            outline.getElements().add(new ClosePath());
            group.getChildren().add(profileOutline(outline, sketch.plane(), "#2d839f"));
            return;
        }
        for (int i = 0; i < count; i++) {
            var line = line(
                    toView(xy[2 * i], xy[2 * i + 1], sketch.plane().offsetZ() + 0.04),
                    toView(
                            xy[2 * ((i + 1) % count)],
                            xy[2 * ((i + 1) % count) + 1],
                            sketch.plane().offsetZ() + 0.04),
                    chosen ? 0.14 : 0.065,
                    chosen ? Color.web("#dc8d18") : Color.web("#2d839f"));
            line.setMouseTransparent(true);
            group.getChildren().add(line);
        }
    }
}
