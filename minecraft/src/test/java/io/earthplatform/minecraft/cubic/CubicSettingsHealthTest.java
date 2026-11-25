package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.TerrariumTileSource;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
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
     * proxy), pointed at a local, byte-correct loopback CHELSA fixture - never the real host, so this
     * leg stays deterministic and instant - that also primes and serves actually-useful warmed data.
     * John's 55922 refinement, and the 55934/55935 review kickback's correction of my first attempt
     * (which used a separate, never-primed database and tested only {@code /__health}): a fresh
     * mod-side cache must get a correct cached read through the proxy's own retained SQLite cache
     * while the origin is down, not just a health-flag flip, and recovery must be proven by a
     * genuinely fresh (never-cached) read succeeding afterward, not a replay of the same cached one.
     * Skipped unless {@code CHELSA_CACHE_PROXY_REPO} names the proxy's repository root.
     */
    @Test
    void climateHealthyAndReachableThroughTheRealProxyDuringAnOutageAndRecoveryWithAUsefulWarmRead() throws Exception {
        String proxyRepo = System.getenv("CHELSA_CACHE_PROXY_REPO");
        assumeTrue(proxyRepo != null && !proxyRepo.isEmpty(), "set CHELSA_CACHE_PROXY_REPO to chelsa-cache-proxy's repository root");

        double primedLat = 40.7128, primedLon = -74.0060; // New York: primed while the origin is up.
        double freshLat = -1.2921, freshLon = 36.8219; // Nairobi: never touched until after recovery.

        try (RainFixture fixture = new RainFixture((short) 500)) { // 500 * 0.1 = 50.0 mm/year everywhere.
            Path dbFile = Files.createTempFile("chelsa-cache-proxy-outage-test", ".sqlite3");
            Files.deleteIfExists(dbFile);
            int proxyPort;
            try (ServerSocket free = new ServerSocket(0)) { proxyPort = free.getLocalPort(); }
            ProcessBuilder launch = new ProcessBuilder("python3", "-m", "chelsa_cache_proxy");
            launch.directory(new java.io.File(proxyRepo));
            launch.environment().put("PROXY_BIND_PORT", String.valueOf(proxyPort));
            launch.environment().put("PROXY_SQLITE_PATH", dbFile.toString());
            launch.environment().put("CHELSA_UPSTREAM_BASE", fixture.base());
            launch.redirectErrorStream(true);
            Process proxy = launch.start();
            try {
                String proxyBase = "http://127.0.0.1:" + proxyPort + "/";
                waitForChelsaProxyReady(proxyBase);
                System.setProperty("earthplatform.earth.climateBase", proxyBase);
                System.setProperty("earthplatform.earth.dedicatedHealthProbeMs", "20");
                CubicSettings.resetDedicatedProbesForTesting();

                // Prime: a real, useful read through the real proxy while the fixture origin is up, warming
                // its SQLite cache with a correct decoded value.
                double primed;
                try (var source = new ClimateSource(URI.create(proxyBase), Files.createTempDirectory("chelsa-outage-prime"), true, 32, 8, Duration.ofSeconds(30))) {
                    primed = source.precipitation(primedLat, primedLon);
                }
                assertEquals(50.0, primed, 0.01, "primed read decodes the planted rain value");

                // Outage: the fixture origin goes down entirely (not just its /__health route).
                fixture.down.set(true);
                assertFalse(CubicSettings.climateHealthy(), "climateHealthy() is false while the fixture origin is down");
                // climateReachable() never blocks the calling thread: the very first call after a reset can
                // legitimately still return the cached pre-reset "true" while its async probe is in flight -
                // await the real settle, as worldCoverReachableTransitionsThroughAnOutageAndRecoversAsynchronously() does.
                awaitTrue(() -> !CubicSettings.climateReachable());

                // A useful warmed read survives the outage: a FRESH mod-side cache directory, reading the
                // SAME already-primed place, served entirely from the proxy's own retained SQLite cache -
                // the fixture origin is down throughout, so this can only have come from that warm cache.
                double warm;
                try (var source = new ClimateSource(URI.create(proxyBase), Files.createTempDirectory("chelsa-outage-warm"), true, 32, 8, Duration.ofSeconds(30))) {
                    warm = source.precipitation(primedLat, primedLon);
                }
                assertEquals(50.0, warm, 0.01, "the warm proxy-cached read still decodes correctly while the fixture origin is down");

                // Recovery: the fixture origin comes back, health recovers, and a genuinely fresh
                // (never-cached, different place) read succeeds too - real recovery, not a cached fluke.
                fixture.down.set(false);
                awaitTrue(CubicSettings::climateReachable);
                assertTrue(CubicSettings.climateHealthy(), "climateHealthy() is true once the fixture origin recovers");
                double recovered;
                try (var source = new ClimateSource(URI.create(proxyBase), Files.createTempDirectory("chelsa-outage-recovered"), true, 32, 8, Duration.ofSeconds(30))) {
                    recovered = source.precipitation(freshLat, freshLon);
                }
                assertEquals(50.0, recovered, 0.01, "a genuinely fresh, previously-uncached read succeeds once the fixture origin has recovered");
            } finally {
                proxy.destroy();
                proxy.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * {@link CubicSettings#climate()} and {@link CubicSettings#riversAndStreams()} themselves - not a
     * directly-constructed {@link ClimateSource}/{@link TerrariumTileSource} - actually reach their
     * configured {@code climateBase()}/{@code terrariumBase()}: a regression that would fail if either
     * factory ever reverted to its hardcoded default. The 55934/55935 review kickback's correction of
     * my first PR #2 attempt, which only exercised directly-constructed sources. {@code surfaceWaterBase()}
     * is pointed at a fixture that always answers 404 (a real, already-handled "no water data here"
     * outcome - {@code Index.MISSING}, never an exception), so this needs no third raster format.
     * climate() and riversAndStreams()'s own rain reader query DIFFERENT real places so neither can
     * satisfy the other's cold miss from the disk cache both factories otherwise share, per the
     * singleton/cache isolation the review asked for.
     */
    @Test
    void climateAndRiversAndStreamsFactoriesRouteThroughTheirConfiguredBases() throws Exception {
        double climateLat = 51.5074, climateLon = -0.1278; // London.
        double drainageLat = 48.8566, drainageLon = 2.3522; // Paris - far enough that its RAIN rows never overlap London's.

        // climate() and riversAndStreams() both cache under the real, persistent game-dir cache folder
        // (CubicSettings.cacheFolder(), shared across every test run in this gameDir, not reset by
        // resetSourcesForTesting() - that only discards the in-memory singletons). A prior run's cached
        // bytes for these exact coordinates would let a reader answer correctly straight from disk
        // without ever touching this test's fixtures, hiding the very routing bug this test exists to
        // catch - so both datasets' on-disk caches are wiped first to force a real network read.
        Path gameDir = io.earthplatform.minecraft.platform.Loader.INSTANCE.gameDir();
        Path cacheFolder = gameDir.resolve("earthplatform").resolve("cache");
        deleteRecursively(cacheFolder.resolve(ClimateSource.DATASET_ID));
        deleteRecursively(cacheFolder.resolve(TerrariumTileSource.DATASET_ID.toLowerCase(java.util.Locale.ROOT)));

        HttpServer water404 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        water404.createContext("/", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
        water404.start();
        try (RainFixture rain = new RainFixture((short) 500); ElevationFixture elevation = new ElevationFixture(50f)) {
            System.setProperty("earthplatform.earth.climateBase", rain.base());
            System.setProperty("earthplatform.earth.terrariumBase", elevation.base());
            System.setProperty("earthplatform.earth.surfaceWaterBase", "http://127.0.0.1:" + water404.getAddress().getPort() + "/");
            CubicSettings.resetSourcesForTesting();
            try {
                double precipitation = CubicSettings.climate().precipitation(climateLat, climateLon);
                assertEquals(50.0, precipitation, 0.01, "climate() factory decodes the planted rain value through climateBase()");
                assertTrue(rain.hits.get() > 0, "climate() factory actually reached the configured climateBase() fixture");

                // strength() (through traced()) drains the elevation tile, which touches both terrariumBase()
                // (for height) and, via Drainage's rain reader, climateBase() too - the SAME override
                // riversAndStreams() wires its own separate ClimateSource instance to, per John's 55858 note
                // that the two climate consumers (climate() above and this one) are never merged into one.
                int rainHitsBefore = rain.hits.get();
                double strength = CubicSettings.riversAndStreams().strength(drainageLat, drainageLon);
                assertTrue(strength >= 0 && strength <= 1, "riversAndStreams().strength() returns a real value, not an exception");
                assertTrue(elevation.hits.get() > 0, "riversAndStreams() factory actually reached the configured terrariumBase() fixture");
                assertTrue(rain.hits.get() > rainHitsBefore, "riversAndStreams()'s own rain reader actually reached the configured climateBase() fixture");
            } finally {
                System.clearProperty("earthplatform.earth.climateBase");
                System.clearProperty("earthplatform.earth.terrariumBase");
                System.clearProperty("earthplatform.earth.surfaceWaterBase");
                CubicSettings.resetSourcesForTesting();
            }
        } finally {
            water404.stop(0);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); } catch (Exception ignored) { }
            });
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

    /**
     * The exact byte layout {@link ClimateSource#parse} requires: a classic, little-endian, striped,
     * single-band 16-bit TIFF with CHELSA's exact GeoTIFF pixel scale and tie point, one strip (row)
     * per {@code HEIGHT} rows, each {@code WIDTH * 2} bytes. Built once in memory (header and the
     * {@code StripOffsets}/{@code StripByteCounts} tables only - about 160 KiB; never the 1.8 GB a
     * real file's row data would take), with every row's actual pixel bytes synthesized on request
     * instead, since every row carries the same planted value.
     */
    private static final class ChelsaRainHeader {
        static final int WIDTH = 43200, HEIGHT = 20880, PIXELS_PER_DEGREE = 120;
        static final double WEST = -180, NORTH = 84;
        static final int ROW_BYTES = WIDTH * 2;
        final byte[] bytes;
        final int length;

        ChelsaRainHeader() {
            int ifdOffset = 8, entryCount = 10;
            int ifdSize = 2 + entryCount * 12 + 4;
            int scaleOffset = ifdOffset + ifdSize;
            int tieOffset = scaleOffset + 3 * 8;
            int stripOffsetsOffset = tieOffset + 6 * 8;
            int stripCountsOffset = stripOffsetsOffset + HEIGHT * 4;
            length = stripCountsOffset + HEIGHT * 4;
            ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
            buf.put((byte) 'I').put((byte) 'I');
            buf.putShort((short) 42);
            buf.putInt(ifdOffset);
            buf.position(ifdOffset);
            buf.putShort((short) entryCount);
            putInlineEntry(buf, 256, 4, 1, WIDTH); // ImageWidth
            putInlineEntry(buf, 257, 4, 1, HEIGHT); // ImageLength
            putInlineShortEntry(buf, 258, 3, 1, (short) 16); // BitsPerSample
            putInlineShortEntry(buf, 259, 3, 1, (short) 1); // Compression: none
            putInlineEntry(buf, 273, 4, HEIGHT, stripOffsetsOffset); // StripOffsets
            putInlineShortEntry(buf, 277, 3, 1, (short) 1); // SamplesPerPixel
            putInlineEntry(buf, 278, 4, 1, 1); // RowsPerStrip
            putInlineEntry(buf, 279, 4, HEIGHT, stripCountsOffset); // StripByteCounts
            putInlineEntry(buf, 33550, 12, 3, scaleOffset); // ModelPixelScaleTag
            putInlineEntry(buf, 33922, 12, 6, tieOffset); // ModelTiePointTag
            buf.putInt(0); // next IFD offset: none
            buf.position(scaleOffset);
            buf.putDouble(1.0 / PIXELS_PER_DEGREE).putDouble(1.0 / PIXELS_PER_DEGREE).putDouble(0);
            buf.position(tieOffset);
            buf.putDouble(0).putDouble(0).putDouble(0).putDouble(WEST).putDouble(NORTH).putDouble(0);
            buf.position(stripOffsetsOffset);
            for (int row = 0; row < HEIGHT; row++) buf.putInt(length + row * ROW_BYTES);
            buf.position(stripCountsOffset);
            for (int row = 0; row < HEIGHT; row++) buf.putInt(ROW_BYTES);
            bytes = buf.array();
        }

        private static void putInlineEntry(ByteBuffer buf, int tag, int type, long count, long value) {
            buf.putShort((short) tag).putShort((short) type).putInt((int) count).putInt((int) value);
        }

        private static void putInlineShortEntry(ByteBuffer buf, int tag, int type, long count, short value) {
            buf.putShort((short) tag).putShort((short) type).putInt((int) count).putShort(value).putShort((short) 0);
        }

        /** {@code count} bytes of RAIN row data, every pixel the same planted value - every row is identical. */
        static byte[] rowData(int count, short rainValue) {
            byte[] out = new byte[count];
            ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            int i = 0;
            for (; i + 2 <= count; i += 2) buf.putShort(rainValue);
            if (i < count) buf.put((byte) (rainValue & 0xFF));
            return out;
        }
    }

    /**
     * A loopback HTTP origin shaped exactly like real CHELSA for the RAIN (bio12) layer only: serves
     * a valid, minimal, deterministic GeoTIFF whose every pixel is the same planted value, with hits
     * counted and an up/down toggle for the outage/recovery leg - never the real CHELSA host.
     */
    private static final class RainFixture implements AutoCloseable {
        private final ChelsaRainHeader header = new ChelsaRainHeader();
        private final HttpServer server;
        final AtomicBoolean down = new AtomicBoolean();
        final AtomicInteger hits = new AtomicInteger();
        private final short rainValue;

        RainFixture(short rainValue) throws java.io.IOException {
            this.rainValue = rainValue;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/climatologies/1981-2010/bio/CHELSA_bio12_1981-2010_V.2.1.tif", exchange -> {
                hits.incrementAndGet();
                if (down.get()) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
                String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
                long start = 0;
                int count = header.length;
                if (rangeHeader != null) {
                    var matcher = java.util.regex.Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(rangeHeader);
                    if (matcher.matches()) {
                        start = Long.parseLong(matcher.group(1));
                        count = (int) (Long.parseLong(matcher.group(2)) - start + 1);
                    }
                }
                byte[] body = read(start, count);
                exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (start + body.length - 1) + "/*");
                exchange.sendResponseHeaders(206, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            // chelsa-cache-proxy's own dedicated health probe always GETs this exact path on its
            // upstream (server.py's HEALTH_PROBE_PATH) regardless of which data path a client is
            // using - without a route for it here, the probe gets the JDK HttpServer's unconditional
            // default 404 both during and after the "outage", and recovery can never be observed.
            server.createContext("/input/dem_geo.sdat", exchange -> {
                if (down.get()) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
                byte[] body = {0};
                exchange.getResponseHeaders().add("Content-Range", "bytes 0-0/*");
                exchange.sendResponseHeaders(206, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        private byte[] read(long start, int count) {
            if (start + count <= header.length) return Arrays.copyOfRange(header.bytes, (int) start, (int) start + count);
            if (start >= header.length) return ChelsaRainHeader.rowData(count, rainValue);
            // Only the very first header fetch, before its real size is known, over-asks past it - the tail
            // past the real header is never read by the parser, so a zero-filled remainder is harmless.
            byte[] out = new byte[count];
            System.arraycopy(header.bytes, (int) start, out, 0, header.length - (int) start);
            return out;
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }

        @Override public void close() { server.stop(0); }
    }

    /** A loopback HTTP origin serving the same constant-elevation Terrarium tile for any zoom/x/y. */
    private static final class ElevationFixture implements AutoCloseable {
        private final HttpServer server;
        final AtomicInteger hits = new AtomicInteger();

        ElevationFixture(float metres) throws Exception {
            float[] plane = new float[256 * 256];
            // A slight gradient, not a perfectly flat plane, so the drainage algorithm never has to break ties.
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) plane[y * 256 + x] = metres + x * 0.01f + y * 0.001f;
            byte[] tile = TerrariumTileSource.encode(plane);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                hits.incrementAndGet();
                exchange.sendResponseHeaders(200, tile.length);
                exchange.getResponseBody().write(tile);
                exchange.close();
            });
            server.start();
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }

        @Override public void close() { server.stop(0); }
    }
}
