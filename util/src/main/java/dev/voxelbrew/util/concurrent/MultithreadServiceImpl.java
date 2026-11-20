package dev.voxelbrew.util.concurrent;

import dev.voxelbrew.api.multithread.MultithreadService;
import dev.voxelbrew.api.userconfig.UserConfigException;
import dev.voxelbrew.api.userconfig.UserConfigService;
import dev.voxelbrew.util.services.Services;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the application pool on first use.
 */
@Slf4j
@RequiredArgsConstructor
public final class MultithreadServiceImpl implements MultithreadService {
    public static final int MIN_THREADS = 1;
    public static final int MAX_THREADS = 32;

    private static final String THREADS_KEY = "voxelbrew.compute.threads";

    private static final ThreadLocal<Boolean> COMMON_WORKER = new ThreadLocal<>();

    private static ExecutorService executor;

    @NonNull
    private final UserConfigService configuration;

    private static java.util.concurrent.ThreadFactory workerFactory() {
        var factory = Thread.ofPlatform().daemon().name("voxelbrew-worker-", 0).factory();
        return task -> factory.newThread(() -> {
            COMMON_WORKER.set(true);
            try {
                task.run();
            } finally {
                COMMON_WORKER.remove();
            }
        });
    }

    private static synchronized ExecutorService applicationExecutor(UserConfigService configuration) {
        if (executor == null) {
            executor = Executors.newFixedThreadPool(configuredThreads(configuration), workerFactory());
        }
        return executor;
    }

    static boolean isCommonWorker() {
        return Boolean.TRUE.equals(COMMON_WORKER.get());
    }

    static int configuredThreads(UserConfigService config) {
        try {
            var setting = config.readUserConfig(THREADS_KEY);
            if (setting.isPresent()) {
                int requested = Integer.parseInt(setting.get().strip());
                int threads = Math.clamp(requested, MIN_THREADS, MAX_THREADS);
                if (threads != requested) {
                    log.warn(
                            "{} must be between {} and {}. Using {} threads",
                            THREADS_KEY,
                            MIN_THREADS,
                            MAX_THREADS,
                            threads);
                }
                return threads;
            }
            int defaultThreads = computeDefaultThreads();
            log.warn("{} is not set. Using {} threads", THREADS_KEY, defaultThreads);
            return defaultThreads;
        } catch (NumberFormatException | UserConfigException failure) {
            int defaultThreads = computeDefaultThreads();
            log.warn("Cannot read a valid {}. Using {} threads", THREADS_KEY, defaultThreads, failure);
            return defaultThreads;
        }
    }

    private static int computeDefaultThreads() {
        return Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors());
    }

    public MultithreadServiceImpl() {
        this(Services.load(UserConfigService.class));
    }

    @Override
    public ExecutorService commonExecutorService() {
        return applicationExecutor(configuration);
    }
}
