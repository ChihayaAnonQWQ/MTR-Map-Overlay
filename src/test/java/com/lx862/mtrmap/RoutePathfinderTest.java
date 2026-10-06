package com.lx862.mtrmap;

import com.lx862.mtrmap.mapdata.MapTrack;
import com.lx862.mtrmap.mapdata.RoutePathfinder;
import com.lx862.mtrmap.mapdata.TrackSampler;
import com.lx862.mtrmap.mtr.MtrCompat;
import mtr.data.Platform;
import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless verification for the no-depot route fallback.
 *
 * <p>MTR 3 port: positions are {@link BlockPos}, rails are built with
 * {@code new Rail(BlockPos, RailAngle, BlockPos, RailAngle, RailType, TransportMode)},
 * a rail carries no id of its own (it is identified by its endpoint pair) and
 * {@code RoutePathfinder#buildGraph} therefore takes the node-to-node rail map
 * directly instead of a separate rail collection.</p>
 */
class RoutePathfinderTest {

    /** One rail together with the two network nodes it connects. */
    private record TestRail(BlockPos start, BlockPos end, Rail rail) {
        String id() {
            return MtrCompat.railKey(start, end);
        }
    }

    private static long nextPlatformId = 1;

    private static BlockPos node(int x, int z) {
        return new BlockPos(x, 64, z);
    }

    private static RailAngle angleOf(BlockPos from, BlockPos to) {
        final double degrees = Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()));
        final int index = ((int) Math.round(degrees / 22.5) + 16) % 16;
        return RailAngle.values()[index];
    }

    private static TestRail rail(BlockPos start, BlockPos end) {
        final Rail rail = new Rail(start, angleOf(start, end), end, angleOf(end, start),
                RailType.IRON, TransportMode.TRAIN);
        assertTrue(rail.isValid(), "test rail must be a valid MTR 3 rail: " + start + " -> " + end);
        assertTrue(rail.getLength() > 0, "test rail must have a positive length");
        return new TestRail(start, end, rail);
    }

    private static Platform platform(TestRail rail) {
        return new Platform(nextPlatformId++, TransportMode.TRAIN, rail.start(), rail.end());
    }

    private static RoutePathfinder.Graph graph(TestRail... rails) {
        final Map<BlockPos, Map<BlockPos, Rail>> positionsToRail = new HashMap<>();
        for (TestRail rail : rails) {
            positionsToRail.computeIfAbsent(rail.start(), ignored -> new HashMap<>()).put(rail.end(), rail.rail());
        }
        return RoutePathfinder.buildGraph(positionsToRail);
    }

    private static String key(double[] point) {
        return Double.doubleToLongBits(point[0]) + ":" + Double.doubleToLongBits(point[1]);
    }

    @Test
    void fallbackUsesTheExactTrackLayerSamples() {
        final TestRail first = rail(node(0, 0), node(100, 0));
        final TestRail middle = rail(node(100, 0), node(200, 0));
        final TestRail last = rail(node(200, 0), node(300, 0));
        final RoutePathfinder.Graph graph = graph(first, middle, last);
        final List<Platform> platforms = List.of(platform(first), platform(last));

        final List<RoutePathfinder.SegmentPath> segments =
                RoutePathfinder.findRoutePath(graph, platforms, false);
        final List<MapTrack> routeTracks = RoutePathfinder.toTracks(graph, platforms, segments, false);
        assertFalse(routeTracks.isEmpty());

        final Set<String> grayTrackSamples = new HashSet<>();
        for (TestRail rail : List.of(first, middle, last)) {
            TrackSampler.sample(rail.rail()).forEach(point -> grayTrackSamples.add(key(point)));
        }
        for (MapTrack routeTrack : routeTracks) {
            for (double[] routePoint : routeTrack.points) {
                assertTrue(grayTrackSamples.contains(key(routePoint)),
                        "route point must be one of the exact gray-track samples");
            }
        }
    }

    @Test
    void disconnectedNetworkNeverProducesAStationChord() {
        final TestRail first = rail(node(0, 0), node(100, 0));
        final TestRail isolated = rail(node(10_000, 10_000), node(10_100, 10_000));
        final RoutePathfinder.Graph graph = graph(first, isolated);
        final List<Platform> platforms = List.of(platform(first), platform(isolated));

        final List<RoutePathfinder.SegmentPath> segments =
                RoutePathfinder.findRoutePath(graph, platforms, false);
        final List<MapTrack> routeTracks = RoutePathfinder.toTracks(graph, platforms, segments, false);

        assertTrue(routeTracks.isEmpty(), "an unroutable route must be omitted instead of drawn as a straight line");
    }

    @Test
    void reverseTraversalStillUsesTrackSamples() {
        final TestRail first = rail(node(0, 0), node(100, 0));
        final TestRail last = rail(node(100, 0), node(200, 0));
        final RoutePathfinder.Graph graph = graph(first, last);
        final List<Platform> platforms = List.of(platform(last), platform(first));

        final List<RoutePathfinder.SegmentPath> segments =
                RoutePathfinder.findRoutePath(graph, platforms, false);
        final List<MapTrack> routeTracks = RoutePathfinder.toTracks(graph, platforms, segments, false);

        assertFalse(routeTracks.isEmpty());
        assertTrue(routeTracks.stream().allMatch(track -> track.points.size() >= 2));
    }

    @Test
    void fallbackReusesTheCanonicalTrackLayer() {
        final TestRail first = rail(node(0, 0), node(100, 0));
        final TestRail middle = rail(node(100, 0), node(200, 0));
        final TestRail last = rail(node(200, 0), node(300, 0));
        final RoutePathfinder.Graph graph = graph(first, middle, last);
        final List<Platform> platforms = List.of(platform(first), platform(last));
        final List<RoutePathfinder.SegmentPath> segments =
                RoutePathfinder.findRoutePath(graph, platforms, false);
        final Map<String, MapTrack> sampledTracks = new HashMap<>();
        for (TestRail rail : List.of(first, middle, last)) {
            sampledTracks.put(rail.id(), new MapTrack(rail.id(), TrackSampler.sample(rail.rail())));
        }

        final List<MapTrack> routeTracks =
                RoutePathfinder.toTracks(graph, platforms, segments, false, sampledTracks);
        assertFalse(routeTracks.isEmpty());
        for (MapTrack routeTrack : routeTracks) {
            assertSame(sampledTracks.get(routeTrack.id), routeTrack);
        }

        sampledTracks.remove(middle.id());
        assertTrue(RoutePathfinder.toTracks(graph, platforms, segments, false, sampledTracks).isEmpty(),
                "a route must not reference a rail missing from the track layer");
    }
}
