package dev.voxelbrew.printer.internal;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

@lombok.experimental.UtilityClass
public class ArtifactChecks {

    public static String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
