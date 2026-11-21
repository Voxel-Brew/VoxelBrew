package dev.voxelbrew.gui;

import dev.voxelbrew.api.i18n.TranslationService;
import java.util.function.Function;
import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;

/**
 * Display adapters translate labels without changing model identifiers, enum values or commands.
 */
final class UiText {
    static <T> void choices(ComboBox<T> control, Function<T, String> display) {
        control.setConverter(new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : display.apply(value);
            }

            @Override
            public T fromString(String value) {
                throw new UnsupportedOperationException("Read-only choices");
            }
        });
    }

    static <E extends Enum<E>> void enums(ComboBox<E> control, TranslationService translations, String prefix) {
        choices(control, value -> translations.translate(prefix + value.name()));
    }

    static String view(TranslationService translations, String name) {
        return java.util.Arrays.stream(name.split(" "))
                .map(part -> translations.tryTranslate("view." + part).orElse(part))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private UiText() {}
}
