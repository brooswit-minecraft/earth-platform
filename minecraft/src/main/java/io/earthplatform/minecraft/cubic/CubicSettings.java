package io.earthplatform.minecraft.cubic;

import io.earthplatform.geography.globe.BareEarth;
import io.earthplatform.geography.globe.CanopySource;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.CopernicusFill;
import io.earthplatform.geography.globe.CopernicusSource;
import io.earthplatform.geography.globe.ImagerySource;
import io.earthplatform.geography.globe.ElevationLayer;
import io.earthplatform.geography.globe.GsiElevation;
import io.earthplatform.geography.globe.HrdemElevation;
import io.earthplatform.geography.globe.IgnElevation;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.RemaElevation;
import io.earthplatform.geography.globe.RemaSource;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.UsgsElevation;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.geography.globe.Drainage;
import io.earthplatform.geography.globe.RiverMap;
import io.earthplatform.geography.globe.SurfaceWaterSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthMaps;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.gen.HighlandsGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.earthplatform.minecraft.platform.Loader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

/**
 * Which generator a cubic world was made with, pinned in its folder the first time it loads so
 * saved cubes never meet a different generator. New worlds get the Earth; cubic worlds that
 * already saved cubes before this file existed keep the Highlands test world.
 */
public final class CubicSettings {
    private static final String FILE = "generator.properties";
    /** Elevation sources by a world's {@code earth.elevation} pin, so worlds made with different sources never share tiles. */
    private static final Map<String, TerrariumTileSource> tiles = new HashMap<>();
    private static CopernicusSource copernicus;
    private static RemaElevation rema;
    private static HrdemElevation hrdem;
    private static IgnElevation ign;
    private static GsiElevation gsi;
    /** 3DEP by each way of reading it a world may pin. */
    private static final UsgsElevation[] usgs = new UsgsElevation[UsgsElevation.Reading.values().length];
    private static WorldCoverSource cover;
    private static CanopySource canopy;
    /** The {@code earth.plants} pin of worlds whose cacti, bamboo and cherries grow by ecoregion realm and climate. */
    public static final String PLANT_REALMS = "realms-v1";
    private static BareEarth bareEarth;
    private static ClimateSource climate;
    private static SurfaceWaterSource surfaceWater;
    private static RiverMap rivers, riversAndStreams;
    private static ImagerySource imagery;

    private CubicSettings() {}

    public static CubeGenerator open(Path folder, long seed, Registry<Biome> biomes) {
        Path file = folder.resolve(FILE);
        Properties properties = new Properties();
        try {
            if (Files.isRegularFile(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
            } else {
                if (hasSavedCubes(folder.resolve("cubes"))) properties.setProperty("generator", HighlandsGenerator.ID);
                else earth(properties, EarthSettings.defaults(), "");
                store(folder, properties);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read or pin the cubic world's generator in " + file, failure);
        }
        String id = properties.getProperty("generator", "");
        return switch (id) {
            case HighlandsGenerator.ID -> new HighlandsGenerator(seed, biomes);
            case EarthGenerator.ID -> {
                if (!MercatorGrid.PROJECTION_ID.equals(properties.getProperty("earth.projection")))
                    throw new IllegalStateException("Unsupported Earth projection in " + file);
                EarthSettings settings = new EarthSettings(number(properties, "earth.latitude"), number(properties, "earth.longitude"),
                        number(properties, "earth.metresPerBlock"));
                // Worlds made before land cover keep generating as they did.
                String coverId = properties.getProperty("earth.cover", "");
                if (!coverId.isEmpty() && !WorldCoverSource.DATASET_ID.equals(coverId))
                    throw new IllegalStateException("Unsupported land cover '" + coverId + "' in " + file);
                // Climate came with land cover (worlds made in between read the same dataset).
                String climateId = properties.getProperty("earth.climate", "");
                if (!climateId.isEmpty() && !ClimateSource.DATASET_ID.equals(climateId))
                    throw new IllegalStateException("Unsupported climate '" + climateId + "' in " + file);
                // Worlds made before imagery keep their ground as it was.
                String imageryId = properties.getProperty("earth.imagery", "");
                if (!imageryId.isEmpty() && !ImagerySource.DATASET_ID.equals(imageryId))
                    throw new IllegalStateException("Unsupported imagery '" + imageryId + "' in " + file);
                // Worlds made before narrow rivers keep their land as it was.
                String riversId = properties.getProperty("earth.rivers", "");
                if (!riversId.isEmpty() && !SurfaceWaterSource.DATASET_ID.equals(riversId))
                    throw new IllegalStateException("Unsupported rivers '" + riversId + "' in " + file);
                // Worlds made before streams from the elevation keep only the rivers seen from space.
                String streamsId = properties.getProperty("earth.streams", "");
                if (!streamsId.isEmpty() && !Drainage.ID.equals(streamsId))
                    throw new IllegalStateException("Unsupported streams '" + streamsId + "' in " + file);
                // Worlds made before bare-earth ground keep their towns' roofs as ground.
                String groundId = properties.getProperty("earth.ground", "");
                if (!groundId.isEmpty() && !BareEarth.ID.equals(groundId))
                    throw new IllegalStateException("Unsupported ground '" + groundId + "' in " + file);
                // Worlds made before elevation layers read the composite alone.
                String elevationId = properties.getProperty("earth.elevation", TerrariumTileSource.DATASET_ID);
                // Worlds made before vanilla's caves keep the caves they had, so new cubes meet saved ones.
                String cavesId = properties.getProperty("earth.caves", "");
                if (!cavesId.isEmpty() && !SurfaceGenerator.VANILLA_CAVES.equals(cavesId))
                    throw new IllegalStateException("Unsupported caves '" + cavesId + "' in " + file);
                EarthGenerator earth = new EarthGenerator(seed, biomes, settings, tiles(elevationId), coverId.isEmpty() ? null : cover(), coverId.isEmpty() ? null : climate(),
                        coverId.isEmpty() || imageryId.isEmpty() ? null : imagery(), coverId.isEmpty() || riversId.isEmpty() ? null : streamsId.isEmpty() ? rivers() : riversAndStreams(),
                        coverId.isEmpty() || groundId.isEmpty() ? null : bareEarth());
                if (cavesId.isEmpty()) earth.legacyCaves();
                // Worlds made before build 70 keep the boxes of latitude and longitude that placed their giant trees, red
                // deserts, lava fields, cacti, bamboo and cherries, so new ground matches what they have saved.
                String canopyId = properties.getProperty("earth.canopy", "");
                if (!canopyId.isEmpty() && !CanopySource.DATASET_ID.equals(canopyId))
                    throw new IllegalStateException("Unsupported canopy heights '" + canopyId + "' in " + file);
                String mapsId = properties.getProperty("earth.maps", "");
                if (!mapsId.isEmpty() && !EarthMaps.ID.equals(mapsId))
                    throw new IllegalStateException("Unsupported rock and soil maps '" + mapsId + "' in " + file);
                String plantsId = properties.getProperty("earth.plants", "");
                if (!plantsId.isEmpty() && !PLANT_REALMS.equals(plantsId))
                    throw new IllegalStateException("Unsupported plant ranges '" + plantsId + "' in " + file);
                earth.data(coverId.isEmpty() || canopyId.isEmpty() ? null : canopy(), !mapsId.isEmpty(), !plantsId.isEmpty());
                yield earth;
            }
            default -> throw new IllegalStateException("Unknown cubic generator '" + id + "' in " + file);
        };
    }

    /**
     * Pins a new Earth world's home before its cubic level first loads, naming the place for
     * players; does nothing when the world already has a generator.
     */
    public static void pinEarth(Path folder, EarthSettings earth, String name) {
        if (Files.isRegularFile(folder.resolve(FILE))) return;
        Properties properties = new Properties();
        earth(properties, earth, name);
        try { store(folder, properties); }
        catch (IOException failure) { throw new UncheckedIOException("Could not pin the Earth's home in " + folder, failure); }
    }

    /** The name a world's home was given when it was made, or empty. */
    public static String homeName(Path folder) {
        Path file = folder.resolve(FILE);
        if (!Files.isRegularFile(file)) return "";
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        catch (IOException failure) { return ""; }
        return properties.getProperty("earth.homeName", "");
    }

    private static void earth(Properties properties, EarthSettings earth, String name) {
        properties.setProperty("generator", EarthGenerator.ID);
        properties.setProperty("earth.latitude", Double.toString(earth.latitude()));
        properties.setProperty("earth.longitude", Double.toString(earth.longitude()));
        properties.setProperty("earth.metresPerBlock", Double.toString(earth.metresPerBlock()));
        properties.setProperty("earth.projection", earth.projection());
        properties.setProperty("earth.elevation", elevation());
        properties.setProperty("earth.cover", WorldCoverSource.DATASET_ID);
        properties.setProperty("earth.climate", ClimateSource.DATASET_ID);
        properties.setProperty("earth.rivers", SurfaceWaterSource.DATASET_ID);
        properties.setProperty("earth.streams", Drainage.ID);
        properties.setProperty("earth.ground", BareEarth.ID);
        properties.setProperty("earth.caves", SurfaceGenerator.VANILLA_CAVES);
        if (!"false".equals(System.getProperty("earthplatform.earth.canopy"))) properties.setProperty("earth.canopy", CanopySource.DATASET_ID);
        if (!"false".equals(System.getProperty("earthplatform.earth.maps"))) properties.setProperty("earth.maps", EarthMaps.ID);
        if (!"false".equals(System.getProperty("earthplatform.earth.realms"))) properties.setProperty("earth.plants", PLANT_REALMS);
        if (imageryEnabled()) properties.setProperty("earth.imagery", ImagerySource.DATASET_ID);
        if (!name.isEmpty()) properties.setProperty("earth.homeName", name);
    }

    private static void store(Path folder, Properties properties) throws IOException {
        Files.createDirectories(folder);
        Path temp = folder.resolve(FILE + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            properties.store(writer, "Cubic world generator, fixed when the world was made");
        }
        Files.move(temp, folder.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static double number(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalStateException("Missing " + key + " in the cubic world's " + FILE);
        return Double.parseDouble(value.trim());
    }

    private static boolean hasSavedCubes(Path cubes) throws IOException {
        if (!Files.isDirectory(cubes)) return false;
        try (Stream<Path> files = Files.list(cubes)) { return files.findAny().isPresent(); }
    }

    /** Land cover overview the Copernicus fill finds snow and ice at: pixels about 75 m across, near its check's 120 m. */
    private static final int ICE_OVERVIEW = 3;

    /**
     * The elevation pin for new worlds: the Terrarium composite, then the layers laid over it, joined
     * by '+'. The Copernicus fill, Canada from HRDEM, the United States from 3DEP, Antarctica from REMA,
     * France from IGN and Japan from GSI are on unless the game runs with
     * {@code -Dearthplatform.earth.copernicus=false}, {@code -Dearthplatform.earth.hrdem=false},
     * {@code -Dearthplatform.earth.3dep=false}, {@code -Dearthplatform.earth.rema=false},
     * {@code -Dearthplatform.earth.ign=false} or {@code -Dearthplatform.earth.gsi=false}. Each national
     * source covers only its own country, so their order matters only at a border: 3DEP follows HRDEM,
     * and the United States keeps 3DEP.
     */
    public static String elevation() {
        String pin = TerrariumTileSource.DATASET_ID;
        if (!"false".equals(System.getProperty("earthplatform.earth.copernicus"))) pin += "+" + CopernicusFill.ICE_ID;
        if (!"false".equals(System.getProperty("earthplatform.earth.hrdem"))) pin += "+" + HrdemElevation.DATASET_ID;
        if (!"false".equals(System.getProperty("earthplatform.earth.3dep"))) pin += "+" + UsgsElevation.GRADED_ID;
        if (!"false".equals(System.getProperty("earthplatform.earth.rema"))) pin += "+" + RemaElevation.DATASET_ID;
        if (!"false".equals(System.getProperty("earthplatform.earth.ign"))) pin += "+" + IgnElevation.DATASET_ID;
        if (!"false".equals(System.getProperty("earthplatform.earth.gsi"))) pin += "+" + GsiElevation.DATASET_ID;
        return pin;
    }

    /** Elevation as new worlds read it. */
    public static synchronized TerrariumTileSource tiles() { return tiles(elevation()); }

    /**
     * Where Terrarium elevation tiles are fetched from: {@code -Dearthplatform.earth.terrariumBase}
     * overrides it (for a local loopback cache such as an elevation-cache-proxy), defaulting to
     * {@link TerrariumTileSource#AWS_TERRAIN_TILES} unchanged when unset. Every caller that builds its
     * own {@link TerrariumTileSource} — {@link #tiles(String)} and {@link #riversAndStreams()} alike —
     * reads this, so an override can't be hidden behind one of them still fetching straight from AWS.
     */
    public static URI terrariumBase() {
        String override = System.getProperty("earthplatform.earth.terrariumBase");
        return override == null || override.isEmpty() ? TerrariumTileSource.AWS_TERRAIN_TILES : URI.create(override);
    }

    /**
     * Elevation tiles for an {@code earth.elevation} pin, shared by every Earth world in this game made
     * with the same sources, cached on disk under the game folder.
     * {@code -Dearthplatform.earth.offline=true} reads only that cache.
     */
    public static synchronized TerrariumTileSource tiles(String pin) {
        TerrariumTileSource known = tiles.get(pin);
        if (known != null) return known;
        String[] ids = pin.split("\\+");
        if (!TerrariumTileSource.DATASET_ID.equals(ids[0])) throw new IllegalStateException("Unsupported elevation '" + pin + "'");
        List<ElevationLayer> layers = new ArrayList<>();
        // The Copernicus fill checks the tiles as they were before any layer, so it reads them back
        // from the source made here.
        TerrariumTileSource[] made = new TerrariumTileSource[1];
        for (int i = 1; i < ids.length; i++) {
            if (CopernicusFill.DATASET_ID.equals(ids[i])) layers.add(new CopernicusFill(copernicus(), (zoom, x, y) -> made[0].cleaned(zoom, x, y), 512));
            else if (CopernicusFill.ICE_ID.equals(ids[i])) {
                WorldCoverSource cover = cover();
                layers.add(new CopernicusFill(copernicus(), (zoom, x, y) -> made[0].cleaned(zoom, x, y),
                        (latitude, longitude) -> cover.code(latitude, longitude, ICE_OVERVIEW) == WorldCoverSource.SNOW_AND_ICE, 512));
            }
            else if (UsgsElevation.Reading.of(ids[i]) != null) layers.add(usgs(UsgsElevation.Reading.of(ids[i])));
            else if (RemaElevation.DATASET_ID.equals(ids[i])) layers.add(rema());
            else if (HrdemElevation.DATASET_ID.equals(ids[i])) layers.add(hrdem());
            else if (IgnElevation.DATASET_ID.equals(ids[i])) layers.add(ign());
            else if (GsiElevation.DATASET_ID.equals(ids[i])) layers.add(gsi());
            else throw new IllegalStateException("Unsupported elevation layer '" + ids[i] + "' in '" + pin + "'");
        }
        Path cache = cacheFolder().resolve(TerrariumTileSource.DATASET_ID.toLowerCase(Locale.ROOT));
        try {
            // Far terrain over a new area reads several hundred tiles at several zooms; with fewer kept than
            // that, a first visit cleaned the same tiles five or six times over. Each is 256 KiB: up to a
            // sixteenth of the heap, from 256 to 1,024 of them. Fetching them is mostly a wait on the round
            // trip, so many go at once.
            known = new TerrariumTileSource(terrariumBase(), cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                    tilesKept(), transfers(16, 48), Duration.ofSeconds(20), layers);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not open the elevation tile cache at " + cache, failure);
        }
        made[0] = known;
        tiles.put(pin, known);
        return known;
    }

    /**
     * Copernicus GLO-30, for the fill of places where the elevation tiles are wrong over a wide area:
     * fjords filled in as land, and heights off the sea's datum north of SRTM. Cached on disk beside
     * the elevation tiles; its blocks are up to 4 MiB decoded.
     */
    public static synchronized CopernicusSource copernicus() {
        if (copernicus == null) {
            Path cache = cacheFolder().resolve(CopernicusSource.DATASET_ID);
            try {
                copernicus = new CopernicusSource(CopernicusSource.AWS_COPERNICUS, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        12, transfers(8, 16), Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the Copernicus elevation cache at " + cache, failure);
            }
        }
        return copernicus;
    }

    /** The United States from 3DEP, cached on disk beside the elevation tiles; both ways of reading it share the cache. */
    public static synchronized UsgsElevation usgs(UsgsElevation.Reading reading) {
        int at = reading.ordinal();
        if (usgs[at] == null) {
            Path cache = cacheFolder().resolve(UsgsElevation.DATASET_ID);
            try {
                usgs[at] = new UsgsElevation(UsgsElevation.AWS_3DEP, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        blocksKept(), transfers(6, 24), Duration.ofSeconds(30), reading);
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the 3DEP elevation cache at " + cache, failure);
            }
        }
        return usgs[at];
    }

    /**
     * Transfers at once from Amazon S3's open data buckets (elevation tiles, 3DEP, Copernicus, land cover, HRDEM,
     * REMA), which serve thousands of requests a second. A piece is mostly a wait on the round trip, some 0.1 to
     * 0.25 s on a home line, so the few at a time of before set how long a travel took: on a first visit to Mount
     * Rainier the 100 pieces of 3DEP, six at a time, were close to four of the travel's four and a half seconds
     * on their own. A host that asks for less is still obeyed (see HostBackoff).
     * {@code -Dearthplatform.earth.transfers=few} keeps the few of before, for comparing. The climate and surface
     * water hosts, which are not Amazon's, keep theirs.
     */
    static int transfers(int few, int many) { return "few".equals(System.getProperty("earthplatform.earth.transfers")) ? few : many; }

    /**
     * Decoded 3DEP and REMA blocks kept in memory. A zoom-15 tile needs one to four, but far terrain,
     * the water surveys and the cone search read the same coarse blocks again and again across the
     * far range, and with only 48 kept a block was decoded two or three times over. Blocks are up
     * to 1 MiB: a thirty-second of the heap, from 48 to 256 of them.
     */
    static int tilesKept() { return (int) Math.clamp(Runtime.getRuntime().maxMemory() / 16 / (256 << 10), 256, 1024); }
    static int blocksKept() { return (int) Math.clamp(Runtime.getRuntime().maxMemory() / 32 / (1 << 20), 48, 256); }

    /** Antarctica from REMA, cached on disk beside the elevation tiles. */
    public static synchronized RemaElevation rema() {
        if (rema == null) {
            Path cache = cacheFolder().resolve(RemaSource.DATASET_ID);
            try {
                rema = new RemaElevation(new RemaSource(RemaSource.AWS_REMA, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        blocksKept(), transfers(6, 24), Duration.ofSeconds(30)));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the REMA elevation cache at " + cache, failure);
            }
        }
        return rema;
    }

    /** Canada from HRDEM lidar, cached on disk beside the elevation tiles. */
    public static synchronized HrdemElevation hrdem() {
        if (hrdem == null) {
            Path cache = cacheFolder().resolve(HrdemElevation.DATASET_ID);
            try {
                hrdem = new HrdemElevation(HrdemElevation.AWS_HRDEM, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        blocksKept(), transfers(6, 24), Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the HRDEM elevation cache at " + cache, failure);
            }
        }
        return hrdem;
    }

    /**
     * France from IGN, cached on disk beside the elevation tiles. Its server works each tile out on
     * request, a second or so apiece, so several go at once.
     */
    public static synchronized IgnElevation ign() {
        if (ign == null) {
            Path cache = cacheFolder().resolve(IgnElevation.DATASET_ID);
            try {
                ign = new IgnElevation(IgnElevation.IGN_WMS, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        64, 12, Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the IGN elevation cache at " + cache, failure);
            }
        }
        return ign;
    }

    /** Japan from GSI, cached on disk beside the elevation tiles. Its server is far from most players, so many tiles go at once. */
    public static synchronized GsiElevation gsi() {
        if (gsi == null) {
            Path cache = cacheFolder().resolve(GsiElevation.DATASET_ID);
            try {
                gsi = new GsiElevation(GsiElevation.GSI_TILES, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        64, 16, Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the GSI elevation cache at " + cache, failure);
            }
        }
        return gsi;
    }

    private static Path cacheFolder() { return Loader.INSTANCE.gameDir().resolve("earthplatform").resolve("cache"); }

    /** Bare-earth ground shared by every Earth world in this game, its worked-out fields cached on disk beside the elevation tiles. */
    public static synchronized BareEarth bareEarth() {
        if (bareEarth == null)
            bareEarth = new BareEarth(tiles(), cover(), cacheFolder().resolve(BareEarth.ID));
        return bareEarth;
    }

    /** Canopy heights shared by every Earth world in this game, cached on disk beside the elevation tiles. */
    public static synchronized CanopySource canopy() {
        if (canopy == null) {
            Path cache = cacheFolder().resolve(CanopySource.DATASET_ID);
            try {
                // Each block is 256 KiB and covers 10 km of trees at the detail they are read at.
                canopy = new CanopySource(CanopySource.AWS_CANOPY, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        24, 4, Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the canopy height cache at " + cache, failure);
            }
        }
        return canopy;
    }

    /**
     * Where ESA WorldCover tiles are fetched from: {@code -Dearthplatform.earth.worldCoverBase}
     * overrides it (for a local loopback cache such as a worldcover-cache-proxy), defaulting to
     * {@link WorldCoverSource#AWS_WORLDCOVER} unchanged when unset. {@link #cover()} is this
     * source's one call site, so an override can't be hidden behind another path still fetching
     * straight from the bucket.
     */
    public static URI worldCoverBase() {
        String override = System.getProperty("earthplatform.earth.worldCoverBase");
        return override == null || override.isEmpty() ? WorldCoverSource.AWS_WORLDCOVER : URI.create(override);
    }

    /**
     * Whether the configured land-cover proxy at {@link #worldCoverBase()} answers its own dedicated,
     * uncached {@code /__health} endpoint with exactly HTTP 200 - a live reachability probe, kept
     * deliberately separate from {@link #cover()}'s own tile cache so neither a stale cached success
     * hides a real outage here, nor a bad probe ever evicts or poisons that cache. When
     * worldCoverBase() is unset (the real AWS bucket, unchanged), there is no dedicated endpoint to
     * probe, so this reports healthy without a network call - retaining existing behavior.
     */
    public static boolean worldCoverHealthy() {
        URI base = worldCoverBase();
        return base.equals(WorldCoverSource.AWS_WORLDCOVER) || dedicatedHealthCheck(base);
    }

    /**
     * Cached, rate-limited, asynchronous reachability of the configured land-cover proxy's dedicated
     * endpoint: never blocks the calling thread, and re-probes {@link #worldCoverHealthy()} only
     * once {@code -Dearthplatform.earth.dedicatedHealthProbeMs} (15 s by default) has passed since the
     * last one finished. True, with no probe scheduled, when worldCoverBase() is unset.
     */
    public static boolean worldCoverReachable() {
        URI base = worldCoverBase();
        return base.equals(WorldCoverSource.AWS_WORLDCOVER) || worldCoverProbe.reachable(CubicSettings::worldCoverHealthy);
    }

    /** Land cover shared by every Earth world in this game, cached on disk beside the elevation tiles. */
    public static synchronized WorldCoverSource cover() {
        if (cover == null) {
            Path cache = cacheFolder().resolve(WorldCoverSource.DATASET_ID);
            try {
                cover = new WorldCoverSource(worldCoverBase(), cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        48, transfers(8, 24), Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the land cover cache at " + cache, failure);
            }
        }
        return cover;
    }

    /** Narrow rivers seen from space, for worlds made before streams from the elevation. */
    public static synchronized RiverMap rivers() {
        // Each traced square keeps 64 KiB.
        if (rivers == null) rivers = new RiverMap(surfaceWater(), 256);
        return rivers;
    }

    /**
     * Narrow rivers seen from space and the streams too narrow for that, which the elevation's
     * valleys carry where the climate is wet enough, shared by every Earth world in this game.
     */
    public static synchronized RiverMap riversAndStreams() {
        if (riversAndStreams == null) {
            Path caches = cacheFolder();
            boolean network = !Boolean.getBoolean("earthplatform.earth.offline");
            try {
                // Elevation and rain of their own, the composite alone and the rainfall rows alone: a first visit
                // then downloads what streams need beside the terrain's own, never queued behind it. Each drained
                // tile keeps 320 KiB. Rain reads climateBase() - the same configured climate proxy climate() uses -
                // but keeps its own separate ClimateSource instance, cache and transfer queue; the two consumers
                // are never merged into one, per John's 55858 acceptance note.
                Path tiles = caches.resolve(TerrariumTileSource.DATASET_ID.toLowerCase(Locale.ROOT));
                TerrariumTileSource elevation = new TerrariumTileSource(terrariumBase(), tiles, network, 64, 16, Duration.ofSeconds(20));
                ClimateSource rain = new ClimateSource(climateBase(), caches.resolve(ClimateSource.DATASET_ID), network, 32, 8, Duration.ofSeconds(30));
                riversAndStreams = new RiverMap(surfaceWater(), new Drainage(elevation, rain::precipitation, 24), 256);
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the stream caches under " + caches, failure);
            }
        }
        return riversAndStreams;
    }

    /**
     * Where JRC surface water tiles are fetched from: {@code -Dearthplatform.earth.surfaceWaterBase}
     * overrides it (for a local loopback cache such as a surface-water-cache-proxy), defaulting to
     * {@link SurfaceWaterSource#JRC_GSW} unchanged when unset. {@link #surfaceWater()} is this
     * source's one call site, so an override can't be hidden behind another path still fetching
     * straight from Copernicus.
     */
    public static URI surfaceWaterBase() {
        String override = System.getProperty("earthplatform.earth.surfaceWaterBase");
        return override == null || override.isEmpty() ? SurfaceWaterSource.JRC_GSW : URI.create(override);
    }

    /**
     * Whether the configured surface-water proxy at {@link #surfaceWaterBase()} answers its own
     * dedicated, uncached {@code /__health} endpoint with exactly HTTP 200 - a live reachability
     * probe, kept deliberately separate from {@link #surfaceWater()}'s own tile cache so neither a
     * stale cached success hides a real outage here, nor a bad probe ever evicts or poisons that
     * cache. When surfaceWaterBase() is unset (the real Copernicus host, unchanged), there is no
     * dedicated endpoint to probe, so this reports healthy without a network call - retaining
     * existing behavior.
     */
    public static boolean surfaceWaterHealthy() {
        URI base = surfaceWaterBase();
        return base.equals(SurfaceWaterSource.JRC_GSW) || dedicatedHealthCheck(base);
    }

    /**
     * Cached, rate-limited, asynchronous reachability of the configured surface-water proxy's
     * dedicated endpoint: never blocks the calling thread, and re-probes
     * {@link #surfaceWaterHealthy()} only once {@code -Dearthplatform.earth.dedicatedHealthProbeMs}
     * (15 s by default) has passed since the last one finished. True, with no probe scheduled, when
     * surfaceWaterBase() is unset.
     */
    public static boolean surfaceWaterReachable() {
        URI base = surfaceWaterBase();
        return base.equals(SurfaceWaterSource.JRC_GSW) || surfaceWaterProbe.reachable(CubicSettings::surfaceWaterHealthy);
    }

    /**
     * A live, uncached GET to base's own {@code __health} endpoint, as {@link #worldCoverHealthy()}
     * and {@link #surfaceWaterHealthy()} share: healthy means exactly HTTP 200, the documented
     * success both proxies' dedicated endpoints report - everything else (a different status, a
     * timeout, a connection failure) is unhealthy, never silently treated as reachable.
     */
    private static boolean dedicatedHealthCheck(URI base) {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
            HttpRequest request = HttpRequest.newBuilder(base.resolve("__health"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException | InterruptedException failure) {
            return false;
        }
    }

    /**
     * Rate-limited, asynchronous wrapper around one source's dedicated health check: at most one
     * probe in flight at a time, started off the caller's thread no more often than
     * {@code -Dearthplatform.earth.dedicatedHealthProbeMs} (15 s by default) apart, with every call
     * in between returning the last known answer (true until the first probe finishes) without
     * blocking or making a network call itself.
     */
    private static final class DedicatedProbe {
        private volatile boolean reachable = true;
        private volatile long nextProbe;
        private volatile boolean probing;

        synchronized boolean reachable(java.util.function.BooleanSupplier check) {
            long now = System.currentTimeMillis();
            if (!probing && now >= nextProbe) {
                probing = true;
                long intervalMs = Long.getLong("earthplatform.earth.dedicatedHealthProbeMs", 15_000);
                CompletableFuture.supplyAsync(check::getAsBoolean).whenComplete((result, failure) -> {
                    reachable = failure == null && result;
                    nextProbe = System.currentTimeMillis() + intervalMs;
                    probing = false;
                });
            }
            return reachable;
        }

        synchronized void resetForTesting() {
            reachable = true;
            nextProbe = 0;
            probing = false;
        }
    }

    private static final DedicatedProbe worldCoverProbe = new DedicatedProbe();
    private static final DedicatedProbe surfaceWaterProbe = new DedicatedProbe();
    private static final DedicatedProbe climateProbe = new DedicatedProbe();

    /** Test-only: clears worldCoverReachable()/surfaceWaterReachable()/climateReachable()'s cached state and rate limit. */
    static void resetDedicatedProbesForTesting() {
        worldCoverProbe.resetForTesting();
        surfaceWaterProbe.resetForTesting();
        climateProbe.resetForTesting();
    }

    /**
     * Test-only: discards the memoized climate()/riversAndStreams()/rivers()/surfaceWater() instances,
     * so a later call reconstructs with whatever override system properties a test has since set,
     * rather than reusing a singleton built under an earlier test's configuration.
     */
    static void resetSourcesForTesting() {
        climate = null;
        rivers = null;
        riversAndStreams = null;
        surfaceWater = null;
    }

    /** Surface water occurrence, cached on disk beside the elevation tiles. */
    private static synchronized SurfaceWaterSource surfaceWater() {
        if (surfaceWater == null) {
            Path cache = cacheFolder().resolve(SurfaceWaterSource.DATASET_ID);
            try {
                // Blocks are 64 KiB decoded and cover 7 km.
                surfaceWater = new SurfaceWaterSource(surfaceWaterBase(), cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        96, 4, Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the surface water cache at " + cache, failure);
            }
        }
        return surfaceWater;
    }

    /**
     * Where CHELSA climate tiles are fetched from: {@code -Dearthplatform.earth.climateBase}
     * overrides it (for a local loopback cache such as a chelsa-cache-proxy), defaulting to
     * {@link ClimateSource#CHELSA} unchanged when unset. Both {@link #climate()} (terrain) and
     * {@link #riversAndStreams()} (drainage rainfall) read this, each keeping its own separate
     * ClimateSource instance/cache/transfer queue - an override can't be hidden behind either one
     * still fetching straight from CHELSA.
     */
    public static URI climateBase() {
        String override = System.getProperty("earthplatform.earth.climateBase");
        return override == null || override.isEmpty() ? ClimateSource.CHELSA : URI.create(override);
    }

    /**
     * Whether the configured climate proxy at {@link #climateBase()} answers its own dedicated,
     * uncached {@code /__health} endpoint with exactly HTTP 200 - a live reachability probe, kept
     * deliberately separate from {@link #climate()}/{@link #riversAndStreams()}'s own tile caches
     * so neither a stale cached success hides a real outage here, nor a bad probe ever evicts or
     * poisons either cache. When climateBase() is unset (the real CHELSA host, unchanged), there is
     * no dedicated endpoint to probe, so this reports healthy without a network call - retaining
     * existing behavior.
     */
    public static boolean climateHealthy() {
        URI base = climateBase();
        return base.equals(ClimateSource.CHELSA) || dedicatedHealthCheck(base);
    }

    /**
     * Cached, rate-limited, asynchronous reachability of the configured climate proxy's dedicated
     * endpoint: never blocks the calling thread, and re-probes {@link #climateHealthy()} only once
     * {@code -Dearthplatform.earth.dedicatedHealthProbeMs} (15 s by default) has passed since the
     * last one finished. True, with no probe scheduled, when climateBase() is unset.
     */
    public static boolean climateReachable() {
        URI base = climateBase();
        return base.equals(ClimateSource.CHELSA) || climateProbe.reachable(CubicSettings::climateHealthy);
    }

    /** Climate shared by every Earth world in this game, cached on disk beside the elevation tiles. */
    public static synchronized ClimateSource climate() {
        if (climate == null) {
            Path cache = cacheFolder().resolve(ClimateSource.DATASET_ID);
            try {
                // Rows are about 86 KiB; a 48 km square needs about 210 of them. Its elevation comes in
                // about 50 small reads, so more of them run at once.
                climate = new ClimateSource(climateBase(), cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        320, 8, Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the climate cache at " + cache, failure);
            }
        }
        return climate;
    }

    /**
     * Whether new Earth worlds are made with satellite imagery: the "satellite imagery" setting
     * (off by default, as EOX's tile service is not cleared for public use), which {@code EarthConfig}
     * passes on as {@code earthplatform.earth.imagery}, or that property given on the command line.
     * A world keeps what it was made with, so its ground never changes partway.
     */
    public static boolean imageryEnabled() {
        return Boolean.getBoolean("earthplatform.earth.imagery");
    }

    /** Satellite imagery shared by every Earth world in this game, cached on disk beside the elevation tiles. */
    public static synchronized ImagerySource imagery() {
        if (imagery == null) {
            Path cache = cacheFolder().resolve(ImagerySource.DATASET_ID);
            try {
                // Tiles are 256 KiB decoded; a 3 km square at full detail needs about 25 of them.
                imagery = new ImagerySource(ImagerySource.EOX_S2_CLOUDLESS_2016, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        192, 6, Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the imagery cache at " + cache, failure);
            }
        }
        return imagery;
    }
}
