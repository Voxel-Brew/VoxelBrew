package dev.voxelbrew.util.update;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class GitHubReleaseSourceTest {
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();

    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> authentication = new CopyOnWriteArrayList<>();
    private final ReleaseVersion version = ReleaseVersion.release("1.2.3");
    private final String asset = "VoxelBrewCLI-1.2.3-linux-x64.zip";

    @TempDir
    Path directory;

    private HttpServer server;
    private ExecutorService workers;
    private URI root;

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            for (String header : List.of("Authorization", "Proxy-Authorization", "Cookie")) {
                if (exchange.getRequestHeaders().containsKey(header)) authentication.add(header);
            }
            try (exchange) {
                handler.get().handle(exchange);
            }
        });
        server.start();
        root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
        workers.shutdownNow();
        assertThat(authentication)
                .as("Public updates must never send credentials")
                .isEqualTo(List.of());
    }

    @Test
    void discoversLatestAndExplicitVersionsAndFollowsAssetRedirectWithoutAuthentication() throws Exception {
        var userAgents = new CopyOnWriteArrayList<String>();
        handler.set(exchange -> {
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/repos/")) {
                assertThat(exchange.getRequestHeaders().getFirst("Accept")).isEqualTo("application/vnd.github+json");
                respond(exchange, 200, metadata().getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/Voxel-Brew/")) {
                exchange.getResponseHeaders().set("Location", "/cdn/asset");
                exchange.sendResponseHeaders(302, -1);
            } else {
                respond(exchange, 200, new byte[] {0, 1, 2, (byte) 255});
            }
        });
        var source = new GitHubReleaseSource(root);
        var token = new UpdateCancellation();
        var release = source.fetch(null, token);
        assertThat(release.version()).isEqualTo(version);
        assertThat(release.assets()).isEqualTo(Set.of(asset, "SHA256SUMS"));
        assertThat(source.fetch(version, token)).isEqualTo(release);
        Path file = directory.resolve("download with spaces.zip");
        source.download(release, asset, file, token);
        assertThat(Files.readAllBytes(file)).containsExactly(new byte[] {0, 1, 2, (byte) 255});
        assertThat(paths)
                .isEqualTo(List.of(
                        "/repos/Voxel-Brew/VoxelBrew/releases/latest",
                        "/repos/Voxel-Brew/VoxelBrew/releases/tags/v1.2.3",
                        "/Voxel-Brew/VoxelBrew/releases/download/v1.2.3/" + asset,
                        "/cdn/asset"));
        assertThat(userAgents.stream().allMatch(agent -> agent.startsWith("VoxelBrew/")))
                .isTrue();
    }

    @Test
    void diagnosesMissingReleasesRateLimitsAndServerFailuresWithoutLoginInstructions() {
        var source = new GitHubReleaseSource(root);
        for (int status : List.of(404, 403, 429, 500)) {
            handler.set(exchange -> {
                if (status == 403 || status == 429) {
                    exchange.getResponseHeaders().set("X-RateLimit-Remaining", "0");
                    exchange.getResponseHeaders().set("Retry-After", "60");
                }
                respond(exchange, status, "error".getBytes(StandardCharsets.UTF_8));
            });
            var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> source.fetch(null, new UpdateCancellation()), IOException.class);
            assertThat(failure.getMessage().contains("HTTP " + status)).isTrue();
            assertThat(failure.getMessage().contains("gh auth")).isFalse();
            if (status == 403 || status == 429) {
                assertThat(failure.getMessage().contains("rate limit")).isTrue();
            }
        }
    }

    @Test
    void failedAndTruncatedDownloadsRemovePartialFilesButPreserveExistingDestinations() throws Exception {
        var source = new GitHubReleaseSource(root);
        var release = new ReleaseSource.Release(version, Set.of(asset));
        Path file = Files.writeString(directory.resolve("existing.zip"), "keep");
        assertThatThrownBy(() -> source.download(release, asset, file, new UpdateCancellation()))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(file)).isEqualTo("keep");
        assertThat(paths.isEmpty()).isTrue();
        for (int status : List.of(404, 200)) {
            handler.set(exchange -> {
                exchange.sendResponseHeaders(status, 100);
                exchange.getResponseBody().write(new byte[] {1, 2});
            });
            Path partial = directory.resolve("partial-" + status);
            assertThatThrownBy(() -> source.download(release, asset, partial, new UpdateCancellation()))
                    .isInstanceOf(IOException.class);
            assertThat(Files.exists(partial)).isFalse();
        }
    }

    @Test
    void enforcesDeclaredAndStreamingMetadataAndChecksumLimits() {
        var source = new GitHubReleaseSource(root);
        for (boolean declared : List.of(true, false)) {
            handler.set(exchange -> {
                exchange.sendResponseHeaders(200, declared ? 2 * 1024 * 1024 : 0);
                if (!declared) exchange.getResponseBody().write(new byte[2 * 1024 * 1024]);
            });
            assertThatThrownBy(() -> source.fetch(null, new UpdateCancellation()))
                    .isInstanceOf(IOException.class);
            Path file = directory.resolve("SHA256SUMS");
            assertThatThrownBy(() -> source.download(
                            new ReleaseSource.Release(version, Set.of("SHA256SUMS")),
                            "SHA256SUMS",
                            file,
                            new UpdateCancellation()))
                    .isInstanceOf(IOException.class);
            assertThat(Files.exists(file)).isFalse();
        }
    }

    @Test
    void cancelsDuringHeadersAndBodyAndRemovesPartialDownloadPromptly() throws Exception {
        for (boolean body : List.of(false, true)) {
            var entered = new CountDownLatch(1);
            var releaseServer = new CountDownLatch(1);
            handler.set(exchange -> {
                if (body) {
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write(1);
                    exchange.getResponseBody().flush();
                }
                entered.countDown();
                try {
                    releaseServer.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            var cancellation = new UpdateCancellation();
            Path file = directory.resolve("cancelled-" + body);
            Future<?> pending = workers.submit(() -> {
                new GitHubReleaseSource(root)
                        .download(new ReleaseSource.Release(version, Set.of(asset)), asset, file, cancellation);
                return null;
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                cancellation.cancel();
                var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> pending.get(3, TimeUnit.SECONDS), ExecutionException.class);
                assertThat(failure.getCause()).isInstanceOf(CancellationException.class);
                assertThat(Files.exists(file)).isFalse();
            } finally {
                releaseServer.countDown();
            }
        }
    }

    @Test
    void timeoutIncludesAStalledResponseBody() throws Exception {
        var releaseServer = new CountDownLatch(1);
        handler.set(exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write(1);
            exchange.getResponseBody().flush();
            try {
                releaseServer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        var source = new GitHubReleaseSource(root, Duration.ofMillis(350), Duration.ofMillis(350));
        Path file = directory.resolve("timed-out");
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> source.download(
                                new ReleaseSource.Release(version, Set.of(asset)),
                                asset,
                                file,
                                new UpdateCancellation()),
                        IOException.class);
                assertThat(failure.getMessage().contains("timed out")).isTrue();
                assertThat(Files.exists(file)).isFalse();
            });
        } finally {
            releaseServer.countDown();
        }
    }

    @Test
    void fixtureOverrideCannotRedirectProductionUpdatesToARemoteOrCredentialedOrigin() {
        for (String invalid : List.of(
                "https://example.com/",
                "http://localhost:8080/",
                "http://127.0.0.1/",
                "http://user:secret@127.0.0.1:8080/",
                "http://127.0.0.1:8080/?token=abc",
                "http://127.0.0.1:8080/path/")) {
            assertThatThrownBy(() -> new GitHubReleaseSource(URI.create(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private String metadata() {
        return "{\"tag_name\":\"v1.2.3\",\"draft\":false,\"prerelease\":false,\"assets\":[{\"name\":\""
                + asset
                + "\"},{\"name\":\"SHA256SUMS\"}]}";
    }
}
