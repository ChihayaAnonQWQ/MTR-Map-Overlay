package com.lx862.mtrmap;

import com.lx862.mtrmap.mapdata.MapDataBuilder;
import com.lx862.mtrmap.mapdata.MapLandmark;
import com.lx862.mtrmap.mapdata.MapRoute;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.Platform;
import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.SerializedDataBase;
import mtr.data.Siding;
import mtr.data.Station;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Tuple;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generates a small MTR network into the TestWorld save so the unified mod
 * can be verified visually in a real game (route snapping + parallel lanes).
 *
 * <p>Runs as a normal JUnit test but writes into {@code run/saves/TestWorld}.
 * Re-run it whenever the test world needs to be (re)populated.</p>
 *
 * <p><b>MTR 3 notes.</b> MTR 3 has no platform independent core: its data lives
 * in the per-level {@code RailwayData} SavedData, which can only be constructed
 * from a live {@code ServerLevel} (its constructor resolves
 * {@code level.dimension()} and the server's world path) and whose writer,
 * {@code RailwayDataFileSaveModule}, therefore cannot be called headlessly.
 * The same files that module writes and that {@code RailwayData.load} reads back
 * are written directly here instead:</p>
 * <ul>
 *   <li>one message-pack map per object under
 *       {@code <world>/mtr/<namespace>/<path>/<collection>/<id % 100>/<id>};</li>
 *   <li>an empty {@code data/mtr_train_data.dat}, because Minecraft only runs the
 *       SavedData load function when that file already exists - without it MTR
 *       would create a fresh {@code RailwayData} and never read the files.</li>
 * </ul>
 *
 * <p>MTR 3 also generates a depot's driving path in game
 * ({@code Depot.generateMainRoute} needs the running server and level) and
 * validates rails against real rail node blocks, so this generator produces the
 * network <em>data</em>; routes without a generated path are snapped onto the
 * rails by {@code RoutePathfinder}, which is what the map draws for this world.</p>
 */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "mtrmap.generateTestWorld", matches = "true")
class TestWorldGeneratorTest {

    private static final Path WORLD_MTR = Path.of(System.getProperty("mtrmap.testWorldMtrPath", "run/saves/TestWorld/mtr"));
    /** MTR 3 keeps one data set per dimension: {@code <world>/mtr/<namespace>/<path>}. */
    private static final Path DIMENSION_MTR = WORLD_MTR.resolve("minecraft/overworld");
    /** SavedData file that makes MTR run {@code RailwayData.load()} on world load. */
    private static final Path SAVED_DATA = WORLD_MTR.getParent().resolve("data").resolve("mtr_train_data.dat");

    private static final long ALPHA_PLATFORM_ID = 1;
    private static final long BRAVO_PLATFORM_ID = 2;
    private static final long CHARLIE_PLATFORM_ID = 3;
    private static final long SIDING_ID = 4;
    private static final long ALPHA_STATION_ID = 11;
    private static final long BRAVO_STATION_ID = 12;
    private static final long CHARLIE_STATION_ID = 13;
    private static final long EXPRESS_ROUTE_ID = 21;
    private static final long LOCAL_ROUTE_ID = 22;
    private static final long LOCAL_RETURN_ROUTE_ID = 23;
    private static final long DEPOT_ID = 31;
    private static final int PLATFORM_COLOR = 7829367;

    private static void deleteRecursively(java.io.File file) {
        final java.io.File[] children = file.listFiles();
        if (children != null) {
            for (java.io.File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static BlockPos node(int x, int z) {
        return new BlockPos(x, 64, z);
    }

    /**
     * MTR 3 takes a plain constructor instead of MTR 4's {@code Rail.newRail}
     * factory. Its facing convention, verified against the real rail class, is:
     * the start facing is the travel direction at the start, the end facing is
     * the <em>reverse</em> of the travel direction at the end. A straight rail is
     * therefore {@code E -> W}; a quarter turn from heading east to heading south
     * is {@code E -> N}, which MTR fits as an arc (the same shape the in-game rail
     * tool produces).
     */
    private static Rail makeRail(BlockPos start, RailAngle startFacing, BlockPos end, RailAngle endFacing,
            RailType railType) {
        final Rail rail = new Rail(start, startFacing, end, endFacing, railType, TransportMode.TRAIN);
        if (!rail.isValid()) {
            throw new IllegalStateException("rail invalid: " + start + " -> " + end);
        }
        return rail;
    }

    /**
     * Registers one rail in both directions, like MTR's own node index does. A
     * non-zero {@code savedRailId} makes {@link RailwayData#addRail} create the
     * platform (or siding) that belongs to a PLATFORM/SIDING rail; the reverse
     * direction passes 0 so no second saved rail is created.
     */
    private static void addRailPair(Map<BlockPos, Map<BlockPos, Rail>> rails, Set<Platform> platforms,
            Set<Siding> sidings, BlockPos start, RailAngle startFacing, BlockPos end, RailAngle endFacing,
            RailType railType, long savedRailId) {
        RailwayData.addRail(rails, platforms, sidings, TransportMode.TRAIN, start, end,
                makeRail(start, startFacing, end, endFacing, railType), savedRailId);
        RailwayData.addRail(rails, platforms, sidings, TransportMode.TRAIN, end, start,
                makeRail(end, endFacing, start, startFacing, railType), 0L);
    }

    /** The platform MTR created for a platform rail, ready for its display name. */
    private static Platform platform(Set<Platform> platforms, long id, String name) {
        final Platform platform = platforms.stream().filter(candidate -> candidate.id == id).findFirst()
                .orElseThrow(() -> new IllegalStateException("platform " + id + " was not created"));
        platform.name = name;
        platform.color = PLATFORM_COLOR;
        return platform;
    }

    private static Siding siding(Set<Siding> sidings, long id, String name) {
        final Siding siding = sidings.stream().filter(candidate -> candidate.id == id).findFirst()
                .orElseThrow(() -> new IllegalStateException("siding " + id + " was not created"));
        siding.name = name;
        return siding;
    }

    /** MTR 3 has no JSON data constructor: names, colours and area corners are public fields. */
    private static Station station(long id, String name, int color, BlockPos corner1, BlockPos corner2) {
        final Station station = new Station(id);
        station.name = name;
        station.color = color;
        station.corner1 = new Tuple<>(corner1.getX(), corner1.getZ());
        station.corner2 = new Tuple<>(corner2.getX(), corner2.getZ());
        return station;
    }

    /** MTR 3 keeps route stops as {@code Route.RoutePlatform} entries carrying the platform id. */
    private static Route route(long id, String name, int color, List<Platform> stops) {
        final Route route = new Route(id, TransportMode.TRAIN);
        route.name = name;
        route.color = color;
        for (Platform platform : stops) {
            route.platformIds.add(new Route.RoutePlatform(platform.id));
        }
        if (route.platformIds.size() != stops.size()) {
            throw new IllegalStateException("route platform data not applied for " + name);
        }
        return route;
    }

    private static Depot depot(long id, String name, int color, BlockPos corner1, BlockPos corner2, List<Route> routes) {
        final Depot depot = new Depot(id, TransportMode.TRAIN);
        depot.name = name;
        depot.color = color;
        depot.corner1 = new Tuple<>(corner1.getX(), corner1.getZ());
        depot.corner2 = new Tuple<>(corner2.getX(), corner2.getZ());
        for (Route route : routes) {
            depot.routeIds.add(route.id);
        }
        return depot;
    }

    /** MTR writes one message-pack file per object: {@code <collection>/<id % 100>/<id>}. */
    private static <T extends SerializedDataBase> void writeDataFiles(Path folder, Collection<T> data,
            ToLongFunction<T> idOf) throws IOException {
        for (T element : data) {
            final long id = idOf.applyAsLong(element);
            final Path directory = folder.resolve(Long.toString(id % 100));
            Files.createDirectories(directory);
            try (MessagePacker packer = MessagePack.newDefaultPacker(
                    Files.newOutputStream(directory.resolve(Long.toString(id))))) {
                packer.packMapHeader(element.messagePackLength());
                element.toMessagePack(packer);
            }
        }
    }

    /**
     * MTR stores the rail network as one file per node, keyed by
     * {@code BlockPos.asLong()}, listing that node's connections.
     */
    private static void writeRailFiles(Path folder, Map<BlockPos, Map<BlockPos, Rail>> rails) throws IOException {
        for (Map.Entry<BlockPos, Map<BlockPos, Rail>> entry : rails.entrySet()) {
            final long nodeId = entry.getKey().asLong();
            final Path directory = folder.resolve(Long.toString(nodeId % 100));
            Files.createDirectories(directory);
            try (MessagePacker packer = MessagePack.newDefaultPacker(
                    Files.newOutputStream(directory.resolve(Long.toString(nodeId))))) {
                packer.packMapHeader(2);
                packer.packString("node_pos").packLong(nodeId);
                packer.packString("rail_connections").packArrayHeader(entry.getValue().size());
                for (Map.Entry<BlockPos, Rail> connection : entry.getValue().entrySet()) {
                    packer.packMapHeader(connection.getValue().messagePackLength() + 1);
                    packer.packString("node_pos").packLong(connection.getKey().asLong());
                    connection.getValue().toMessagePack(packer);
                }
            }
        }
    }

    @Test
    void generateNetwork() throws IOException {
        // IMPORTANT: start from a clean save: stale files would leak old ids into the data set.
        deleteRecursively(WORLD_MTR.toFile());
        Files.deleteIfExists(SAVED_DATA);

        // Corridor: east along z=0, quarter turn south, south, quarter turn west, then west.
        final BlockPos n0 = node(0, 0);
        final BlockPos n1 = node(80, 0);
        final BlockPos n2 = node(160, 80);
        final BlockPos n3 = node(160, 180);
        final BlockPos n4 = node(80, 260);
        final BlockPos n5 = node(-40, 260);
        final BlockPos n6 = node(-160, 260);

        final Map<BlockPos, Map<BlockPos, Rail>> rails = new LinkedHashMap<>();
        final Set<Platform> platforms = new LinkedHashSet<>();
        final Set<Siding> sidings = new LinkedHashSet<>();

        // Rails: platform rails at each station, quarter-arc connectors, then a siding
        addRailPair(rails, platforms, sidings, n0, RailAngle.E, n1, RailAngle.W, RailType.PLATFORM,
                ALPHA_PLATFORM_ID);
        addRailPair(rails, platforms, sidings, n1, RailAngle.E, n2, RailAngle.N, RailType.IRON, 0L);
        addRailPair(rails, platforms, sidings, n2, RailAngle.S, n3, RailAngle.N, RailType.PLATFORM,
                BRAVO_PLATFORM_ID);
        addRailPair(rails, platforms, sidings, n3, RailAngle.S, n4, RailAngle.E, RailType.IRON, 0L);
        addRailPair(rails, platforms, sidings, n4, RailAngle.W, n5, RailAngle.E, RailType.PLATFORM,
                CHARLIE_PLATFORM_ID);
        addRailPair(rails, platforms, sidings, n5, RailAngle.W, n6, RailAngle.E, RailType.SIDING, SIDING_ID);

        // Platforms and the siding are created by addRail from the saved rail's endpoints
        final Platform alpha = platform(platforms, ALPHA_PLATFORM_ID, "Alpha Platform");
        final Platform bravo = platform(platforms, BRAVO_PLATFORM_ID, "Bravo Platform");
        final Platform charlie = platform(platforms, CHARLIE_PLATFORM_ID, "Charlie Platform");
        final Siding siding = siding(sidings, SIDING_ID, "Test Siding");

        // Stations covering their platforms (for JourneyMap landmarks + waypoints)
        final Set<Station> stations = new LinkedHashSet<>();
        stations.add(station(ALPHA_STATION_ID, "Alpha", 15073280, node(-20, -20), node(100, 20)));
        stations.add(station(BRAVO_STATION_ID, "Bravo", 28440, node(140, 60), node(180, 200)));
        stations.add(station(CHARLIE_STATION_ID, "Charlie", 22016, node(-60, 240), node(100, 280)));

        // Routes: Express (Alpha->Bravo), Local (Alpha->Bravo->Charlie), Local Return
        final Set<Route> routes = new LinkedHashSet<>();
        routes.add(route(EXPRESS_ROUTE_ID, "Express", 15073280, List.of(alpha, bravo)));
        final Route local = route(LOCAL_ROUTE_ID, "Local", 28440, List.of(alpha, bravo, charlie));
        routes.add(local);
        routes.add(route(LOCAL_RETURN_ROUTE_ID, "Local Return", 28440, List.of(charlie, bravo, alpha)));

        final Set<Depot> depots = new LinkedHashSet<>();
        final Depot depot = depot(DEPOT_ID, "Test Depot", 28440, node(-170, 240), node(-20, 280), List.of(local));
        depots.add(depot);

        // The network must be valid MTR 3 data before it is written out
        assertFalse(rails.isEmpty());
        for (Map<BlockPos, Rail> connections : rails.values()) {
            for (Rail rail : connections.values()) {
                assertTrue(rail.isValid(), "generated rails must be valid MTR 3 rails");
                assertTrue(rail.getLength() > 0, "generated rails must have geometry");
            }
        }
        for (Platform platform : platforms) {
            assertFalse(platform.isInvalidSavedRail(rails),
                    "every platform must own its platform rail: " + platform.name);
        }

        System.out.println("[TestWorldGenerator] rail nodes=" + rails.size()
                + " platforms=" + platforms.size() + " sidings=" + sidings.size()
                + " stations=" + stations.size() + " routes=" + routes.size() + " depots=" + depots.size()
                + " positionsToRailContainsN0=" + rails.containsKey(n0));
        for (Platform platform : platforms) {
            System.out.println("[TestWorldGenerator]   platform id=" + platform.id + " name=" + platform.name
                    + " midPos=" + platform.getMidPos() + " invalidSavedRail=" + platform.isInvalidSavedRail(rails));
        }
        System.out.println("[TestWorldGenerator]   siding id=" + siding.id + " railLength=" + siding.railLength);
        // MTR 3: Depot.generateMainRoute(MinecraftServer, Level, ...) drives MTR's own route generation and
        // needs a live server, so the driving path is generated in game; without one the map snaps the route
        // onto the rail graph with RoutePathfinder, which is exactly what the TestWorld is meant to show.
        System.out.println("[TestWorldGenerator]   depot id=" + depot.id + " routeIds=" + depot.routeIds);

        // Exercise the ported map layer with the generated network (no game required)
        final DataCache dataCache = new DataCache(stations, platforms, sidings, routes, depots, Set.of());
        dataCache.sync();
        final MapDataBuilder.Input input = new MapDataBuilder.Input();
        input.dataCache = dataCache;
        input.routes = routes;
        input.stations = stations;
        input.depots = depots;
        input.rails = rails;
        input.fallbackPathfinding = true;
        final MapDataBuilder.Result result = MapDataBuilder.build(input);
        System.out.println("[TestWorldGenerator] map layers: tracks=" + result.tracks.size()
                + " routes=" + result.routes.size() + " landmarks=" + result.landmarks.size());
        for (MapRoute route : result.routes) {
            System.out.println("[TestWorldGenerator]   route " + route.name
                    + " stops=" + route.stops.size() + " tracks=" + route.trackIds.size());
        }
        for (MapLandmark landmark : result.landmarks) {
            System.out.println("[TestWorldGenerator]   landmark " + landmark.type() + " " + landmark.name()
                    + " @" + landmark.x() + "," + landmark.y() + "," + landmark.z()
                    + " routes=" + landmark.hasRoutes());
        }

        // MTR 3's save layout: one message-pack file per object plus the SavedData marker below
        Files.createDirectories(DIMENSION_MTR);
        writeDataFiles(DIMENSION_MTR.resolve("stations"), stations, entry -> entry.id);
        writeDataFiles(DIMENSION_MTR.resolve("platforms"), platforms, entry -> entry.id);
        // The lambda parameter must not shadow the local variables above.
        writeDataFiles(DIMENSION_MTR.resolve("sidings"), sidings, entry -> entry.id);
        writeDataFiles(DIMENSION_MTR.resolve("routes"), routes, entry -> entry.id);
        writeDataFiles(DIMENSION_MTR.resolve("depots"), depots, entry -> entry.id);
        writeRailFiles(DIMENSION_MTR.resolve("rails"), rails);

        // Minecraft only calls the SavedData load function when this file exists; the empty tag makes
        // RailwayData.load() run, which then reads the message-pack files written above.
        Files.createDirectories(SAVED_DATA.getParent());
        NbtIo.writeCompressed(new CompoundTag(), SAVED_DATA.toFile());

        // The data must survive MTR's own codec round trip, otherwise the files above would only
        // contain in-memory shells.
        final Rail original = rails.get(n0).get(n1);
        final Rail restored = new Rail(stringValueMap(pack(original)));
        assertEquals(original.getLength(), restored.getLength(), 1.0E-6,
                "rail geometry must survive MTR 3's message-pack round trip");
        assertTrue(restored.isValid(), "a deserialised rail must still be a valid rail");

        System.out.println("[TestWorldGenerator] MTR 3 world data written to " + DIMENSION_MTR.toAbsolutePath());
        System.out.println("[TestWorldGenerator] SavedData file written to " + SAVED_DATA.toAbsolutePath());
    }

    /** Packs one object the way MTR's file writer does: map header around the object's own fields. */
    private static byte[] pack(SerializedDataBase data) throws IOException {
        final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
        packer.packMapHeader(data.messagePackLength());
        data.toMessagePack(packer);
        packer.close();
        return packer.toByteArray();
    }

    private static Map<String, Value> stringValueMap(byte[] bytes) throws IOException {
        final MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes);
        final Map<Value, Value> raw = unpacker.unpackValue().asMapValue().map();
        unpacker.close();
        final Map<String, Value> map = new HashMap<>();
        raw.forEach((key, value) -> map.put(key.asStringValue().asString(), value));
        return map;
    }
}
