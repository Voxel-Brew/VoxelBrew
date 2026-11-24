package dev.voxelbrew.engine.persistence;

import dev.voxelbrew.api.model.sketch.SketchGeometry;
import dev.voxelbrew.api.model.sketch.SketchText;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.engine.sketch.SketchConstraints;

/** Protobuf remains an implementation detail; enum names are the compatibility contract. */
@lombok.experimental.UtilityClass
class SketchCodec {

    static SketchGeometry fromProto(DocumentProto.ConstrainedSketch message) {
        return new SketchGeometry(
                message.getEntitiesList().stream().map(SketchCodec::entity).toList(),
                message.getConstraintsList().stream()
                        .map(c -> {
                            var constraint = new SketchGeometry.Constraint(
                                    c.getId(),
                                    c.getType() == DocumentProto.SketchConstraint.Type.PATTERN_LINK
                                            ? SketchGeometry.Type.FIX
                                            : SketchGeometry.Type.valueOf(
                                                    c.getType().name()),
                                    c.getRefsList().stream()
                                            .map(r -> new SketchGeometry.Ref(
                                                    r.getEntityId(),
                                                    SketchGeometry.Part.valueOf(
                                                            r.getPart().name()),
                                                    r.getIndex()))
                                            .toList(),
                                    c.getValuesList());
                            return c.hasLabelPosition()
                                    ? SketchConstraints.positionLabel(
                                            constraint,
                                            new SketchGeometry.Vec(
                                                    c.getLabelPosition().getX(),
                                                    c.getLabelPosition().getY()))
                                    : constraint;
                        })
                        .toList(),
                message.getBoundaryList());
    }

    private static SketchGeometry.Entity entity(DocumentProto.SketchEntity e) {
        var t = e.getText();
        var data = e.hasText()
                ? new SketchText.Data(
                        t.getContent(),
                        t.getFont(),
                        t.getStyle(),
                        t.getPathId(),
                        t.getGlyphsList().stream()
                                .map(g -> new SketchText.Glyph(
                                        g.getAdvance(),
                                        g.getContoursList().stream()
                                                .map(c -> c.getCurvesList().stream()
                                                        .map(SketchCodec::entity)
                                                        .toList())
                                                .toList()))
                                .toList(),
                        SketchText.Horizontal.valueOf(t.getHorizontal().name()),
                        SketchText.Vertical.valueOf(t.getVertical().name()))
                : null;
        return new SketchGeometry.Entity(
                e.getId(),
                SketchGeometry.Kind.valueOf(e.getKind().name()),
                e.getValuesList(),
                e.getConstruction(),
                e.getDegree(),
                e.getClosed(),
                data);
    }

    private static DocumentProto.SketchEntity entity(SketchGeometry.Entity e) {
        var out = DocumentProto.SketchEntity.newBuilder()
                .setId(e.id())
                .setKind(DocumentProto.SketchEntity.Kind.valueOf(e.kind().name()))
                .addAllValues(e.values())
                .setConstruction(e.construction())
                .setDegree(e.degree())
                .setClosed(e.closed());
        if (e.text() != null) {
            var t = e.text();
            var text = DocumentProto.SketchText.newBuilder()
                    .setContent(t.content())
                    .setFont(t.font())
                    .setStyle(t.style())
                    .setPathId(t.pathId())
                    .setHorizontal(DocumentProto.SketchText.Horizontal.valueOf(
                            t.horizontal().name()))
                    .setVertical(DocumentProto.SketchText.Vertical.valueOf(
                            t.vertical().name()));
            for (var g : t.glyphs()) {
                var glyph = DocumentProto.SketchGlyph.newBuilder().setAdvance(g.advance());
                for (var c : g.contours()) {
                    glyph.addContours(DocumentProto.SketchContour.newBuilder()
                            .addAllCurves(c.stream().map(SketchCodec::entity).toList()));
                }
                text.addGlyphs(glyph);
            }
            out.setText(text);
        }
        return out.build();
    }

    static DocumentProto.ConstrainedSketch toProto(SketchGeometry geometry) {
        var out = DocumentProto.ConstrainedSketch.newBuilder().addAllBoundary(geometry.boundary());
        out.addAllEntities(geometry.entities().stream().map(SketchCodec::entity).toList());
        for (var c : geometry.constraints()) {
            var constraint = DocumentProto.SketchConstraint.newBuilder()
                    .setId(c.id())
                    .setType(
                            dev.voxelbrew.engine.sketch.SketchCopies.linked(c)
                                    ? DocumentProto.SketchConstraint.Type.PATTERN_LINK
                                    : DocumentProto.SketchConstraint.Type.valueOf(
                                            c.type().name()));
            var position = SketchConstraints.labelPosition(c);
            if (position.isPresent()) {
                constraint
                        .addValues(c.values().getFirst())
                        .setLabelPosition(DocumentProto.SketchLabelPosition.newBuilder()
                                .setX(position.get().x())
                                .setY(position.get().y()));
            } else {
                constraint.addAllValues(c.values());
            }
            for (var r : c.refs()) {
                constraint.addRefs(DocumentProto.SketchReference.newBuilder()
                        .setEntityId(r.entityId())
                        .setPart(DocumentProto.SketchReference.Part.valueOf(
                                r.part().name()))
                        .setIndex(r.index()));
            }
            out.addConstraints(constraint);
        }
        return out.build();
    }
}
