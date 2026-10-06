package com.lx862.mtrmap;

import com.lx862.mtrmap.mapdata.MapDataBuilder;
import com.lx862.mtrmap.mtr.MtrCompat;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.Platform;
import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.Route;
import mtr.data.Siding;
import mtr.data.Station;
import mtr.data.TransportMode;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a small synthetic MTR 3 network for the headless tests.
 *
 * <p>This replaces the MTR 4 test-world generator's data construction: MTR 4
 * could fabricate a whole save through its platform independent core
 * ({@code Simulator} + {@code JsonReader} + {@code simulator.save()}), while MTR 3
 * keeps its data in per-level {@code RailwayData} SavedData that can only be
 * created from a live {@code Level}. The model objects below are the same MTR 3
 * classes the server and client use, so the map layer is exercised with real
 * data, and every object can still be serialised with MTR 3's own codecs.</p>
 *
 * <p>Layout: a west-east corridor {@code n0..n6} with a bend between {@code n1}
 * and {@code n2}, three stations with one platform rail each, a siding after the
 * last station, three routes and one depot.</p>
 */
final class TestNetwork {

    static final BlockPos N0 = new BlockPos(0, 64, 0);
    static final BlockPos N1 = new BlockPos(80, 64, 0);
    static final BlockPos N2 = new BlockPos(180, 64, 80);
    static final BlockPos N3 = new BlockPos(280, 64, 80);
    static final BlockPos N4 = new BlockPos(380, 64, 0);
    static final BlockPos N5 = new BlockPos(480, 64, 0);
    static final BlockPos N6 = new BlockPos(600, 64, 0);

    private TestNetwork() {
    }

    /** A complete network plus the MTR id maps the map layer resolves through. */
    record Network(List<Rail> rails, List<Platform> platforms, List<Station> stations, List<Depot> depots,
            List<Route> routes, Map<BlockPos, Map<BlockPos, Rail>> railsByNode, DataCache dataCache,
            Route express, Route local, Route localReturn, Depot depot, List<PathData> localDrivingPath) {
    }

    static RailAngle angleOf(BlockPos from, BlockPos to) {
        final double degrees = Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()));
        final int index = ((int) Math.round(degrees / 22.5) + 16) % 16;
        return RailAngle.values()[index];
    }

    static Rail rail(BlockPos start, BlockPos end, RailType type) {
        final Rail rail = new Rail(start, angleOf(start, end), end, angleOf(end, start), type, TransportMode.TRAIN);
        if (!rail.isValid()) {
            throw new IllegalStateException("test rail invalid: " + start + " -> " + end);
        }
        return rail;
    }

    static Network build() {
        final Rail railAlpha = rail(N0, N1, RailType.PLATFORM);
        final Rail railAlphaBravo = rail(N1, N2, RailType.IRON);
        final Rail railBravo = rail(N2, N3, RailType.PLATFORM);
        final Rail railBravoCharlie = rail(N3, N4, RailType.IRON);
        final Rail railCharlie = rail(N4, N5, RailType.PLATFORM);
        final Rail railSiding = rail(N5, N6, RailType.SIDING);
        final List<Rail> rails = List.of(railAlpha, railAlphaBravo, railBravo, railBravoCharlie, railCharlie,
                railSiding);

        final Platform alpha = new Platform(1, TransportMode.TRAIN, N0, N1);
        alpha.name = "Alpha Platform";
        alpha.color = 7829367;
        final Platform bravo = new Platform(2, TransportMode.TRAIN, N2, N3);
        bravo.name = "Bravo Platform";
        bravo.color = 7829367;
        final Platform charlie = new Platform(3, TransportMode.TRAIN, N4, N5);
        charlie.name = "Charlie Platform";
        charlie.color = 7829367;
        final List<Platform> platforms = List.of(alpha, bravo, charlie);

        final Station stationAlpha = station(11, "Alpha", 15073280, new BlockPos(-20, 64, -20),
                new BlockPos(100, 64, 20));
        final Station stationBravo = station(12, "Bravo", 28440, new BlockPos(200, 64, 60),
                new BlockPos(340, 64, 100));
        final Station stationCharlie = station(13, "Charlie", 22016, new BlockPos(360, 64, -20),
                new BlockPos(500, 64, 20));
        final List<Station> stations = List.of(stationAlpha, stationBravo, stationCharlie);

        final Route express = route(21, "Express", 15073280, List.of(alpha, bravo));
        final Route local = route(22, "Local", 28440, List.of(alpha, bravo, charlie));
        final Route localReturn = route(23, "Local Return", 28440, List.of(charlie, bravo, alpha));
        final List<Route> routes = List.of(express, local, localReturn);

        final Depot depot = new Depot(31, TransportMode.TRAIN);
        depot.name = "Test Depot";
        depot.color = 28440;
        depot.routeIds.add(local.id);
        depot.corner1 = new Tuple<>(-10, 470);
        depot.corner2 = new Tuple<>(10, 610);
        final List<Depot> depots = List.of(depot);

        // Both directions per physical rail, like MTR's own node index.
        final Map<BlockPos, Map<BlockPos, Rail>> railsByNode = new LinkedHashMap<>();
        addRail(railsByNode, N0, N1, railAlpha);
        addRail(railsByNode, N1, N2, railAlphaBravo);
        addRail(railsByNode, N2, N3, railBravo);
        addRail(railsByNode, N3, N4, railBravoCharlie);
        addRail(railsByNode, N4, N5, railCharlie);
        addRail(railsByNode, N5, N6, railSiding);

        // MTR's own driving path for the Local route, so the "colour rails with
        // the route a train really drives" branch is covered too.
        final List<PathData> localPath = List.of(
                new PathData(railAlpha, alpha.id, 0, N0, N1, 0),
                new PathData(railAlphaBravo, 0, 0, N1, N2, 0),
                new PathData(railBravo, bravo.id, 0, N2, N3, 1),
                new PathData(railBravoCharlie, 0, 0, N3, N4, 1),
                new PathData(railCharlie, charlie.id, 0, N4, N5, 2));

        final DataCache dataCache = new DataCache(new LinkedHashSet<>(stations), new LinkedHashSet<>(platforms),
                new LinkedHashSet<Siding>(), new LinkedHashSet<>(routes), new LinkedHashSet<>(depots), Set.of());
        dataCache.sync();

        return new Network(rails, platforms, stations, depots, routes, railsByNode, dataCache,
                express, local, localReturn, depot, localPath);
    }

    /** One MTR driving path (server: siding paths, client: running train paths). */
    static MapDataBuilder.RealPath realPath(Network network) {
        return new MapDataBuilder.RealPath(List.of(network.local()), "path:local", network.local().name,
                network.local().color, network.localDrivingPath());
    }

    /**
     * The same physical rails as <em>fresh instances</em>, mirroring how MTR
     * deserialises each {@code Siding} (and the {@code PathData} rails inside it)
     * separately from the rail map. Object identity therefore cannot be used to
     * match a path rail to the drawn track layer.
     */
    static List<PathData> copiedLocalDrivingPath(Network network) {
        return List.of(
                new PathData(rail(N0, N1, RailType.PLATFORM), network.platforms().get(0).id, 0, N0, N1, 0),
                new PathData(rail(N1, N2, RailType.IRON), 0, 0, N1, N2, 0),
                new PathData(rail(N2, N3, RailType.PLATFORM), network.platforms().get(1).id, 0, N2, N3, 1),
                new PathData(rail(N3, N4, RailType.IRON), 0, 0, N3, N4, 1),
                new PathData(rail(N4, N5, RailType.PLATFORM), network.platforms().get(2).id, 0, N4, N5, 2));
    }

    private static void addRail(Map<BlockPos, Map<BlockPos, Rail>> railsByNode, BlockPos first, BlockPos second,
            Rail rail) {
        railsByNode.computeIfAbsent(first, ignored -> new HashMap<>()).put(second, rail);
        railsByNode.computeIfAbsent(second, ignored -> new HashMap<>()).put(first, rail);
    }

    private static Station station(long id, String name, int color, BlockPos corner1, BlockPos corner2) {
        final Station station = new Station(id);
        station.name = name;
        station.color = color;
        station.corner1 = new Tuple<>(corner1.getX(), corner1.getZ());
        station.corner2 = new Tuple<>(corner2.getX(), corner2.getZ());
        return station;
    }

    private static Route route(long id, String name, int color, List<Platform> platforms) {
        final Route route = new Route(id, TransportMode.TRAIN);
        route.name = name;
        route.color = color;
        for (Platform platform : new ArrayList<>(platforms)) {
            route.platformIds.add(new Route.RoutePlatform(platform.id));
        }
        return route;
    }

    /** Number of physical rails the TRACK layer is expected to draw. */
    static int physicalRailCount(Network network) {
        final Set<String> ids = new HashSet<>();
        for (Map.Entry<BlockPos, Map<BlockPos, Rail>> entry : network.railsByNode().entrySet()) {
            for (BlockPos to : entry.getValue().keySet()) {
                ids.add(MtrCompat.railKey(entry.getKey(), to));
            }
        }
        return ids.size();
    }
}
