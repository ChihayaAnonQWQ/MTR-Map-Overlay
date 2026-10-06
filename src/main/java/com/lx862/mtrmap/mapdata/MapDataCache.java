package com.lx862.mtrmap.mapdata;

import com.lx862.mtrmap.MTRMap;
import mtr.client.ClientData;
import mtr.data.Route;
import mtr.data.TrainClient;
import mtr.path.PathData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central access point for map path-layer data, keyed by dimension id
 * (the same "namespace/path" form the map integrations use, e.g.
 * "minecraft/overworld").
 *
 * <p>Resolution order per dimension:</p>
 * <ol>
 *   <li>Server-synced full-network data ({@code SERVER_DATA}, filled by the
 *       network sync when the server runs this mod) - covers the whole
 *       network, Create-style.</li>
 *   <li>Fallback: whatever MTR has synced to the client
 *       ({@link mtr.client.ClientData}), which is limited to the area around
 *       the player (MTR only syncs data within render distance).</li>
 * </ol>
 *
 * <p>MTR 3 exposes client data as static collections on {@code ClientData}
 * (MTR 4 used a {@code MinecraftClientData} singleton plus a separate dashboard
 * instance), so this class no longer has to aggregate two instances.</p>
 */
public class MapDataCache {

    /** Simple per-dimension bundle of render-ready data. */
    public static class DimensionData {

        public final String dimensionId;
        public final List<MapRoute> routes;
        public final List<MapTrack> tracks;
        public final List<MapLandmark> landmarks;
        public final Map<String, List<TrackRoutePalette.Entry>> routePalette;
        /** Monotonic version counter, used to decide when to rebuild caches. */
        public final long version;

        public DimensionData(String dimensionId, List<MapRoute> routes, List<MapTrack> tracks,
                List<MapLandmark> landmarks, long version) {
            this.dimensionId = dimensionId;
            this.routes = routes;
            this.tracks = tracks;
            this.landmarks = landmarks;
            this.routePalette = TrackRoutePalette.build(routes);
            this.version = version;
        }

        public boolean isEmpty() {
            return routes.isEmpty() && tracks.isEmpty() && landmarks.isEmpty();
        }
    }

    private static final DimensionData EMPTY = new DimensionData("", List.of(), List.of(), List.of(), 0);

    private static final HashMap<String, DimensionData> SERVER_DATA = new HashMap<>();
    /** Bumped whenever MTR pushes new client data, invalidates the client-side cache. */
    private static volatile long clientDataVersion = 0;
    private static volatile DimensionData clientDataBuilt = null;
    private static volatile boolean clientDataReady = false;

    /**
     * Human readable cache state, surfaced by {@code /mtrmap status}. Kept free
     * of Minecraft/MTR types so it is safe to call from anywhere.
     */
    public static List<String> describeState(String currentDimensionKey) {
        final List<String> lines = new ArrayList<>();
        synchronized (SERVER_DATA) {
            if (SERVER_DATA.isEmpty()) {
                lines.add("server snapshot: none received");
            } else {
                lines.add("server snapshot: " + SERVER_DATA.size() + " dimension(s)");
                for (Map.Entry<String, DimensionData> entry : SERVER_DATA.entrySet()) {
                    lines.add("  " + entry.getKey() + ": " + describe(entry.getValue()));
                }
            }
        }

        if (!clientDataReady) {
            lines.add("client fallback: MTR client data not synced yet");
        } else if (clientDataBuilt == null) {
            lines.add("client fallback: synced, not built yet");
        } else {
            lines.add("client fallback: " + describe(clientDataBuilt));
        }

        if (currentDimensionKey == null) {
            lines.add("current dimension: unknown (not in a world)");
        } else {
            lines.add("current dimension: " + currentDimensionKey
                    + (hasServerData(currentDimensionKey) ? " -> using server snapshot" : " -> using client fallback"));
        }
        return lines;
    }

    private static String describe(DimensionData data) {
        long colouredRoutes = 0;
        for (MapRoute route : data.routes) {
            if (!route.trackIds.isEmpty()) {
                colouredRoutes++;
            }
        }
        return data.routes.size() + " routes (" + colouredRoutes + " with track geometry), "
                + data.tracks.size() + " tracks, " + data.landmarks.size() + " landmarks, version " + data.version;
    }

    private MapDataCache() {
    }

    public static void onClientDataSynced() {
        clientDataVersion++;
        clientDataReady = true;
    }

    /** Prevent the previous world's radius-limited data from appearing after a connection change. */
    public static void clearClientData() {
        clientDataReady = false;
        clientDataBuilt = null;
    }

    /**
     * Store a full-network snapshot received from the (modded) server.
     * Called on the network thread; volatile-safe swap into the map.
     */
    public static void putServerData(String dimension, DimensionData data) {
        synchronized (SERVER_DATA) {
            SERVER_DATA.put(dimension, data);
        }
    }

    public static void clearServerData() {
        synchronized (SERVER_DATA) {
            SERVER_DATA.clear();
        }
    }

    public static boolean hasServerData(String dimension) {
        synchronized (SERVER_DATA) {
            return SERVER_DATA.containsKey(dimension);
        }
    }

    /**
     * Get render data for a dimension. Prefers server-synced full-network
     * data; falls back to the radius-limited MTR client data.
     */
    public static DimensionData get(String dimension) {
        synchronized (SERVER_DATA) {
            final DimensionData serverData = SERVER_DATA.get(dimension);
            if (serverData != null) {
                return serverData;
            }
        }
        return getClientData();
    }

    /**
     * Build (with memoization) route/track data from MTR's client-side data.
     * Note: MTR only syncs data within render distance of the player, so this
     * fallback only covers the area around the player.
     */
    public static DimensionData getClientData() {
        if (!clientDataReady) {
            return EMPTY;
        }
        final long version = clientDataVersion;
        DimensionData data = clientDataBuilt;
        if (data != null && data.version == version) {
            return data;
        }
        data = buildClientData(version);
        clientDataBuilt = data;
        return data;
    }

    private static DimensionData buildClientData(long version) {
        final MapDataBuilder.Input input = new MapDataBuilder.Input();
        try {
            // Copy the static sets: MTR mutates them from its own tick/packet
            // handling while the map may be rendering.
            input.dataCache = ClientData.DATA_CACHE;
            input.routes = new ArrayList<>(ClientData.ROUTES);
            input.stations = new ArrayList<>(ClientData.STATIONS);
            input.depots = new ArrayList<>(ClientData.DEPOTS);
            input.rails = new HashMap<>(ClientData.RAILS);
            input.realPaths = collectClientDrivingPaths();
            input.fallbackPathfinding = true;
        } catch (Throwable e) {
            MTRMap.LOGGER.error("[MTRMap] Error accessing MTR client datasets", e);
        }

        try {
            final MapDataBuilder.Result result = MapDataBuilder.build(input);
            return new DimensionData("", result.routes, result.tracks, result.landmarks, version);
        } catch (Throwable e) {
            // Never let data collection break the map render
            MTRMap.LOGGER.debug("[MTRMap] Error building client map data: {}", e.getMessage());
            return new DimensionData("", List.of(), List.of(), List.of(), version);
        }
    }

    /**
     * Pure-client source of MTR's real driving paths: every running train
     * carries the path MTR generated for it, so the rails a train is actually
     * using can be dyed with its route's colour.
     */
    private static List<MapDataBuilder.RealPath> collectClientDrivingPaths() {
        final List<MapDataBuilder.RealPath> realPaths = new ArrayList<>();
        try {
            for (TrainClient train : new ArrayList<>(ClientData.TRAINS)) {
                final List<PathData> path = train.path;
                if (path == null || path.isEmpty()) {
                    continue;
                }
                final List<Route> candidates = new ArrayList<>();
                final Route thisRoute = train.getThisRoute();
                if (thisRoute != null) {
                    candidates.add(thisRoute);
                }
                for (Long routeId : train.getRouteIds()) {
                    final Route route = routeId == null || ClientData.DATA_CACHE == null ? null
                            : ClientData.DATA_CACHE.routeIdMap.get(routeId);
                    if (route != null && !candidates.contains(route)) {
                        candidates.add(route);
                    }
                }
                if (candidates.isEmpty()) {
                    continue;
                }
                final Route primary = candidates.get(0);
                realPaths.add(new MapDataBuilder.RealPath(candidates, "train:" + train.trainId,
                        primary.name, primary.color, path));
            }
        } catch (Throwable e) {
            MTRMap.LOGGER.debug("[MTRMap] Failed to collect vehicle paths: {}", e.getMessage());
        }
        return realPaths;
    }
}
