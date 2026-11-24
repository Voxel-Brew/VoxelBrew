package dev.voxelbrew.engine.geometry;

import static dev.voxelbrew.api.model.sketch.SketchGeometry.*;
import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.sketch.*;
import dev.voxelbrew.engine.sketch.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SharedEdgeProfileTest {
    private static List<Entity> house() {
        return List.of(
                Sketches.entity("bottom", Kind.LINE, false, 0, 0, 20, 0),
                Sketches.entity("right", Kind.LINE, false, 20, 0, 20, 10),
                Sketches.entity("divider", Kind.LINE, false, 20, 10, 0, 10),
                Sketches.entity("left", Kind.LINE, false, 0, 10, 0, 0),
                Sketches.entity("roof-left", Kind.LINE, false, 0, 10, 10, 20),
                Sketches.entity("roof-right", Kind.LINE, false, 10, 20, 20, 10));
    }

    private static SketchGeometry geometry(List<Entity> entities) {
        return new SketchGeometry(
                entities, List.of(), entities.stream().map(Entity::id).toList());
    }

    @Test
    void individualFacesKeepSharedEdgeAndStableReferences() throws Exception {
        var original = SketchProfiles.selectableRegions(geometry(house()));
        assertThat(original.size()).isEqualTo(2);
        for (int seed = 0; seed < 12; seed++) {
            var entities = new ArrayList<>(house());
            Collections.shuffle(entities, new Random(seed));
            if (seed % 2 == 0) {
                entities.replaceAll(
                        e -> Sketches.entity(e.id(), Kind.LINE, false, e.value(2), e.value(3), e.value(0), e.value(1)));
            }
            var sketch = new dev.voxelbrew.api.model.Feature.Sketch(
                    "sketch",
                    "profile",
                    new dev.voxelbrew.api.model.Feature.Plane("origin-xy", 0),
                    new dev.voxelbrew.api.model.Feature.Constrained(geometry(entities)));
            for (int i = 0; i < original.size(); i++) {
                var reference = new dev.voxelbrew.api.model.Feature.ProfileRef(
                        "sketch", SketchProfiles.regionProfileId("profile", original.get(i)));
                var session = new dev.voxelbrew.engine.model.ModelSession(dev.voxelbrew.api.model.CadDocument.empty());
                session.add(sketch);
                session.add(new dev.voxelbrew.api.model.Feature.Extrude("extrude", reference, 5));
                var reopened = dev.voxelbrew.engine.persistence.DocumentCodec.decode(
                        dev.voxelbrew.engine.persistence.DocumentCodec.encode(session.document()));
                try (var evaluation = new dev.voxelbrew.engine.model.ModelSession(reopened).evaluate()) {
                    evaluation.requireCompleteDocument();
                    assertThat(evaluation.bodies().size()).isEqualTo(1);
                    var solid = evaluation.bodies().getFirst().solid();
                    assertThat(solid.volume())
                            .isCloseTo(i == 0 ? 1000 : 500, org.assertj.core.data.Offset.offset(1e-6));
                    assertThat(solid.contains(10, 5, 2)).isEqualTo(i == 0);
                    assertThat(solid.contains(10, 15, 2)).isEqualTo(i != 0);
                }
            }
        }
    }

    @Test
    void selectedFacePreservesHoleAndMissingRegionFails() {
        var entities = new ArrayList<>(house());
        entities.add(Sketches.entity("hole", Kind.CIRCLE, false, 10, 5, 2));
        var g = geometry(entities);
        var regions = SketchProfiles.selectableRegions(g);
        assertThat(regions).hasSize(3);
        try (var solid = Solid.profilePrism(regions.getFirst().profile(), 0, 5)) {
            assertThat(solid.volume()).isCloseTo(1000 - 20 * Math.PI, org.assertj.core.data.Offset.offset(1e-6));
            assertThat(solid.contains(10, 5, 2)).isFalse();
            assertThat(solid.contains(10, 15, 2)).isFalse();
        }
        var sketch = new dev.voxelbrew.api.model.Feature.Sketch(
                "s",
                "p",
                new dev.voxelbrew.api.model.Feature.Plane("origin-xy", 0),
                new dev.voxelbrew.api.model.Feature.Constrained(g));
        assertThatThrownBy(() -> SketchProfiles.referencedRegions(sketch, "p/region/missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sharedEdgeExtrudesAsOneSolidRegardlessOfOrderAndDirection() {
        for (int seed = 0; seed < 12; seed++) {
            var entities = new ArrayList<>(house());
            Collections.shuffle(entities, new Random(seed));
            if (seed % 2 == 0) {
                entities.replaceAll(
                        e -> Sketches.entity(e.id(), Kind.LINE, false, e.value(2), e.value(3), e.value(0), e.value(1)));
            }
            var g = geometry(entities);
            assertThat(SketchProfiles.hasProfile(g)).isTrue();
            assertThat(SketchProfiles.regions(g).size()).isEqualTo(1);
            try (var solid = Solid.profilePrism(SketchProfiles.profile(g), 0, 5)) {
                assertThat(solid.volume()).isCloseTo(1500, org.assertj.core.data.Offset.offset(1e-6));
                assertThat(solid.contains(10, 5, 2)).isTrue();
                assertThat(solid.contains(10, 15, 2)).isTrue();
                assertThat(solid.contains(10, 10, 2)).isTrue();
            }
        }
    }

    @Test
    void sharedBoundaryPreservesHolesAndDisconnectedRegions() {
        var entities = new ArrayList<>(house());
        entities.add(Sketches.entity("hole", Kind.CIRCLE, false, 10, 5, 2));
        entities.add(Sketches.entity("separate", Kind.CIRCLE, false, 40, 5, 2));
        var regions = SketchProfiles.regions(geometry(entities));
        assertThat(regions.size()).isEqualTo(2);
        assertThat(regions.getFirst().id()).isEqualTo("bottom");
        double volume = 0;
        for (var region : regions) {
            try (var solid = Solid.profilePrism(region.profile(), 0, 5)) {
                volume += solid.volume();
                assertThat(solid.contains(10, 5, 2)).isFalse();
            }
        }
        assertThat(volume).isCloseTo(1500, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void lineBetweenHoleCentersLeavesBothHolesInTheExtrusion() {
        var entities = new ArrayList<>(house().subList(0, 4));
        entities.add(Sketches.entity("first", Kind.CIRCLE, false, 5, 5, 2));
        entities.add(Sketches.entity("second", Kind.CIRCLE, false, 15, 5, 2));
        entities.add(Sketches.entity("spacing", Kind.LINE, false, 5, 5, 15, 5));
        var sketch = geometry(entities);
        var faces = SketchProfiles.selectableRegions(sketch);
        assertThat(faces).hasSize(3);
        var feature = new dev.voxelbrew.api.model.Feature.Sketch(
                "s",
                "p",
                new dev.voxelbrew.api.model.Feature.Plane("origin-xy", 0),
                new dev.voxelbrew.api.model.Feature.Constrained(sketch));
        for (int mask = 0; mask < 4; mask++) {
            var ids = new ArrayList<>(List.of(faces.getFirst().id()));
            boolean left = (mask & 1) != 0;
            boolean right = (mask & 2) != 0;
            if (left) ids.add(faces.get(1).id());
            if (right) ids.add(faces.get(2).id());
            var selected = SketchProfiles.referencedRegions(feature, SketchProfiles.selectedProfileId("p", ids));
            assertThat(selected).hasSize(1);
            try (var solid = Solid.profilePrism(selected.getFirst().profile(), 0, 5)) {
                assertThat(solid.contains(5, 5, 2)).isEqualTo(left);
                assertThat(solid.contains(15, 5, 2)).isEqualTo(right);
                assertThat(solid.volume()).isCloseTo(1000 - (2 - Integer.bitCount(mask)) * 20 * Math.PI, within(1e-6));
            }
        }
        try (var solid = Solid.profilePrism(SketchProfiles.profile(sketch), 0, 5)) {
            assertThat(solid.volume()).isCloseTo(1000 - 40 * Math.PI, within(1e-6));
            assertThat(solid.contains(5, 5, 2)).isFalse();
            assertThat(solid.contains(15, 5, 2)).isFalse();
        }
    }

    @Test
    void dividerMeetingUnsplitWallsProducesTwoExtrudableFaces() {
        var entities = new ArrayList<>(house().subList(0, 4));
        entities.add(Sketches.entity("partition", Kind.LINE, false, 0, 3, 20, 3));
        var g = geometry(entities);
        var regions = SketchProfiles.selectableRegions(g);
        assertThat(regions).hasSize(2);
        var volumes = new ArrayList<Double>();
        for (var region : regions) {
            try (var solid = Solid.profilePrism(region.profile(), 0, 5)) {
                volumes.add(solid.volume());
            }
        }
        assertThat(volumes)
                .usingElementComparator(new org.assertj.core.util.DoubleComparator(1e-6))
                .containsExactlyInAnyOrder(300., 700.);
        try (var solid = Solid.profilePrism(SketchProfiles.profile(g), 0, 5)) {
            assertThat(solid.volume()).isCloseTo(1000, within(1e-6));
        }
    }

    @Test
    void danglingEdgeStillRejectsExtrusion() {
        var entities = new ArrayList<>(house());
        entities.add(Sketches.entity("open", Kind.LINE, false, 10, 20, 10, 25));
        assertThat(SketchProfiles.hasProfile(geometry(entities))).isFalse();
    }
}
