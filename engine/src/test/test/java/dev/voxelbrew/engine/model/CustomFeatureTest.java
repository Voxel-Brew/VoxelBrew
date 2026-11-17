package dev.voxelbrew.engine.model;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.model.CadDocument;
import dev.voxelbrew.api.model.Feature;
import dev.voxelbrew.engine.feature.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomFeatureTest {
    @Test
    void opaqueBytesAreImmutableAndHaveValueIdentityInSessionSnapshots() {
        byte[] bytes = {0, 1, -1, 10};
        var custom = new Feature.Custom("custom", "unknown.provider", "operation", 999, bytes);
        var equal = new Feature.Custom("custom", "unknown.provider", "operation", 999, bytes);
        bytes[0] = 42;
        custom.payload()[1] = 100;
        assertThat(custom.payload()).containsExactly(new byte[] {0, 1, -1, 10});
        assertThat(custom).isEqualTo(equal);
        assertThat(custom.hashCode()).isEqualTo(equal.hashCode());
        var session = new ModelSession(CadDocument.empty());
        session.add(custom);
        var snapshot = session.snapshot();
        session.replace(equal);
        assertThat(session.snapshot())
                .as("Equal opaque payload is not a new edit")
                .isEqualTo(snapshot);
        assertThat(session.undo()).isTrue();
        assertThat(session.document().features().isEmpty()).isTrue();
        assertThat(session.redo()).isTrue();
        assertThat(session.document().features().getFirst()).isEqualTo(custom);
    }

    @Test
    void metadataAndParametersRejectInvalidValuesWithoutNativeInitialization() {
        assertThatThrownBy(() -> new Feature.Custom("id", "provider", "type", 0, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Feature.Custom("id", "bad/provider", "type", 1, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        var p = new FeatureParameter("height", "Height", "mm", 8, 0.001, 1000);
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0, 1001})
            assertThatThrownBy(() -> p.validate(value)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeatureDescriptor("provider", "type", "Name", 1, 1, List.of(p, p)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeatureParameter("height", "Height", "mm", 8, 10, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
