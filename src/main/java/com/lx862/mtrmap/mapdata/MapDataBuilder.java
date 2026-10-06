package com.lx862.mtrmap.mapdata;

import com.lx862.mtrmap.MTRMap;
import com.lx862.mtrmap.mtr.MtrCompat;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.Platform;
import mtr.data.Rail;
import mtr.data.Route;
import mtr.data.Station;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts MTR 3 network data into the render-ready map layers.
 *
 * <p>The same builder runs on both sides - the client cache
 * ({@link MapDataCache}) feeds it MTR's radius limited client data, and the
 * server snapshot ({@code ServerNetworkCollector}) feeds it the whole
 * {@code RailwayData} network - so both produce identical layers. MTR 4 had two
 * parallel implementations (a {@code Simulator} based one and a
 * {@code MinecraftClientData} based one) because its core and mod data models
 * differed; MTR 3 has a single {@code mtr.data} model, so the duplication is not
 * needed.</p>
 *
 * <p>Route colours come from MTR's own driving paths when available (the path a
 * depot's trains actually drive, or the path a running train is following);
 * routes without a generated path are snapped onto the rail graph with
 * {@link RoutePathfinder} so the colour still follows real track geometry.</p>
 */
public final class MapDataBuilder {

    private MapDataBuilder() {
    }

    /** MTR inputs, already resolved by the caller (client cache or server data). */
    public static final class Input {
        public DataCache dataCache;
        public Collection<Route> routes = List.of();
        public Collection<Station> stations = List.of();
        public Collection<Depot> depots = List.of();
        public Map<BlockPos, Map<BlockPos, Rail>> rails = Map.of();
        /** MTR generated driving paths; the colour of every stretch is resolved from their candidate routes. */
        public List<RealPath> realPaths = List.of();
        /** Snap routes without a generated driving path onto the rail network. */
        public boolean fallbackPathfinding = true;
    }

    /**
     * One MTR generated driving path together with the routes it may belong to.
     * {@code candidates} are the routes of the depot (server) or of the running
     * train (client); the serving route of each stretch is the first candidate
     * that calls at the platform the stretch stops at.
     */
    public record RealPath(List<Route> candidates, String fallbackId, String fallbackName, int fallbackColor,
            List<PathData> path) {
    }

    public static final class Result {
        public final List<MapRoute> routes;
        public final List<MapTrack> tracks;
        public final List<MapLandmark> landmarks;
        public final Map<String, MapTrack> tracksById;
        /** Routes coloured from MTR's own generated driving paths. */
        public final int realPathRoutes;
        /** Routes that had no generated path and were snapped onto the rail graph (0 track ids = unroutable). */
        public final int fallbackRoutes;
        /** Routes emitted without track geometry (no complete path found); they carry stops only. */
        public final int unroutableRoutes;

        /** Physical rails found in MTR's node index (before sampling). */
        public final int physicalRails;

        Result(List<MapRoute> routes, List<MapTrack> tracks, List<MapLandmark> landmarks,
                Map<String, MapTrack> tracksById, int realPathRoutes, int fallbackRoutes, int unroutableRoutes,
                int physicalRails) {
            this.routes = routes;
            this.tracks = tracks;
            this.landmarks = landmarks;
            this.tracksById = tracksById;
            this.realPathRoutes = realPathRoutes;
            this.fallbackRoutes = fallbackRoutes;
            this.unroutableRoutes = unroutableRoutes;
            this.physicalRails = physicalRails;
        }
    }

    public static Result build(Input input) {
        final List<MapRoute> routes = new ArrayList<>();
        final List<MapTrack> tracks = new ArrayList<>();
        final List<MapLandmark> landmarks = new ArrayList<>();

        // Canonical TRACK layer first: every later layer reuses these polylines
        // instead of sampling the same physical rail again.
        final Map<String, Rail> uniqueRails = MtrCompat.uniqueRails(input.rails);
        final Map<String, MapTrack> sampledTracks = new LinkedHashMap<>();
        final Map<Rail, String> railKeyByIdentity = new IdentityHashMap<>();
        for (Map.Entry<String, Rail> entry : uniqueRails.entrySet()) {
            final Rail rail = entry.getValue();
            railKeyByIdentity.put(rail, entry.getKey());
            final List<double[]> points = TrackSampler.sample(rail);
            if (points == null) {
                continue;
            }
            final MapTrack track = new MapTrack(entry.getKey(), points);
            tracks.add(track);
            sampledTracks.put(track.id, track);
        }

        // Route colours painted along MTR's own generated driving paths.
        final Set<Long> routesWithRealPath = new HashSet<>();
        for (RealPath realPath : input.realPaths) {
            try {
                final Set<Long> usedRouteIds = new HashSet<>();
                if (collectRealPath(input, realPath, sampledTracks, railKeyByIdentity, routes, usedRouteIds)) {
                    // Only suppress the fallback for routes whose generated path
                    // actually produced geometry; otherwise those routes would
                    // stay colourless instead of being snapped onto the rails.
                    routesWithRealPath.addAll(usedRouteIds);
                }
            } catch (Throwable e) {
                MTRMap.LOGGER.debug("[MTRMap] Failed to collect a driving path: {}", e.getMessage());
            }
        }
        final int realPathRoutes = routes.size();

        // Fallback for routes without a generated path: snap them onto the rail
        // network so the colour still follows the exact track geometry.
        int fallbackRoutes = 0;
        int unroutableRoutes = 0;
        if (input.fallbackPathfinding && !uniqueRails.isEmpty()) {
            final RoutePathfinder.Graph graph = RoutePathfinder.buildGraph(input.rails);
            for (Route route : safe(input.routes)) {
                try {
                    if (route == null || routesWithRealPath.contains(route.id)) {
                        continue;
                    }
                    if (collectFallbackRoute(input, graph, route, sampledTracks, routes)) {
                        fallbackRoutes++;
                    } else {
                        unroutableRoutes++;
                    }
                } catch (Throwable e) {
                    unroutableRoutes++;
                    MTRMap.LOGGER.debug("[MTRMap] Failed to snap route onto the network: {}", e.getMessage());
                }
            }
        }

        collectLandmarks(input, landmarks);
        return new Result(routes, tracks, landmarks, sampledTracks, realPathRoutes, fallbackRoutes, unroutableRoutes,
                uniqueRails.size());
    }

    /**
     * Walk one MTR driving path and split it into per-route coloured stretches.
     * A colour change happens where the serving route changes, exactly like the
     * MTR 4 implementation did for depot paths.
     *
     * @param usedRouteIds receives the routes this path belongs to; the caller
     *        only suppresses the fallback for them when this method returns
     *        {@code true} (i.e. the path produced geometry).
     * @return true when at least one coloured route stretch was emitted.
     */
    private static boolean collectRealPath(Input input, RealPath realPath, Map<String, MapTrack> sampledTracks,
            Map<Rail, String> railKeyByIdentity, List<MapRoute> routes, Set<Long> usedRouteIds) {
        final List<PathData> path = realPath.path();
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (Route route : safe(realPath.candidates())) {
            usedRouteIds.add(route.id);
        }
        final int routesBefore = routes.size();

        String currentRouteId = realPath.fallbackId();
        String currentRouteName = realPath.fallbackName();
        int currentColor = realPath.fallbackColor();
        List<MapRoute.Stop> stops = new ArrayList<>();
        List<MapTrack> routeTracks = new ArrayList<>();
        Set<String> railIds = new HashSet<>();
        long lastPlatformId = 0;

        for (PathData pathData : path) {
            if (pathData == null || pathData.rail == null) {
                continue;
            }
            // The rails inside a Siding's path are deserialised separately from
            // the rail map, so identity matching fails for real server data:
            // fall back to the geometry-derived key (same node pair).
            String railId = railKeyByIdentity.get(pathData.rail);
            if (railId == null) {
                railId = MtrCompat.railKeyOf(pathData.rail);
            }
            if (railId == null) {
                continue; // rail outside the drawn network
            }
            final double length = pathData.rail.getLength();
            if (!Double.isFinite(length) || length <= 0) {
                continue;
            }

            final long savedRailId = pathData.savedRailBaseId;
            final Platform platform = savedRailId == 0 || input.dataCache == null ? null
                    : input.dataCache.platformIdMap.get(savedRailId);
            if (platform != null && savedRailId != lastPlatformId) {
                final Route serving = MtrCompat.resolveServingRoute(realPath.candidates(), savedRailId);
                final String routeId = serving == null ? realPath.fallbackId() : MtrCompat.hexId(serving.id);
                final String routeName = serving == null ? realPath.fallbackName() : serving.name;
                final int routeColor = serving == null ? realPath.fallbackColor() : serving.color;

                if (currentRouteName != null && (!routeName.equals(currentRouteName) || routeColor != currentColor)) {
                    // Colour change: flush the finished stretch.
                    if (!routeTracks.isEmpty()) {
                        routes.add(MapRoute.ofTracks(currentRouteId, currentRouteName, currentColor, stops, routeTracks));
                    }
                    stops = new ArrayList<>();
                    routeTracks = new ArrayList<>();
                    railIds = new HashSet<>();
                }
                currentRouteId = routeId;
                currentRouteName = routeName;
                currentColor = routeColor;
                lastPlatformId = savedRailId;
            }

            // Reuse the exact full-rail geometry of the TRACK layer. Direction
            // and path distances affect train movement, not how a rail is drawn.
            if (railIds.add(railId)) {
                final MapTrack track = sampledTracks.get(railId);
                if (track != null && track.points.size() >= 2) {
                    routeTracks.add(track);
                }
            }

            if (platform != null) {
                final BlockPos stopPos = MtrCompat.midPos(platform);
                if (stopPos != null) {
                    appendStop(stops, stopPos, MtrCompat.stationNameOfPlatform(input.dataCache, platform.id));
                }
                lastPlatformId = savedRailId;
            }
        }

        if (!routeTracks.isEmpty()) {
            routes.add(MapRoute.ofTracks(currentRouteId, currentRouteName == null ? realPath.fallbackName() : currentRouteName,
                    currentColor, stops, routeTracks));
        }
        return routes.size() > routesBefore;
    }

    /** Snap one route without a generated path onto the rail graph. */
    private static boolean collectFallbackRoute(Input input, RoutePathfinder.Graph graph, Route route,
            Map<String, MapTrack> sampledTracks, List<MapRoute> routes) {
        final List<Route.RoutePlatform> routePlatforms = route.platformIds;
        if (routePlatforms == null || routePlatforms.size() < 2 || input.dataCache == null) {
            return false;
        }
        final List<MapRoute.Stop> stops = new ArrayList<>(routePlatforms.size());
        final List<Platform> platforms = new ArrayList<>(routePlatforms.size());
        for (Route.RoutePlatform routePlatform : routePlatforms) {
            final Platform platform = routePlatform == null ? null : input.dataCache.platformIdMap.get(routePlatform.platformId);
            final BlockPos pos = MtrCompat.midPos(platform);
            if (platform == null || pos == null) {
                return false; // incomplete stop list: a partial route line would be misleading
            }
            platforms.add(platform);
            stops.add(new MapRoute.Stop(pos.getX(), pos.getZ(),
                    MtrCompat.stationNameOfPlatform(input.dataCache, platform.id),
                    routePlatform.customDestination == null ? "" : routePlatform.customDestination));
        }

        final boolean circular = MtrCompat.isCircular(route);
        final List<RoutePathfinder.SegmentPath> segments = RoutePathfinder.findRoutePath(graph, platforms, circular);
        final List<MapTrack> routeTracks = RoutePathfinder.toTracks(graph, platforms, segments, circular, sampledTracks);
        if (routeTracks.isEmpty()) {
            MTRMap.LOGGER.debug("[MTRMap] No complete track path for route {}; skipping it", route.name);
            return false;
        }
        routes.add(MapRoute.ofTracks(MtrCompat.hexId(route.id), route.name, route.color, stops, routeTracks));
        return true;
    }

    private static void appendStop(List<MapRoute.Stop> stops, BlockPos pos, String stationName) {
        final MapRoute.Stop last = stops.isEmpty() ? null : stops.get(stops.size() - 1);
        if (last != null && last.stationName != null && last.stationName.equals(stationName)) {
            return; // arrive + depart on the same platform
        }
        stops.add(new MapRoute.Stop(pos.getX(), pos.getZ(), stationName, ""));
    }

    /** Complete, dimension-wide landmark data for stations, platforms and depots. */
    private static void collectLandmarks(Input input, List<MapLandmark> landmarks) {
        final Map<Long, List<String>> platformRouteLabels = new HashMap<>();
        for (Route route : safe(input.routes)) {
            if (route.platformIds == null) {
                continue;
            }
            for (int i = 0; i < route.platformIds.size(); i++) {
                final Route.RoutePlatform routePlatform = route.platformIds.get(i);
                if (routePlatform == null) {
                    continue;
                }
                platformRouteLabels.computeIfAbsent(routePlatform.platformId, ignored -> new ArrayList<>())
                        .add(MtrCompat.routeLabel(route, i));
            }
        }

        final Map<Long, List<Platform>> platformsByStation = MtrCompat.platformsByStation(input.dataCache);
        for (Station station : safe(input.stations)) {
            if (station.name == null || station.name.isEmpty()) {
                continue;
            }
            long totalY = 0;
            int platformCount = 0;
            final Set<String> stationRouteLabels = new LinkedHashSet<>();
            for (Platform platform : platformsByStation.getOrDefault(station.id, List.of())) {
                final BlockPos platformPosition = MtrCompat.midPos(platform);
                if (platformPosition == null) {
                    continue;
                }
                totalY += platformPosition.getY();
                platformCount++;

                final List<String> routeLabels = platformRouteLabels.getOrDefault(platform.id, List.of());
                stationRouteLabels.addAll(routeLabels);
                final String platformName = platform.name == null || platform.name.isEmpty()
                        ? Long.toString(platform.id) : platform.name;
                landmarks.add(new MapLandmark("platform:" + MtrCompat.hexId(platform.id), MapLandmark.Type.PLATFORM,
                        platformPosition.getX(), platformPosition.getY(), platformPosition.getZ(), station.name,
                        platformName, String.join(", ", new LinkedHashSet<>(routeLabels)), !routeLabels.isEmpty()));
            }

            final BlockPos center = MtrCompat.areaCenter(station);
            final int x = center == null ? 0 : center.getX();
            final int z = center == null ? 0 : center.getZ();
            final int y = platformCount == 0 ? MtrCompat.areaY(center) : (int) (totalY / platformCount);
            landmarks.add(new MapLandmark("station:" + MtrCompat.hexId(station.id), MapLandmark.Type.STATION,
                    x, y, z, station.name, station.name, String.join(", ", stationRouteLabels),
                    !stationRouteLabels.isEmpty()));
        }

        for (Depot depot : safe(input.depots)) {
            if (depot.name == null || depot.name.isEmpty()) {
                continue;
            }
            final BlockPos center = MtrCompat.areaCenter(depot);
            landmarks.add(new MapLandmark("depot:" + MtrCompat.hexId(depot.id), MapLandmark.Type.DEPOT,
                    center == null ? 0 : center.getX(), MtrCompat.areaY(center), center == null ? 0 : center.getZ(),
                    depot.name, "D", "", depot.routeIds != null && !depot.routeIds.isEmpty()));
        }
    }

    private static <T> Collection<T> safe(Collection<T> collection) {
        return collection == null ? List.of() : collection;
    }
}
