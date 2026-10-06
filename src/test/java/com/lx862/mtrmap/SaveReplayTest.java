package com.lx862.mtrmap;

import com.lx862.mtrmap.mapdata.MapDataBuilder;
import com.lx862.mtrmap.mapdata.MapRoute;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.Platform;
import mtr.data.Rail;
import mtr.data.Route;
import mtr.data.Siding;
import mtr.data.Station;
import mtr.data.TransportMode;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays a real MTR 3 world save through the ported map layer, without a game.
 *
 * <p>This is the regression that motivated the test: a live server reported
 * {@code 1 routes} while MTR's own {@code /data} endpoint reported 17, because a
 * {@code Siding}'s path holds rail instances that MTR deserialised separately
 * from the rail map, so the identity lookup in the builder matched nothing and -
 * worse - those routes were then skipped by the fallback.</p>
 *
 * <p>Point {@code -Dmtrmap.replayDir} at a
 * {@code <world>/mtr/<namespace>/<path>} directory (e.g. {@code minecraft/overworld}).</p>
 */
@EnabledIfSystemProperty(named = "mtrmap.replayDir", matches = ".+")
class SaveReplayTest {

    private static Map<BlockPos, Map<BlockPos, Rail>> loadRails(Path railsDir) throws Exception {
        final Map<BlockPos, Map<BlockPos, Rail>> rails = new LinkedHashMap<>();
        for (Path file : listFiles(railsDir)) {
            final Map<String, Value> nodeMap = readMap(file);
            final BlockPos from = BlockPos.of(nodeMap.get("node_pos").asIntegerValue().asLong());
            final Value connections = nodeMap.get("rail_connections");
            if (connections == null || !connections.isArrayValue()) {
                continue;
            }
            for (Value connection : connections.asArrayValue()) {
                final Map<String, Value> connectionMap = toMap(connection);
                final Value to = connectionMap.get("node_pos");
                if (to == null) {
                    continue;
                }
                rails.computeIfAbsent(from, ignored -> new HashMap<>())
                        .put(BlockPos.of(to.asIntegerValue().asLong()), new Rail(connectionMap));
            }
        }
        return rails;
    }

    private static <T> List<T> loadAll(Path dir, java.util.function.Function<Map<String, Value>, T> factory,
            java.util.function.ToLongFunction<T> idOf) throws Exception {
        final List<T> result = new ArrayList<>();
        for (Path file : listFiles(dir)) {
            result.add(factory.apply(readMap(file)));
        }
        return result;
    }

    /**
     * Siding paths are read straight from the message-pack file: MTR's own
     * {@code Siding(Map)} constructor also builds train instances and therefore
     * needs the vanilla item registries, which a plain JVM test has no reason to
     * bootstrap. The {@code PathData} objects - the part the map layer consumes -
     * are identical either way.
     */
    private static List<PathData> loadSidingPath(Path file) throws Exception {
        final Value path = readMap(file).get("path");
        final List<PathData> result = new ArrayList<>();
        if (path != null && path.isArrayValue()) {
            for (Value entry : path.asArrayValue()) {
                result.add(new PathData(toMap(entry)));
            }
        }
        return result;
    }

    /** Depots are rebuilt from their fields for the same reason as sidings. */
    private static Depot loadDepot(Path file) throws Exception {
        final Map<String, Value> map = readMap(file);
        final Depot depot = new Depot(map.get("id").asIntegerValue().asLong(), TransportMode.TRAIN);
        final Value name = map.get("name");
        if (name != null) {
            depot.name = name.asStringValue().asString();
        }
        final Value color = map.get("color");
        if (color != null) {
            depot.color = color.asIntegerValue().asInt();
        }
        final Value routeIds = map.get("route_ids");
        if (routeIds != null && routeIds.isArrayValue()) {
            for (Value routeId : routeIds.asArrayValue()) {
                depot.routeIds.add(routeId.asIntegerValue().asLong());
            }
        }
        return depot;
    }

    @Test
    void replaysARealSave() throws Exception {
        final Path dimDir = Path.of(System.getProperty("mtrmap.replayDir"));

        final Map<BlockPos, Map<BlockPos, Rail>> rails = loadRails(dimDir.resolve("rails"));
        final List<Station> stations = loadAll(dimDir.resolve("stations"), Station::new, s -> s.id);
        final List<Platform> platforms = loadAll(dimDir.resolve("platforms"), Platform::new, p -> p.id);
        final List<Route> routes = loadAll(dimDir.resolve("routes"), Route::new, r -> r.id);
        final List<Depot> depots = new ArrayList<>();
        for (Path file : listFiles(dimDir.resolve("depots"))) {
            depots.add(loadDepot(file));
        }

        final List<List<PathData>> sidingPaths = new ArrayList<>();
        for (Path file : listFiles(dimDir.resolve("sidings"))) {
            final List<PathData> path = loadSidingPath(file);
            if (!path.isEmpty()) {
                sidingPaths.add(path);
            }
        }

        final DataCache dataCache = new DataCache(new LinkedHashSet<>(stations), new LinkedHashSet<>(platforms),
                new LinkedHashSet<Siding>(), new LinkedHashSet<>(routes), new LinkedHashSet<>(depots), Set.of());
        dataCache.sync();

        final List<MapDataBuilder.RealPath> realPaths = new ArrayList<>();
        int pathEntries = 0;
        for (int i = 0; i < sidingPaths.size(); i++) {
            final List<PathData> path = sidingPaths.get(i);
            pathEntries += path.size();
            // Candidates are every route here: the replay checks geometry
            // matching, not MTR's depot-to-route attribution.
            realPaths.add(new MapDataBuilder.RealPath(new ArrayList<>(routes), "siding:" + i,
                    "siding " + i, 0xFFFFFF, path));
        }

        final MapDataBuilder.Input input = new MapDataBuilder.Input();
        input.dataCache = dataCache;
        input.routes = routes;
        input.stations = stations;
        input.depots = depots;
        input.rails = rails;
        input.realPaths = realPaths;
        input.fallbackPathfinding = true;

        final MapDataBuilder.Result result = MapDataBuilder.build(input);

        long coloured = 0;
        for (MapRoute route : result.routes) {
            if (!route.trackIds.isEmpty()) {
                coloured++;
            }
        }
        System.out.println("[replay] rails=" + result.physicalRails
                + " tracks=" + result.tracks.size()
                + " routesInSave=" + routes.size()
                + " routesEmitted=" + result.routes.size() + " (" + coloured + " coloured)"
                + " fromRealPaths=" + result.realPathRoutes
                + " snapped=" + result.fallbackRoutes
                + " unroutable=" + result.unroutableRoutes
                + " sidingPathEntries=" + pathEntries
                + " landmarks=" + result.landmarks.size());

        assertFalse(rails.isEmpty(), "the save must contain rails");
        assertTrue(result.tracks.size() > 0, "rails must be sampled into tracks");
        assertTrue(result.realPathRoutes > 0,
                "MTR's own generated driving paths must resolve to drawn tracks (geometry match, not identity)");
    }

    private static Map<String, Value> readMap(Path file) throws Exception {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(Files.readAllBytes(file))) {
            return toMap(unpacker.unpackValue());
        }
    }

    private static Map<String, Value> toMap(Value value) {
        final Map<String, Value> map = new HashMap<>();
        value.asMapValue().map().forEach((key, entry) -> map.put(key.asStringValue().asString(), entry));
        return map;
    }

    private static List<Path> listFiles(Path root) throws Exception {
        final List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }
}
