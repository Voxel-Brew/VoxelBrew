package dev.voxelbrew.slicer;

import dev.voxelbrew.api.model.printing.*;
import dev.voxelbrew.api.model.vui.*;
import dev.voxelbrew.api.slicer.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

final class SlicerTestSupport {
    static SlicerWindow window(Map<String, String> overrides) {
        return new SlicerWindow() {
            @Override
            public <T> Optional<T> show(SlicerDialog<T> dialog) {
                var values = new LinkedHashMap<String, String>();
                if (dialog.layout() instanceof VerticalLayout column) {
                    for (Component component : column.children())
                        if (component instanceof TextField field) values.put(field.id(), field.value());
                }
                values.putAll(overrides);
                var errors = dialog.validate(values);
                if (!errors.isEmpty()) throw new IllegalArgumentException(errors.toString());
                return Optional.of(dialog.buildSettings(values));
            }
        };
    }

    static SliceService.Result slice(
            SlicerProvider provider,
            ManufacturingModel model,
            Map<String, String> options,
            Path output,
            boolean overwrite,
            Cancellation cancellation,
            Consumer<String> diagnostics)
            throws Exception {
        var result = SliceService.slice(provider, model, window(options), cancellation, diagnostics)
                .orElseThrow();
        cancellation.publish(() -> SliceService.save(result.artifact(), output, overwrite));
        return result;
    }

    private SlicerTestSupport() {}
}
