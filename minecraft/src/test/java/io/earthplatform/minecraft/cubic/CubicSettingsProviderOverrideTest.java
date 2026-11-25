package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.globe.CanopySource;
import io.earthplatform.geography.globe.CopernicusSource;
import io.earthplatform.geography.globe.GsiElevation;
import io.earthplatform.geography.globe.HrdemElevation;
import io.earthplatform.geography.globe.IgnElevation;
import io.earthplatform.geography.globe.ImagerySource;
import io.earthplatform.geography.globe.RemaSource;
import io.earthplatform.geography.globe.UsgsElevation;
import io.earthplatform.minecraft.platform.Loader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * John's 55968/55951 demo-scope ask: in full-local mode, every one of the twelve providers' actual
 * native request must land on its configured loopback override, never silently fall back to the
 * real upstream. Each test here points one provider's new {@code CubicSettings.xBase()} override at
 * a trivial loopback server that only counts hits (its response content is irrelevant - every one of
 * these readers may legitimately throw trying to decode a non-matching body, but by the time that
 * happens the real native HTTP request has already landed on our server, which is the one fact these
 * tests exist to prove), then drives the real production factory and asserts the override was
 * actually reached. Deliberately does not assert anything about decoded values - that is each
 * source's own existing GlobeTest coverage; this file is routing-only, mirroring
 * {@code CubicSettingsHealthTest}'s dedicated-endpoint tests in spirit but for the elevation-refiner
 * and imagery sources that have no HUD health endpoint of their own.
 */
class CubicSettingsProviderOverrideTest {

    // Each source's factory caches on disk under the real, persistent game-dir cache folder, shared
    // across every test run in this gameDir and never reset by resetSourcesForTesting() (that only
    // discards the in-memory singletons - see CubicSettingsProviderOverrideTest's own sibling fix in
    // CubicSettingsHealthTest for the same root cause). A disk cache populated by an earlier run (or
    // an earlier test in this very file, since JUnit gives no ordering guarantee) would let a later
    // run answer straight from disk without ever touching this test's fixture, hiding the very
    // routing bug these tests exist to catch - so every one of these datasets' caches is wiped before
    // each test to force a real network read.
    @BeforeEach
    void wipeDiskCaches() throws Exception {
        Path cacheFolder = Loader.INSTANCE.gameDir().resolve("earthplatform").resolve("cache");
        for (String datasetId : new String[] {CopernicusSource.DATASET_ID, UsgsElevation.DATASET_ID, RemaSource.DATASET_ID,
                HrdemElevation.DATASET_ID, IgnElevation.DATASET_ID, GsiElevation.DATASET_ID, CanopySource.DATASET_ID, ImagerySource.DATASET_ID})
            deleteRecursively(cacheFolder.resolve(datasetId));
    }

    @AfterEach
    void clearOverrides() {
        for (String property : new String[] {"copernicusBase", "usgsBase", "remaBase", "hrdemBase", "ignBase", "gsiBase", "canopyBase", "eoxBase"})
            System.clearProperty("earthplatform.earth." + property);
        CubicSettings.resetSourcesForTesting();
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); } catch (Exception ignored) { }
            });
        }
    }

    @Test
    void copernicusFactoryRoutesThroughCopernicusBase() throws Exception {
        try (Fixture fixture = new Fixture()) {
            System.setProperty("earthplatform.earth.copernicusBase", fixture.base());
            CubicSettings.resetSourcesForTesting();
            try { CubicSettings.copernicus().metres(46.5, 10.0, 0); } catch (Exception ignored) { }
            assertTrue(fixture.hits.get() > 0, "copernicus() factory actually reached the configured copernicusBase() override");
        }
    }

    @Test
    void usgsFactoryRoutesThroughUsgsBase() throws Exception {
        try (Fixture fixture = new Fixture()) {
            System.setProperty("earthplatform.earth.usgsBase", fixture.base());
            CubicSettings.resetSourcesForTesting();
            try { CubicSettings.usgs(UsgsElevation.Reading.values()[0]).refine(12, 700, 1500, new float[256 * 256]); } catch (Exception ignored) { }
            assertTrue(fixture.hits.get() > 0, "usgs() factory actually reached the configured usgsBase() override");
        }
    }

    @Test
    void remaFactoryRoutesThroughRemaBase() throws Exception {
        try (Fixture fixture = new Fixture()) {
            System.setProperty("earthplatform.earth.remaBase", fixture.base());
            CubicSettings.resetSourcesForTesting();
            // zoom 8, y=255 (the southernmost row) sits entirely south of NORTH_LIMIT (-50), squarely in
            // REMA's coverage; a zero-filled values array reads as open sea everywhere (RemaElevation.refine()
            // skips any pixel that already reads exactly 0, same open-sea sentinel UsgsElevation uses), so
            // every pixel is set to a placeholder "land" value first to make refine() actually look REMA up.
            float[] values = new float[256 * 256];
            java.util.Arrays.fill(values, 100f);
            try { CubicSettings.rema().refine(8, 128, 255, values); } catch (Exception ignored) { }
            assertTrue(fixture.hits.get() > 0, "rema() factory actually reached the configured remaBase() override");
        }
    }

    @Test
    void hrdemFactoryRoutesThroughHrdemBase() throws Exception {
        try (Fixture fixture = new Fixture()) {
            System.setProperty("earthplatform.earth.hrdemBase", fixture.base());
            CubicSettings.resetSourcesForTesting();
            // Zoom 14 tile over Toronto (43.65 N, 79.38 W): computed to fall inside HRDEM's own square
            // "8_2" in hrdem-2m-v1.txt (EPSG:3979 x=1,277,733 y=-443,861), one of the real lidar-covered
            // squares - an arbitrary Canada-ish tile outside the actual flown lidar footprint makes
            // HrdemElevation.read()'s touched() list empty and this test a false negative, never reaching
            // the network at all.
            try { CubicSettings.hrdem().refine(14, 4579, 5979, new float[256 * 256]); } catch (Exception ignored) { }
            assertTrue(fixture.hits.get() > 0, "hrdem() factory actually reached the configured hrdemBase() override");
        }
    }

    @Test
    void ignFactoryRoutesThroughIgnBase() throws Exception {
        try (Fixture fixture = new Fixture()) {
            System.setProperty("earthplatform.earth.ignBase", fixture.base());
            CubicSettings.resetSourcesForTesting();
            try { CubicSettings.ign().refine(12, 2100, 1450, new float[256 * 256]); } catch (Exception ignored) { }
            assertTrue(fixture.hits.get() > 0, "ign() factory actually reached the configured ignBase() override");
        }
    }

    @Test
    void gsiFactoryRoutesThroughGsiBase() throws Exception {
        try (Fixture fixture = new Fixture()) {
            System.setProperty("earthplatform.earth.gsiBase", fixture.base());
            CubicSettings.resetSourcesForTesting();
            try { CubicSettings.gsi().refine(12, 3600, 1600, new float[256 * 256]); } catch (Exception ignored) { }
            assertTrue(fixture.hits.get() > 0, "gsi() factory actually reached the configured gsiBase() override");
        }
    }

    @Test
    void canopyFactoryRoutesThroughCanopyBase() throws Exception {
        try (Fixture fixture = new Fixture()) {
            System.setProperty("earthplatform.earth.canopyBase", fixture.base());
            CubicSettings.resetSourcesForTesting();
            try { CubicSettings.canopy().metres(-3.0, -60.0, 0); } catch (Exception ignored) { }
            assertTrue(fixture.hits.get() > 0, "canopy() factory actually reached the configured canopyBase() override");
        }
    }

    @Test
    void eoxFactoryRoutesThroughEoxBaseWhenImageryIsEnabled() throws Exception {
        try (Fixture fixture = new Fixture()) {
            System.setProperty("earthplatform.earth.eoxBase", fixture.base());
            System.setProperty("earthplatform.earth.imagery", "true");
            CubicSettings.resetSourcesForTesting();
            try { CubicSettings.imagery().tile(10, 500, 350); } catch (Exception ignored) { }
            assertTrue(fixture.hits.get() > 0, "imagery() factory actually reached the configured eoxBase() override");
        } finally {
            System.clearProperty("earthplatform.earth.imagery");
        }
    }

    /** A loopback HTTP server that answers every request with a bare 404 and only counts that it was asked. */
    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        final AtomicInteger hits = new AtomicInteger();

        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                hits.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.start();
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }

        @Override public void close() { server.stop(0); }
    }
}
