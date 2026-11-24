package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
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
}
