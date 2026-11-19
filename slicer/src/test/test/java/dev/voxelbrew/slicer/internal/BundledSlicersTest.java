package dev.voxelbrew.slicer.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BundledSlicersTest {
    @ParameterizedTest
    @CsvSource({
        "Windows 11, amd64, windows",
        "Linux, x86_64, linux",
        "Mac OS X, x86_64, macos",
        "Mac OS X, aarch64, macos",
        "Linux, aarch64, ''",
        "Windows 11, aarch64, ''"
    })
    void selectsOnlyTheMatchingBundlePlatform(String os, String architecture, String expected) {
        assertThat(BundledSlicers.platform(os, architecture)).isEqualTo(expected);
    }
}
