package dev.voxelbrew.engine.printing;

import dev.voxelbrew.api.model.*;
import dev.voxelbrew.api.model.printing.*;
import java.util.*;

/** One-based slicer tool assignments and the material snapshot stored with the finished job. */
public record PlateFilaments(List<BodyStyle> slots, Map<String, Integer> bodies) {
    private static final String PREFIX = "filament.";

    public static PlateFilaments from(CadDocument document, ManufacturingModel model) {
        var byBody = new TreeMap<String, BodyStyle>();
        for (var instance : model.instances()) {
            var style = document.bodyStyle(instance.bodyId());
            String type =
                    style.material().isBlank() ? model.profile().material().id() : style.material();
            byBody.put(instance.bodyId(), new BodyStyle(type, style.color()));
        }
        var slots = byBody.values().stream().distinct().toList();
        var bodies = new TreeMap<String, Integer>();
        byBody.forEach((id, style) -> bodies.put(id, slots.indexOf(style) + 1));
        return new PlateFilaments(slots, bodies);
    }

    public static PlateFilaments read(Map<String, String> metadata) {
        var slots = new ArrayList<BodyStyle>();
        while (metadata.containsKey(PREFIX + (slots.size() + 1) + ".color")) {
            String key = PREFIX + (slots.size() + 1);
            slots.add(new BodyStyle(metadata.getOrDefault(key + ".type", ""), metadata.get(key + ".color")));
        }
        var bodies = new HashMap<String, Integer>();
        metadata.forEach((key, value) -> {
            if (key.startsWith("filament.body.")) {
                bodies.put(key.substring("filament.body.".length()), Integer.valueOf(value));
            }
        });
        return new PlateFilaments(slots, bodies);
    }

    public PlateFilaments {
        slots = List.copyOf(slots);
        bodies = Map.copyOf(bodies);
    }

    public Map<String, String> metadata() {
        var result = new TreeMap<String, String>();
        for (int i = 0; i < slots.size(); i++) {
            String key = PREFIX + (i + 1);
            result.put(key + ".type", slots.get(i).material());
            result.put(key + ".color", slots.get(i).color());
        }
        bodies.forEach((id, slot) -> result.put("filament.body." + id, slot.toString()));
        return result;
    }
}
