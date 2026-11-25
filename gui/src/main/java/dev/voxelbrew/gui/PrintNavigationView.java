package dev.voxelbrew.gui;

import dev.voxelbrew.api.model.BodyInstance;
import dev.voxelbrew.api.model.mesh.MeshData;
import dev.voxelbrew.api.model.printing.ManufacturingModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javafx.geometry.Point2D;
import javafx.scene.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.*;
import javafx.scene.transform.Affine;

/** Retains the placed meshes on the orthographic print bed during placement and navigation. */
final class PrintNavigationView extends SubScene {
    private final Group bodies;
    private final Affine projection = new Affine();
    private final List<PrintBoundaryMesh> outlines = new ArrayList<>();
    private ViewOrientation edgeOrientation;
    private double edgeScale;
    private ManufacturingModel revision;
    private String selection;
    private MeshView selectedBody;
    private boolean dark;
    private double extent = 1;

    private static double placedBoundsExtent(MeshData mesh) {
        double result = 0;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            result = Math.max(
                    result,
                    Math.abs(mesh.coordinate(i, 0))
                            + Math.abs(mesh.coordinate(i, 1))
                            + Math.abs(mesh.coordinate(i, 2)));
        }
        return result;
    }

    private static MeshView meshView(MeshData data, Color color) {
        float[] points = new float[data.vertexCount() * 3];
        for (int v = 0; v < data.vertexCount(); v++) {
            for (int c = 0; c < 3; c++) points[3 * v + c] = (float) data.coordinate(v, c);
        }
        int[] faces = new int[data.triangleCount() * 6];
        for (int t = 0; t < data.triangleCount(); t++) {
            for (int c = 0; c < 3; c++) faces[6 * t + 2 * c] = data.vertexIndex(t, c);
        }
        var mesh = new TriangleMesh();
        mesh.getPoints().setAll(points);
        mesh.getTexCoords().setAll(0, 0);
        mesh.getFaces().setAll(faces);
        var view = new MeshView(mesh);
        view.setCullFace(CullFace.NONE);
        view.setMaterial(new PhongMaterial(color));
        return view;
    }

    PrintNavigationView() {
        super(new Group(), 1, 1, true, SceneAntialiasing.BALANCED);
        bodies = new Group();
        bodies.getTransforms().add(projection);
        // Match the flat placement colors at rest, independent of camera angle and distance.
        ((Group) getRoot()).getChildren().addAll(bodies, new AmbientLight(Color.WHITE));
        setCamera(new ParallelCamera());
        setFill(Color.TRANSPARENT);
        setMouseTransparent(true);
        setManaged(false);
        setVisible(false);
    }

    void hide(ManufacturingModel current) {
        setVisible(false);
        if (revision != current) {
            bodies.getChildren().clear();
            outlines.clear();
            selectedBody = null;
            revision = null;
        }
    }

    void show(
            ManufacturingModel model,
            DocumentController.Build build,
            String selected,
            PrintCamera camera,
            double ox,
            double oy,
            double scale,
            Point2D selectionOffset) {
        prepare(model, build, selected);
        if (scale != edgeScale || !camera.orientation().equals(edgeOrientation)) {
            for (var outline : outlines) outline.update(camera, scale);
            edgeScale = scale;
            edgeOrientation = camera.orientation();
        }
        double dx = selectionOffset.getX(), dy = selectionOffset.getY();
        if (selectedBody != null) {
            selectedBody.setTranslateX(dx);
            selectedBody.setTranslateY(dy);
        }
        // Include transient translations in the depth range so a part can be dragged off the bed.
        double depthExtent = extent + Math.abs(dx) + Math.abs(dy);
        camera.projectInto(projection, ox, oy, scale, Math.min(scale, getHeight() * 0.5 / depthExtent));
        setVisible(true);
    }

    void setDark(boolean value) {
        if (dark != value) {
            dark = value;
            hide(null);
        }
    }

    private void addOutline(MeshView surface, PrintBoundaryMesh outline) {
        outline.translateXProperty().bind(surface.translateXProperty());
        outline.translateYProperty().bind(surface.translateYProperty());
        bodies.getChildren().add(outline);
        outlines.add(outline);
    }

    private void prepare(ManufacturingModel model, DocumentController.Build build, String selected) {
        if (revision != model || !Objects.equals(selected, selection)) {
            bodies.getChildren().clear();
            outlines.clear();
            edgeOrientation = null;
            selectedBody = null;
            int palette = 0;
            extent = 1;
            // Coplanar outlines share depth samples. Keep their draw order stable across list edits.
            var ordered = model.instances().stream()
                    .sorted(Comparator.comparing(BodyInstance::id))
                    .toList();
            for (var instance : ordered) {
                if (!model.meshes().containsKey(instance.bodyId())) continue;
                var placed = model.placedMesh(instance);
                var color = instance.id().equals(selected)
                        ? Color.web("#4299c7")
                        : Color.hsb(195 + (palette++ * 48) % 160, 0.35, 0.72);
                var view = meshView(placed, color);
                bodies.getChildren().add(view);
                if (instance.id().equals(selected)) {
                    selectedBody = view;
                    addOutline(
                            view,
                            PrintBoundaryMesh.footprint(
                                    dev.voxelbrew.api.model.printing.MeshGeometry.bounds(placed),
                                    Color.web(dark ? "#9bdcff" : "#075b91")));
                }
                build.bodies().stream()
                        .filter(body -> body.id().equals(instance.bodyId()))
                        .findFirst()
                        .ifPresent(body -> addOutline(view, new PrintBoundaryMesh(body.edges(), instance.transform())));
                extent = Math.max(extent, placedBoundsExtent(placed));
            }
            revision = model;
            selection = selected;
        }
    }
}
