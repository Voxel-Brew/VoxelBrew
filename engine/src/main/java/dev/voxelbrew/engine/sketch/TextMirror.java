package dev.voxelbrew.engine.sketch;

import dev.voxelbrew.api.model.sketch.SketchGeometry.Entity;
import dev.voxelbrew.api.model.sketch.SketchText;
import dev.voxelbrew.api.model.sketch.SketchText.Glyph;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reflections in the text's local frame, before placement along a sketch path. */
public record TextMirror(boolean horizontal, boolean vertical) {
    private static final String HORIZONTAL = "mirrorX-";
    private static final String VERTICAL = "mirrorY-";

    public static TextMirror of(Entity text) {
        // Glyph outline IDs travel with the persisted outlines, including in older model APIs.
        String id = text.text().glyphs().stream()
                .flatMap(g -> g.contours().stream())
                .flatMap(List::stream)
                .findFirst()
                .map(Entity::id)
                .orElse("");
        return new TextMirror(
                id.startsWith(HORIZONTAL), id.startsWith(VERTICAL) || id.startsWith(HORIZONTAL + VERTICAL));
    }

    /** Apply to freshly generated font outlines. The source text remains editable in reading order. */
    public Entity apply(Entity text) {
        if (!horizontal && !vertical) return text;
        var data = text.text();
        var outlines = new ArrayList<Glyph>();
        var line = new ArrayList<Glyph>();
        int row = 0;
        for (var glyph : data.glyphs()) {
            if (glyph.advance() < 0) {
                appendLine(outlines, line);
                outlines.add(glyph);
                row++;
            } else {
                outlinesForGlyph(line, glyph, row);
            }
        }
        appendLine(outlines, line);
        var mirrored = new SketchText.Data(
                data.content(), data.font(), data.style(), data.pathId(), outlines, data.horizontal(), data.vertical());
        return new Entity(
                text.id(), text.kind(), text.values(), text.construction(), text.degree(), text.closed(), mirrored);
    }

    private void appendLine(List<Glyph> outlines, List<Glyph> line) {
        if (horizontal) Collections.reverse(line);
        outlines.addAll(line);
        line.clear();
    }

    private void outlinesForGlyph(List<Glyph> line, Glyph glyph, int row) {
        line.add(new Glyph(
                glyph.advance(),
                glyph.contours().stream()
                        .map(contour -> contour.stream()
                                .map(curve -> reflect(curve, glyph.advance(), row))
                                .toList())
                        .toList()));
    }

    private Entity reflect(Entity curve, double advance, int row) {
        var values = new ArrayList<>(curve.values());
        for (int i = 0; i < values.size(); i += 2) {
            if (horizontal) values.set(i, advance - curve.value(i));
            // SketchText lays out each subsequent baseline 1200 font units below its predecessor.
            if (vertical) values.set(i + 1, 2400.0 * row - curve.value(i + 1));
        }
        String prefix = (horizontal ? HORIZONTAL : "") + (vertical ? VERTICAL : "");
        return new Entity(prefix + curve.id(), curve.kind(), values, curve.construction());
    }
}
