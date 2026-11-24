package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * worldCoverHealthy() and surfaceWaterHealthy() must actually call the configured proxy's own
 * dedicated /__health endpoint and require its documented exact-200 success - not the generic
 * Range-based recovery probe HostBackoff uses for circuit-breaker purposes (MINECRAFT-580/571
 * comment 55850's correction of 55840).
 */
final class CubicSettingsHealthTest {
    @AfterEach
    void clearOverrides() {
        System.clearProperty("earthplatform.earth.worldCoverBase");
        System.clearProperty("earthplatform.earth.surfaceWaterBase");
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
}
