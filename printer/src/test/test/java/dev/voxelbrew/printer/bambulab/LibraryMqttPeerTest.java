package dev.voxelbrew.printer.bambulab;

import static org.assertj.core.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class LibraryMqttPeerTest {
    @Test
    void submittingWithoutAnOpenClientKeepsTheCauseWithoutConnecting() {
        try (var peer = new LibraryMqttPeer(new Connection("fixture.local", "serial", "private"))) {
            assertThatThrownBy(() -> peer.submit(new JsonObject()))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Bambu submission connection failed")
                    .hasCauseInstanceOf(NullPointerException.class);
        }
    }
}
