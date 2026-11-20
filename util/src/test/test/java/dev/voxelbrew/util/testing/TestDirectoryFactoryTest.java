package dev.voxelbrew.util.testing;

import static org.assertj.core.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

class TestDirectoryFactoryTest {
    private static final List<Path> paths = CleanupExample.paths;

    public static class DeniedFactory extends TestDirectoryFactory {
        static final List<DeniedFactory> instances = new ArrayList<>();
        static boolean deny;

        public DeniedFactory() {
            instances.add(this);
        }

        @Override
        protected void delete(Path path) throws IOException {
            if (deny && path.getFileName().toString().equals("locked.txt"))
                throw new IOException("Simulated locked test file");
            super.delete(path);
        }
    }

    @Test
    void junitCleansBothSuccessfulAndFailedTestsWithoutChangingTheirResults() {
        paths.clear();
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(CleanupExample.class))
                .configurationParameter("junit.jupiter.tempdir.cleanup.mode.default", "NEVER")
                .configurationParameter("junit.jupiter.tempdir.factory.default", TestDirectoryFactory.class.getName())
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1).failed(1));
        assertThat(paths.size()).isEqualTo(2);
        for (Path path : paths)
            assertThat(Files.exists(path)).as(path.toString()).isFalse();
    }

    @Test
    void deletionFailureLogsErrorAndDoesNotChangeEitherTestResult() {
        paths.clear();
        var records = new ArrayList<LogRecord>();
        Logger logger = Logger.getLogger(TestDirectoryFactory.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        DeniedFactory.deny = true;
        try {
            EngineTestKit.engine("junit-jupiter")
                    .selectors(selectClass(CleanupExample.class))
                    .configurationParameter("junit.jupiter.tempdir.cleanup.mode.default", "NEVER")
                    .configurationParameter("junit.jupiter.tempdir.factory.default", DeniedFactory.class.getName())
                    .execute()
                    .testEvents()
                    .assertStatistics(stats -> stats.succeeded(1).failed(1));
            assertThat(paths.size()).isEqualTo(2);
            assertThat(paths.stream().allMatch(Files::exists)).isTrue();
            assertThat(records.stream()
                            .anyMatch(record -> record.getLevel() == Level.SEVERE
                                    && record.getThrown() instanceof IOException
                                    && record.getMessage().contains("locked.txt")))
                    .isTrue();
        } finally {
            DeniedFactory.deny = false;
            DeniedFactory.instances.forEach(TestDirectoryFactory::close);
            DeniedFactory.instances.clear();
            logger.removeHandler(handler);
        }
        for (Path path : paths)
            assertThat(Files.exists(path)).as(path.toString()).isFalse();
    }
}
