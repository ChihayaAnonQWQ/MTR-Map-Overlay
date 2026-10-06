package com.lx862.mtrmap;

import com.lx862.mtrmap.mapdata.MapDataBuilder;
import com.lx862.mtrmap.mapdata.MapLandmark;
import com.lx862.mtrmap.mapdata.MapRoute;
import com.lx862.mtrmap.mapdata.MapTrack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check of the single MTR 3 snapshot builder that now serves both the
 * client cache and the server network snapshot.
 *
 * <p>Covers the three layers - physical tracks, route ribbons (once from MTR's own
 * generated driving path, once from the strict rail-graph fallback) and station /
 * platform / depot landmarks - against real MTR 3 model objects.</p>
 */
class MapDataBuilderTest {

    private static MapDataBuilder.Input input(TestNetwork.Network network, boolean withRealPath) {
        final MapDataBuilder.Input input = new MapDataBuilder.Input();
        input.dataCache = network.dataCache();
        input.routes = network.routes();
        input.stations = network.stations();
        input.depots = network.depots();
        input.rails = network.railsByNode();
        input.fallbackPathfinding = true;
        if (withRealPath) {
            input.realPaths = List.of(new MapDataBuilder.RealPath(List.of(network.local()), "path:local",
                    network.local().name, network.local().color, network.localDrivingPath()));
        }
        return input;
    }

    @Test
    void buildsEveryPhysicalTrackExactlyOnce() {
        final TestNetwork.Network network = TestNetwork.build();
        final MapDataBuilder.Result result = MapDataBuilder.build(input(network, true));

        assertEquals(TestNetwork.physicalRailCount(network), result.tracks.size(),
                "both directions of a rail must collapse into one drawn track");
        for (MapTrack track : result.tracks) {
            assertTrue(track.points.size() >= 2, "every track needs at least two samples");
            assertNotNull(result.tracksById.get(track.id), "track lookup must contain every track");
        }
    }

    @Test
    void routeFromGeneratedDrivingPathUsesTrackLayerGeometry() {
        final TestNetwork.Network network = TestNetwork.build();
        final MapDataBuilder.Result result = MapDataBuilder.build(input(network, true));

        final MapRoute local = findRoute(result.routes, network.local().name);
        assertNotNull(local, "the route with a generated driving path must be present");
        assertFalse(local.trackIds.isEmpty(), "a real driving path must produce track strokes");
        for (String trackId : local.trackIds) {
            assertTrue(result.tracksById.containsKey(trackId),
                    "route strokes must reference the canonical TRACK layer");
        }
        assertTrue(local.stops.size() >= 3, "the Local route stops at three platforms");
    }

    @Test
    void routesWithoutGeneratedPathAreSnappedOntoTheRailGraph() {
        final TestNetwork.Network network = TestNetwork.build();
        final MapDataBuilder.Result result = MapDataBuilder.build(input(network, false));

        final Map<String, Integer> expectedStops = Map.of(
                network.express().name, 2,
                network.local().name, 3,
                network.localReturn().name, 3);
        for (Map.Entry<String, Integer> expected : expectedStops.entrySet()) {
            final MapRoute route = findRoute(result.routes, expected.getKey());
            assertNotNull(route, "route missing from the snapshot: " + expected.getKey());
            assertFalse(route.trackIds.isEmpty(),
                    "fallback must snap the route onto real track geometry: " + expected.getKey());
            assertEquals(expected.getValue().intValue(), route.stops.size(),
                    "stops must follow the route's platform list for " + expected.getKey());
        }
    }

    @Test
    void landmarksCoverStationsPlatformsAndDepots() {
        final TestNetwork.Network network = TestNetwork.build();
        final MapDataBuilder.Result result = MapDataBuilder.build(input(network, true));

        assertEquals(3, countType(result.landmarks, MapLandmark.Type.STATION), "one marker per station");
        assertEquals(3, countType(result.landmarks, MapLandmark.Type.PLATFORM), "one marker per platform");
        assertEquals(1, countType(result.landmarks, MapLandmark.Type.DEPOT), "one marker per depot");

        for (MapLandmark landmark : result.landmarks) {
            assertTrue(landmark.hasRoutes() || landmark.type() == MapLandmark.Type.DEPOT
                    || landmark.name() != null, "landmark metadata must survive the MTR 3 port");
        }
    }

    @Test
    void generatedPathsMatchRailsByGeometryNotIdentity() {
        final TestNetwork.Network network = TestNetwork.build();
        final MapDataBuilder.Input input = input(network, false);
        // The rails inside a Siding's path are separate instances with the same
        // geometry, exactly like MTR's separately deserialised server data.
        input.realPaths = List.of(new MapDataBuilder.RealPath(List.of(network.local()), "path:local",
                network.local().name, network.local().color, TestNetwork.copiedLocalDrivingPath(network)));

        final MapDataBuilder.Result result = MapDataBuilder.build(input);

        final MapRoute local = findRoute(result.routes, network.local().name);
        assertNotNull(local, "the route with a generated driving path must be present");
        assertFalse(local.trackIds.isEmpty(),
                "a driving path must resolve to the drawn track layer even when its Rail instances are copies");
        assertEquals(1, result.realPathRoutes, "exactly one route came from the generated path");
    }

    private static MapRoute findRoute(List<MapRoute> routes, String name) {
        for (MapRoute route : routes) {
            if (name.equals(route.name)) {
                return route;
            }
        }
        return null;
    }

    private static long countType(List<MapLandmark> landmarks, MapLandmark.Type type) {
        final List<MapLandmark> matching = new ArrayList<>();
        for (MapLandmark landmark : landmarks) {
            if (landmark.type() == type) {
                matching.add(landmark);
            }
        }
        return matching.size();
    }
}
