package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.Kind.LINE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.voxelbrew.api.model.sketch.SketchGeometry;
import dev.voxelbrew.api.model.sketch.SketchGeometry.Entity;
import dev.voxelbrew.api.model.sketch.SketchGeometry.Vec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SketchProfilesTest {
    private static List<Entity> rectangle(String id, double left, double bottom, double right, double top) {
        return List.of(
                Sketches.entity(id + "-bottom", LINE, false, left, bottom, right, bottom),
                Sketches.entity(id + "-right", LINE, false, right, bottom, right, top),
                Sketches.entity(id + "-top", LINE, false, right, top, left, top),
                Sketches.entity(id + "-left", LINE, false, left, top, left, bottom));
    }

    private static SketchGeometry geometry(List<Entity> entities) {
        return new SketchGeometry(
                entities, List.of(), entities.stream().map(Entity::id).toList());
    }

    @Test
    void overlappingWallsBoundSelectableRegionsAndOneWholeProfile() {
        var entities = new ArrayList<>(rectangle("lower", 2, 0, 8, 4));
        entities.addAll(rectangle("upper", 0, 4, 10, 8));
        var sketch = geometry(entities);

        assertThat(SketchProfiles.extrusionIssue(sketch)).isEmpty();
        assertThat(SketchProfiles.regions(sketch)).hasSize(1);
        var regions = SketchProfiles.selectableRegions(sketch);
        assertThat(regions).hasSize(2);
        var lower = SketchProfiles.regionAt(sketch, new Vec(3, 1));
        assertThat(lower).isEqualTo(SketchProfiles.regionAt(geometry(rectangle("lower", 2, 0, 8, 4)), new Vec(3, 1)));
        assertThat(SketchProfiles.regionAt(sketch, new Vec(3, 6))).isPresent().isNotEqualTo(lower);
        assertThat(SketchProfiles.selectableRegions(geometry(entities.reversed())))
                .extracting(SketchProfiles.Region::id)
                .containsExactlyInAnyOrderElementsOf(
                        regions.stream().map(SketchProfiles.Region::id).toList());
    }

    @Test
    void aLineLinkingTwoClosedContoursStillReportsAnOpenConnection() {
        var entities = new ArrayList<>(rectangle("left", 0, 0, 4, 4));
        entities.addAll(rectangle("right", 6, 0, 10, 4));
        entities.add(Sketches.entity("connector", LINE, false, 4, 0, 6, 0));

        assertThat(SketchProfiles.extrusionIssue(geometry(entities)))
                .isEqualTo("Profile contains an open connecting edge");
    }
}
