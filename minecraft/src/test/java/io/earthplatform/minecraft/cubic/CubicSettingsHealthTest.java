package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * worldCoverHealthy() and surfaceWaterHealthy() must actually call the configured proxy's own
 * dedicated /__health endpoint and require its documented exact-200 success - not the generic
 * Range-based recovery probe HostBackoff uses for circuit-breaker purposes (MINECRAFT-580/571
 * comment 55850's correction of 55840). worldCoverReachable()/surfaceWaterReachable() wire that
 * into an actual, asynchronous, rate-limited caller through EarthStatus.unreachable() rather than
 * leaving it tested but never invoked (55855's follow-up finding on 8f8e2ccc).
 */
final class CubicSettingsHealthTest {
    @AfterEach
    void clearOverrides() {
        System.clearProperty("earthplatform.earth.worldCoverBase");
        System.clearProperty("earthplatform.earth.surfaceWaterBase");
        System.clearProperty("earthplatform.earth.climateBase");
        System.clearProperty("earthplatform.earth.dedicatedHealthProbeMs");
        CubicSettings.resetDedicatedProbesForTesting();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("condition never became true within 5s");
            Thread.sleep(20);
        }
    }

    @Test
    void worldCoverHealthyWithoutOverrideNeedsNoNetworkCall() {
        assertTrue(CubicSettings.worldCoverHealthy());
    }

    @Test
    void surfaceWaterHealthyWithoutOverrideNeedsNoNetworkCall() {
        assertTrue(CubicSettings.surfaceWaterHealthy());
    }

    @Test
    void worldCoverHealthyCallsTheConfiguredDedicatedEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__health", exchange -> {
            byte[] body = "{\"upstream_reachable\":true}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty("earthplatform.earth.worldCoverBase", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertTrue(CubicSettings.worldCoverHealthy());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void worldCoverUnhealthyWhenTheDedicatedEndpointAnswers503() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__health", exchange -> {
            byte[] body = "{\"upstream_reachable\":false}".getBytes();
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty("earthplatform.earth.worldCoverBase", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertFalse(CubicSettings.worldCoverHealthy());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void worldCoverUnhealthyOnAMismatchedRoute() throws Exception {
        // A wrong or mismatched configured proxy answering the ordinary 404 case the health probe
        // must not treat as reachable - the exact false-positive John's acceptance findings target.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty("earthplatform.earth.worldCoverBase", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertFalse(CubicSettings.worldCoverHealthy());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void surfaceWaterHealthyCallsTheConfiguredDedicatedEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__health", exchange -> {
            byte[] body = "{\"healthy\":true,\"detail\":\"ok\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty("earthplatform.earth.surfaceWaterBase", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertTrue(CubicSettings.surfaceWaterHealthy());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void surfaceWaterUnhealthyWhenTheDedicatedEndpointAnswers503() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__health", exchange -> {
            byte[] body = "{\"healthy\":false,\"detail\":\"upstream down\"}".getBytes();
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty("earthplatform.earth.surfaceWaterBase", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertFalse(CubicSettings.surfaceWaterHealthy());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void worldCoverReachableWithoutOverrideIsAlwaysTrueAndNeverProbes() {
        assertTrue(CubicSettings.worldCoverReachable());
        assertTrue(CubicSettings.worldCoverReachable());
    }

    @Test
    void worldCoverReachableTransitionsThroughAnOutageAndRecoversAsynchronously() throws Exception {
        AtomicBoolean healthy = new AtomicBoolean(false);
        AtomicInteger healthRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__health", exchange -> {
            healthRequests.incrementAndGet();
            int status = healthy.get() ? 200 : 503;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty("earthplatform.earth.worldCoverBase", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            System.setProperty("earthplatform.earth.dedicatedHealthProbeMs", "20");

            // The outage: calling reachable() repeatedly never blocks, and settles to false once the
            // async probe completes - while a warmed tile would stay servable from WorldCoverSource's
            // own cache regardless, since dedicatedHealthCheck() never touches that cache in either
            // direction (a separate call path entirely - confirmed by reading the implementation).
            awaitTrue(() -> !CubicSettings.worldCoverReachable());

            // Recovery: once the proxy's dedicated endpoint reports 200 again, a later call (after the
            // rate-limit interval) picks that up.
            healthy.set(true);
            awaitTrue(CubicSettings::worldCoverReachable);

            assertTrue(healthRequests.get() >= 2, "expected at least one probe during the outage and one after recovery");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void worldCoverReachableRateLimitsConcurrentCallsToOneProbeInFlight() throws Exception {
        AtomicInteger healthRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__health", exchange -> {
            healthRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty("earthplatform.earth.worldCoverBase", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            System.setProperty("earthplatform.earth.dedicatedHealthProbeMs", "60000");

            for (int i = 0; i < 50; i++) CubicSettings.worldCoverReachable();
            Thread.sleep(200);
            assertEquals(1, healthRequests.get(), "fifty calls inside one rate-limit window must start exactly one probe");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void climateHealthyWithoutOverrideNeedsNoNetworkCall() {
        assertTrue(CubicSettings.climateHealthy());
    }

    @Test
    void climateHealthyCallsTheConfiguredDedicatedEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty("earthplatform.earth.climateBase", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertTrue(CubicSettings.climateHealthy());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void climateUnhealthyWhenTheDedicatedEndpointAnswers502() throws Exception {
        // chelsa-cache-proxy's own /__health contract: exactly 200 (healthy) or 502 (not) - confirmed
        // by reading brooswit-intel/chelsa-cache-proxy's server.py directly.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__health", exchange -> {
            exchange.sendResponseHeaders(502, -1);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty("earthplatform.earth.climateBase", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertFalse(CubicSettings.climateHealthy());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void climateReachableTransitionsThroughAnOutageAndRecoversAsynchronously() throws Exception {
        AtomicBoolean healthy = new AtomicBoolean(false);
        AtomicInteger healthRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/__health", exchange -> {
            healthRequests.incrementAndGet();
            exchange.sendResponseHeaders(healthy.get() ? 200 : 502, -1);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty("earthplatform.earth.climateBase", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            System.setProperty("earthplatform.earth.dedicatedHealthProbeMs", "20");

            awaitTrue(() -> !CubicSettings.climateReachable());
            healthy.set(true);
            awaitTrue(CubicSettings::climateReachable);

            assertTrue(healthRequests.get() >= 2);
        } finally {
            server.stop(0);
        }
    }

    /**
     * climateHealthy()/climateReachable() through the REAL chelsa-cache-proxy binary (not a fake
     * proxy), pointed at a local loopback fixture as its upstream so this leg never touches the real
     * CHELSA host and stays deterministic and instant - the outage/recovery control John's 55922
     * refinement asked for, paired with GlobeTest's live dual-consumer/warm-replay checks against the
     * same proxy. Skipped unless {@code CHELSA_CACHE_PROXY_REPO} names the proxy's repository root.
     */
    @Test
    void climateHealthyAndReachableThroughTheRealProxyDuringAnOutageAndRecovery() throws Exception {
        String proxyRepo = System.getenv("CHELSA_CACHE_PROXY_REPO");
        assumeTrue(proxyRepo != null && !proxyRepo.isEmpty(), "set CHELSA_CACHE_PROXY_REPO to chelsa-cache-proxy's repository root");

        AtomicBoolean fixtureDown = new AtomicBoolean(true);
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            if (fixtureDown.get()) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            byte[] body = new byte[4];
            exchange.getResponseHeaders().add("Content-Range", "bytes 0-3/*");
            exchange.sendResponseHeaders(206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fixture.start();
        String fixtureBase = "http://127.0.0.1:" + fixture.getAddress().getPort() + "/";

        Path dbFile = Files.createTempFile("chelsa-cache-proxy-outage-test", ".sqlite3");
        Files.deleteIfExists(dbFile);
        int proxyPort;
        try (ServerSocket free = new ServerSocket(0)) { proxyPort = free.getLocalPort(); }
        ProcessBuilder launch = new ProcessBuilder("python3", "-m", "chelsa_cache_proxy");
        launch.directory(new java.io.File(proxyRepo));
        launch.environment().put("PROXY_BIND_PORT", String.valueOf(proxyPort));
        launch.environment().put("PROXY_SQLITE_PATH", dbFile.toString());
        launch.environment().put("CHELSA_UPSTREAM_BASE", fixtureBase);
        launch.redirectErrorStream(true);
        Process proxy = launch.start();
        try {
            String proxyBase = "http://127.0.0.1:" + proxyPort + "/";
            waitForChelsaProxyReady(proxyBase);
            System.setProperty("earthplatform.earth.climateBase", proxyBase);
            System.setProperty("earthplatform.earth.dedicatedHealthProbeMs", "20");
            CubicSettings.resetDedicatedProbesForTesting();

            assertFalse(CubicSettings.climateHealthy(), "climateHealthy() is false while the loopback fixture reports the upstream down");
            // climateReachable() never blocks the calling thread: the very first call after a reset can
            // legitimately still return the cached pre-reset "true" while its async probe is in flight -
            // await the real settle, as worldCoverReachableTransitionsThroughAnOutageAndRecoversAsynchronously() does.
            awaitTrue(() -> !CubicSettings.climateReachable());

            fixtureDown.set(false);
            awaitTrue(CubicSettings::climateReachable);
            assertTrue(CubicSettings.climateHealthy(), "climateHealthy() is true once the loopback fixture recovered");
        } finally {
            proxy.destroy();
            proxy.waitFor(5, TimeUnit.SECONDS);
            fixture.stop(0);
        }
    }

    private static void waitForChelsaProxyReady(String proxyBase) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        Exception last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(proxyBase + "_internal/stats")).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return;
            } catch (Exception failure) {
                last = failure;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("chelsa-cache-proxy never became ready at " + proxyBase, last);
    }
}
