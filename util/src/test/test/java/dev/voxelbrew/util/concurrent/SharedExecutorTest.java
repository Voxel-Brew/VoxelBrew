package dev.voxelbrew.util.concurrent;

import static org.assertj.core.api.Assertions.*;

import dev.voxelbrew.api.userconfig.UserConfigService;
import dev.voxelbrew.util.preferences.UserConfigServiceImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SharedExecutorTest {
    @TempDir
    Path directory;

    @Test
    void sizesThePoolFromSavedUserConfig() {
        UserConfigService config = new UserConfigServiceImpl(directory, "config.properties");
        int fallback = Math.min(
                MultithreadServiceImpl.MAX_THREADS, Runtime.getRuntime().availableProcessors());
        assertThat(MultithreadServiceImpl.configuredThreads(config)).isEqualTo(fallback);
        config.saveUserConfig("voxelbrew.compute.threads", "many");
        assertThat(MultithreadServiceImpl.configuredThreads(config)).isEqualTo(fallback);
    }

    @ParameterizedTest
    @CsvSource({"-1,1", "0,1", "1,1", "7,7", "32,32", "33,32"})
    void clampsConfiguredThreadCount(String setting, int expected) {
        var config = new UserConfigServiceImpl(directory, "config.properties");
        config.saveUserConfig("voxelbrew.compute.threads", setting);
        assertThat(MultithreadServiceImpl.configuredThreads(config)).isEqualTo(expected);
    }

    @Test
    void usesDefaultPoolSizeWhenConfigurationCannotBeRead() throws Exception {
        Files.writeString(directory.resolve("config.properties"), "bad=\\uZZZZ");
        var config = new UserConfigServiceImpl(directory, "config.properties");
        assertThat(MultithreadServiceImpl.configuredThreads(config))
                .isEqualTo(Math.min(
                        MultithreadServiceImpl.MAX_THREADS, Runtime.getRuntime().availableProcessors()));
    }

    @Test
    void orderedQueueDrainsAndReleasesItsSharedWorker() throws Exception {
        try (var backing = Executors.newSingleThreadExecutor()) {
            var lane = new SerialExecutor(backing);
            var results = new java.util.ArrayList<Integer>();
            lane.submit(() -> results.add(1));
            lane.submit(() -> results.add(2));
            lane.shutdown();
            assertThat(lane.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(results).containsExactly(1, 2);
            assertThatThrownBy(() -> lane.submit(() -> {})).isInstanceOf(RejectedExecutionException.class);
            assertThat(backing.submit(() -> "available").get(5, TimeUnit.SECONDS))
                    .isEqualTo("available");
        }
    }

    @Test
    void immediateShutdownInterruptsOnlyTheActiveQueueAndReturnsPendingWork() throws Exception {
        try (var backing = Executors.newSingleThreadExecutor()) {
            var first = new SerialExecutor(backing);
            var second = new SerialExecutor(backing);
            var started = new CountDownLatch(1);
            var active = first.submit(() -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    return true;
                }
                return false;
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var pending = first.submit(() -> {});
            var other = second.submit(() -> Thread.currentThread().isInterrupted());
            assertThat(first.shutdownNow()).containsExactly((Runnable) pending);
            assertThat(active.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(other.get(5, TimeUnit.SECONDS)).isFalse();
            second.shutdown();
            assertThat(first.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void computeWorkJoinsFromInsideTheSharedExecutor() throws Exception {
        var configuration = new UserConfigServiceImpl(directory, "config.properties");
        var service = new MultithreadServiceImpl(configuration);
        var executor = service.commonExecutorService();
        assertThat(new MultithreadServiceImpl().commonExecutorService()).isSameAs(executor);
        var result = executor.submit(() -> ParallelWork.map(List.of(1, 2, 3), value -> value * 2));
        assertThat(result.get(5, TimeUnit.SECONDS)).containsExactly(2, 4, 6);
    }
}
