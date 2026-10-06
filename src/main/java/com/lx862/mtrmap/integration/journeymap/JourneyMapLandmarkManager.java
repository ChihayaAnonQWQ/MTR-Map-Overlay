package com.lx862.mtrmap.integration.journeymap;

import com.lx862.mtrmap.MTRDataSummary;
import com.lx862.mtrmap.MTRMap;
import com.lx862.mtrmap.config.MTRMapConfig;
import com.lx862.mtrmap.mapdata.MapDataCache;
import com.lx862.mtrmap.mapdata.MapLandmark;
import com.lx862.mtrmap.mtr.MtrCompat;
import journeymap.api.v2.common.Context;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.model.MapImage;
import mtr.client.ClientData;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.IGui;
import mtr.data.NameColorDataBase;
import mtr.data.Platform;
import mtr.data.Route;
import mtr.data.Station;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds JourneyMap {@link MarkerOverlay} markers from MTR data.
 *
 * <p>Ported from the standalone JourneyMap edition onto the unified mod:
 * a "station" mode (one marker per station), a "platform" mode (one marker
 * per platform, with route &amp; destination info on hover) and optional depot
 * markers, with per-transport-mode icons tinted by MTR color.</p>
 *
 * <p>This class references JourneyMap API types and must only be loaded when
 * JourneyMap is present (see {@link JourneyMapIntegration}).</p>
 */
final class JourneyMapLandmarkManager {

    // Track currently displayed markers so we can clean them up on the next sync
    private static final List<MarkerOverlay> activeMarkers = new ArrayList<>();
    private static MarkerOverlay testMarker;

    static List<MarkerOverlay> displayedMarkers() {
        if (testMarker == null) {
            return activeMarkers;
        }
        final List<MarkerOverlay> markers = new ArrayList<>(activeMarkers);
        markers.add(testMarker);
        return markers;
    }

    private JourneyMapLandmarkManager() {
    }

    static void syncLandmarks(String reason, Level world, DataCache dataCache) {
        final IClientAPI api = getJourneyMapAPI();
        if (api == null) {
            // JourneyMap not loaded or its plugin not initialized yet
            return;
        }

        final MTRMapConfig config = MTRMapConfig.INSTANCE;
        final long startMs = System.currentTimeMillis();

        // Build the full set of markers that should be displayed right now
        final Map<String, MarkerOverlay> desiredMarkers = new LinkedHashMap<>();
        if (config.enabled.get()) {
            // MTR 3: MTRDataSummary takes the DataCache (ClientData.DATA_CACHE)
            // directly instead of a MinecraftClientData instance
            final MTRDataSummary dataSummary = MTRDataSummary.of(dataCache);
            final String dimensionKey = world.dimension().location().getNamespace() + "/"
                    + world.dimension().location().getPath();
            if (MapDataCache.hasServerData(dimensionKey)) {
                collectNetworkMarkers(desiredMarkers, MapDataCache.get(dimensionKey).landmarks, world);
            } else {
                if ("platform".equalsIgnoreCase(config.waypointMode.get())) {
                    collectPlatformMarkers(desiredMarkers, dataCache, world);
                } else if ("both".equalsIgnoreCase(config.waypointMode.get())) {
                    collectStationMarkers(desiredMarkers, dataSummary, world);
                    collectPlatformMarkers(desiredMarkers, dataCache, world);
                } else {
                    collectStationMarkers(desiredMarkers, dataSummary, world);
                }
            }

            if (config.showDepotLandmarks.get()) {
                collectDepotMarkers(desiredMarkers, world);
            }
        }

        // JourneyMap rejects api.show() for an ID that is already displayed, so
        // every sync removes the previous set and re-adds fresh markers
        for (MarkerOverlay marker : activeMarkers) {
            try {
                api.remove(marker);
            } catch (Exception e) {
                MTRMap.LOGGER.warn("[MTRMap] Failed to remove marker during resync: {}", e.getMessage());
            }
        }
        activeMarkers.clear();

        int failures = 0;
        for (Map.Entry<String, MarkerOverlay> entry : desiredMarkers.entrySet()) {
            try {
                api.show(entry.getValue());
                activeMarkers.add(entry.getValue());
            } catch (Exception e) {
                failures++;
                if (failures <= 3) {
                    MTRMap.LOGGER.error("[MTRMap] Failed to show marker for {}", entry.getKey(), e);
                }
            }
        }

        if (config.debugLog.get() || failures > 0) {
            MTRMap.LOGGER.info("[MTRMap] Landmark sync ({}): {} markers active, {} failed (mode: {}, {}ms)",
                    reason, activeMarkers.size(), failures, config.waypointMode.get(),
                    System.currentTimeMillis() - startMs);
        }
    }

    private static void collectNetworkMarkers(Map<String, MarkerOverlay> out, List<MapLandmark> landmarks,
            Level world) {
        for (MapLandmark landmark : landmarks) {
            if (!shouldShow(landmark)) {
                continue;
            }
            final boolean depot = landmark.type() == MapLandmark.Type.DEPOT;
            final int size = landmark.type() == MapLandmark.Type.PLATFORM ? 6 : depot ? 10 : 12;
            final ResourceLocation iconLocation = markerIcon("train", depot);
            // The bundled marker PNGs are 32x32; declaring 16x16 samples
            // only a corner of the station/depot icon in JourneyMap too.
            final MapImage icon = new MapImage(iconLocation, 32, 32);
            icon.setDisplayWidth(size);
            icon.setDisplayHeight(size);
            icon.centerAnchors();

            final StringBuilder title = new StringBuilder(landmark.name());
            if (landmark.type() == MapLandmark.Type.PLATFORM) {
                title.append("\nPlatform ").append(landmark.symbol());
            }
            if (!landmark.description().isEmpty()) {
                title.append("\nRoutes:\n- ").append(landmark.description().replace(", ", "\n- "));
            }

            final MarkerOverlay marker = new MarkerOverlay(MTRMap.MOD_ID,
                    new BlockPos(landmark.x(), landmark.y(), landmark.z()), icon);
            marker.setDimension(world.dimension());
            marker.setLabel("");
            marker.setTitle(title.toString());
            marker.setActiveUIs(Context.UI.Fullscreen);
            marker.setDisplayOrder(JourneyMapLayerOrder.LANDMARK);
            out.put(landmark.id(), marker);
        }
    }

    private static boolean shouldShow(MapLandmark landmark) {
        if (!landmark.hasRoutes() && !MTRMapConfig.INSTANCE.showEmptyStation.get()
                && landmark.type() != MapLandmark.Type.DEPOT) {
            return false;
        }
        return switch (landmark.type()) {
            case STATION -> MTRMapConfig.INSTANCE.showStationLandmarks.get();
            case PLATFORM -> MTRMapConfig.INSTANCE.showPlatformLandmarks.get();
            case DEPOT -> MTRMapConfig.INSTANCE.showDepotLandmarks.get();
        };
    }

    static void placeTestMarker(BlockPos pos, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        final IClientAPI api = getJourneyMapAPI();
        if (api == null) {
            MTRMap.LOGGER.warn("[MTRMap] JourneyMap API not initialized yet - try again after JourneyMap loads");
            return;
        }

        final MapImage icon = new MapImage(markerIcon("train", false), 32, 32);
        icon.setDisplayWidth(12);
        icon.setDisplayHeight(12);
        icon.centerAnchors();
        icon.setColor(0xFF00AAFF);
        final MarkerOverlay marker = new MarkerOverlay(MTRMap.MOD_ID, pos, icon);
        marker.setDimension(dimension);
        marker.setActiveUIs(Context.UI.Fullscreen);
        marker.setDisplayOrder(JourneyMapLayerOrder.LANDMARK);
        marker.setLabel("[MTR] Test marker");
        marker.setTitle("MTR Map Overlay JourneyMap integration works!");

        if (testMarker != null) {
            try {
                api.remove(testMarker);
            } catch (Exception ignored) {
            }
        }
        try {
            api.show(marker);
        } catch (Exception e) {
            MTRMap.LOGGER.error("[MTRMap] Failed to show test marker", e);
            return;
        }
        testMarker = marker;
        MTRMap.LOGGER.info("[MTRMap] JourneyMap test marker placed at {}", pos.toShortString());
    }

    // Station mode: one marker per station, placed at the station centre
    private static void collectStationMarkers(Map<String, MarkerOverlay> out, MTRDataSummary dataSummary, Level world) {
        if (!MTRMapConfig.INSTANCE.showStationLandmarks.get()) {
            return;
        }

        for (Station station : collectStations()) {
            if (station == null) {
                continue;
            }
            if (shouldBeFilteredOut(station, dataSummary)) {
                continue;
            }
            out.put(getMarkerId(station), createStationMarker(station, dataSummary, world));
        }
    }

    // Platform mode: one marker per platform, labelled with the platform
    // number and showing the station name plus route/destination info on hover
    private static void collectPlatformMarkers(Map<String, MarkerOverlay> out, DataCache dataCache, Level world) {
        if (!MTRMapConfig.INSTANCE.showPlatformLandmarks.get()) {
            return;
        }

        // Build a map of platformId -> routes passing through it, since
        // platform.routes is not populated on the client side
        Map<Long, List<Route>> platformRouteMap = new HashMap<>();
        for (Route route : collectRoutes()) {
            // MTR 3: the ordered stops are route.platformIds (each entry is a
            // Route.RoutePlatform with public platformId/customDestination fields)
            List<Route.RoutePlatform> rpList = route == null ? null : route.platformIds;
            if (rpList == null) {
                continue;
            }
            for (Route.RoutePlatform rpd : rpList) {
                if (rpd != null) {
                    platformRouteMap.computeIfAbsent(rpd.platformId, k -> new ArrayList<>()).add(route);
                }
            }
        }

        // MTR 3: station -> platform membership lives in DataCache
        // (platformIdToStation); MTR 4 read it from Station.savedRails
        final Map<Long, List<Platform>> platformsByStation = MtrCompat.platformsByStation(dataCache);

        for (Station station : collectStations()) {
            if (station == null) {
                continue;
            }
            String stationName = IGui.formatStationName(station.name);
            if (stationName == null || stationName.isEmpty()) {
                continue;
            }

            for (Platform platform : platformsByStation.getOrDefault(station.id, List.of())) {
                if (platform == null) {
                    continue;
                }

                final BlockPos midPos = platform.getMidPos();
                if (midPos == null) {
                    continue;
                }

                String markerId = getMarkerId(platform);

                // Label = platform name/number
                String platformName = platform.name;
                if (platformName == null || platformName.isEmpty()) {
                    platformName = String.valueOf(platform.id);
                }

                // Hover text = station name + route names with destinations
                StringBuilder title = new StringBuilder(stationName);
                List<String> routeInfos = buildRouteInfos(dataCache, platform, platformRouteMap.get(platform.id));
                if (!routeInfos.isEmpty()) {
                    title.append("\n").append(String.join("\n", routeInfos));
                }

                out.put(markerId, createMarker(markerId, midPos, platformName, title.toString(),
                        station.transportMode, false, station.color, world));
            }
        }
    }

    private static void collectDepotMarkers(Map<String, MarkerOverlay> out, Level world) {
        for (Depot depot : collectDepots()) {
            if (depot == null) {
                continue;
            }
            String depotName = IGui.formatStationName(depot.name);
            String markerId = getMarkerId(depot);
            // MTR 3: Depot has no getMaxY(); the marker is placed at the depot
            // centre via the shared MtrCompat.areaY helper
            final BlockPos center = depot.getCenter();
            BlockPos pos = new BlockPos(
                    center == null ? 0 : center.getX(),
                    MtrCompat.areaY(center),
                    center == null ? 0 : center.getZ());
            out.put(markerId, createMarker(markerId, pos, depotName, depotName, depot.transportMode, true,
                    depot.color, world));
        }
    }

    /**
     * Build "RouteName → Destination" strings for a platform. Routes are
     * deduplicated by name; the destination is the last station on the route
     * (or its custom destination, if set).
     */
    private static List<String> buildRouteInfos(DataCache dataCache, Platform platform, List<Route> routes) {
        List<String> routeInfos = new ArrayList<>();
        if (routes == null) {
            return routeInfos;
        }

        Set<String> seenRoutes = new HashSet<>();
        for (Route route : routes) {
            if (route == null) {
                continue;
            }
            String routeName = route.name;
            if (routeName == null || routeName.isEmpty()) {
                continue;
            }
            if (!seenRoutes.add(routeName)) {
                continue;
            }

            String formattedRouteName = IGui.formatStationName(routeName.split("\\|\\|")[0]);
            if (formattedRouteName == null) {
                continue;
            }
            StringBuilder routeInfo = new StringBuilder(formattedRouteName);
            String destination = findDestinationForPlatform(dataCache, route, platform);
            if (destination != null && !destination.isEmpty()) {
                routeInfo.append(" → ").append(destination);
            }
            routeInfos.add(routeInfo.toString());
        }
        return routeInfos;
    }

    private static String findDestinationForPlatform(DataCache dataCache, Route route, Platform currentPlatform) {
        try {
            // MTR 3: a route stores its ordered stops as route.platformIds, each
            // entry only carrying the platform id
            List<Route.RoutePlatform> routePlatforms = route == null ? null : route.platformIds;
            if (routePlatforms == null || routePlatforms.isEmpty()) {
                return null;
            }

            Route.RoutePlatform lastPlatformData = routePlatforms.get(routePlatforms.size() - 1);
            if (lastPlatformData == null) {
                return null;
            }
            String customDest = lastPlatformData.customDestination;
            if (customDest != null && !customDest.isEmpty() && !Route.destinationIsReset(customDest)) {
                return customDest;
            }

            // MTR 3: the platform object is resolved through the data cache
            // (MTR 4 had RoutePlatformData.getPlatform())
            Platform lastPlatform = dataCache == null || dataCache.platformIdMap == null ? null
                    : dataCache.platformIdMap.get(lastPlatformData.platformId);
            if (lastPlatform != null) {
                // MTR 3: the station name is looked up by platform id, and may be null
                String stationName = MtrCompat.stationNameOfPlatform(dataCache, lastPlatform.id);
                return stationName == null ? null : IGui.formatStationName(stationName);
            }
        } catch (Exception e) {
            MTRMap.LOGGER.debug("[MTRMap] Error finding destination: {}", e.getMessage());
        }
        return null;
    }

    private static MarkerOverlay createStationMarker(Station station, MTRDataSummary mtrDataSummary, Level world) {
        String markerId = getMarkerId(station);
        String stationName = IGui.formatStationName(station.name);
        final BlockPos center = station.getCenter();
        BlockPos pos = new BlockPos(
                center == null ? 0 : center.getX(),
                center == null ? 0 : center.getY(),
                center == null ? 0 : center.getZ());

        StringBuilder desc = new StringBuilder();
        // MTR 3: Station exposes a single "zone" int (MTR 4 had getZone1())
        desc.append("Fare zone: ").append(station.zone);
        List<MTRDataSummary.BasicRouteInfo> routesInStation = mtrDataSummary.getRoutesInStation(station);
        if (routesInStation != null && !routesInStation.isEmpty()) {
            desc.append("\nRoutes: ");
            for (int i = 0; i < routesInStation.size(); i++) {
                if (i > 0) {
                    desc.append(", ");
                }
                desc.append(IGui.formatStationName(routesInStation.get(i).name()));
            }
        }

        return createMarker(markerId, pos, stationName, desc.toString(), station.transportMode, false,
                station.color, world);
    }

    private static MarkerOverlay createMarker(String markerId, BlockPos pos, String label, String title,
            TransportMode transportMode, boolean isDepot, int color, Level world) {
        ResourceLocation iconRL = getMarkerIcon(transportMode, isDepot);
        MapImage icon = new MapImage(iconRL, 32, 32);
        icon.setColor(color | 0xFF000000); // ensure alpha
        icon.setDisplayWidth(isDepot ? 10 : 12);
        icon.setDisplayHeight(isDepot ? 10 : 12);
        icon.centerAnchors();

        // v2 API generates the marker id internally; markerId is kept for logging only
        MarkerOverlay marker = new MarkerOverlay(MTRMap.MOD_ID, pos, icon);
        marker.setDimension(world.dimension());
        marker.setLabel("");
        marker.setTitle(title);
        marker.setActiveUIs(Context.UI.Fullscreen);
        marker.setDisplayOrder(JourneyMapLayerOrder.LANDMARK);
        return marker;
    }

    private static ResourceLocation getMarkerIcon(TransportMode transportMode, boolean isDepot) {
        return markerIcon(getTransportModeName(transportMode), isDepot);
    }

    private static ResourceLocation markerIcon(String modeName, boolean isDepot) {
        String type = isDepot ? "depot" : "station";
        return new ResourceLocation(MTRMap.MOD_ID,
                "textures/atlas/marker/" + modeName + "_" + type + ".png");
    }

    private static String getTransportModeName(TransportMode transportMode) {
        // MTR 3: transportMode is a public field that can be null on partially
        // synced data - fall back to the train icons instead of failing
        return (transportMode == null ? TransportMode.TRAIN : transportMode).toString()
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String getMarkerId(NameColorDataBase data) {
        String type = (data instanceof Station) ? "station"
                : (data instanceof Depot) ? "depot" : "platform";
        // MTR 3: no getHexId(); the public id field is formatted by MtrCompat
        return getTransportModeName(data.transportMode) + "_" + type + "_"
                + MtrCompat.hexId(data.id);
    }

    private static boolean shouldBeFilteredOut(Station station, MTRDataSummary dataSummary) {
        List<MTRDataSummary.BasicRouteInfo> routes = dataSummary.getRoutesInStation(station);
        return !MTRMapConfig.INSTANCE.showEmptyStation.get() && (routes == null || routes.isEmpty());
    }

    /**
     * MTR 3 keeps the client-side stations in the static {@code ClientData.STATIONS}
     * field; MTR 4 aggregated a streaming instance and a dashboard instance here.
     * The set is copied because MTR mutates it from its own tick handling.
     */
    private static List<Station> collectStations() {
        try {
            return new ArrayList<>(ClientData.STATIONS);
        } catch (Exception e) {
            MTRMap.LOGGER.error("[MTRMap] Error accessing MTR station datasets: ", e);
            return new ArrayList<>();
        }
    }

    private static List<Depot> collectDepots() {
        try {
            return new ArrayList<>(ClientData.DEPOTS);
        } catch (Exception e) {
            MTRMap.LOGGER.error("[MTRMap] Error accessing MTR depot datasets: ", e);
            return new ArrayList<>();
        }
    }

    private static List<Route> collectRoutes() {
        try {
            return new ArrayList<>(ClientData.ROUTES);
        } catch (Exception e) {
            MTRMap.LOGGER.error("[MTRMap] Error accessing MTR route datasets: ", e);
            return new ArrayList<>();
        }
    }

    /**
     * Gets the JourneyMap API via reflection so this class never needs a
     * direct reference to the plugin class.
     */
    private static IClientAPI getJourneyMapAPI() {
        try {
            Class<?> pluginClass = Class.forName("com.lx862.mtrmap.integration.journeymap.MTRJourneyMapPlugin");
            return (IClientAPI) pluginClass.getMethod("getAPI").invoke(null);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            MTRMap.LOGGER.error("[MTRMap] Failed to get JourneyMap API", e);
            return null;
        }
    }
}
