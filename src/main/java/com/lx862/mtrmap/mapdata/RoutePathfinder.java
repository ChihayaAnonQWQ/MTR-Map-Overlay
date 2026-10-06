package com.lx862.mtrmap.mapdata;

import com.lx862.mtrmap.mtr.MtrCompat;
import mtr.data.Platform;
import mtr.data.Rail;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Snaps MTR routes onto the actual rail network.
 *
 * <p>Builds a node graph from the rail set (rail node positions as graph
 * nodes, rails as weighted edges), anchors each platform to its own platform
 * rail (matching its endpoints, with a proximity fallback), and runs a
 * multi-source Dijkstra between consecutive platforms so route lines follow the
 * real track geometry (arcs included) instead of straight stop-to-stop
 * lines.</p>
 *
 * <p>MTR 3 note: rails live in {@code Map<BlockPos, Map<BlockPos, Rail>>} and
 * carry no id, so a physical rail is identified by its unordered endpoint pair
 * ({@link MtrCompat#railKey}); the corresponding Minecraft position type is
 * {@link BlockPos} and a curve sample is a {@link Vec3}.</p>
 */
public final class RoutePathfinder {

    private RoutePathfinder() {
    }

    /** Rails whose endpoints are both within this many blocks may anchor a platform. */
    private static final double PLATFORM_RAIL_SEARCH_RADIUS = 32.0;

    // -----------------------------------------------------------------------------------------------------------------
    // Graph
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * One directed traversal of a rail. {@code reversed} is true when the
     * traversal runs opposite to the rail's own geometry parameterization.
     */
    public static final class Edge {
        public final BlockPos from;
        public final BlockPos to;
        public final Rail rail;
        public final boolean reversed;
        public final double length;
        public final String railId;
        Edge(BlockPos from, BlockPos to, Rail rail, boolean reversed, String railId) {
            this.from = from;
            this.to = to;
            this.rail = rail;
            this.reversed = reversed;
            this.railId = railId;
            this.length = rail.getLength();
        }
    }

    /**
     * Node graph over the rail network. Nodes are rail node positions; edges
     * are directed rail traversals (both directions available).
     */
    public static final class Graph {
        final Map<BlockPos, List<Edge>> adjacency = new HashMap<>();
        final Map<String, Rail> railById = new HashMap<>();
        /** Identity lookup back to the rail id (MTR 3 rails carry no id of their own). */
        final Map<Rail, String> railIdByIdentity = new java.util.IdentityHashMap<>();
        /** rail id -> [naturalStart, naturalEnd] node positions. */
        final Map<String, BlockPos[]> railEnds = new HashMap<>();
    }

    /**
     * Build the graph from MTR's node-to-node rail index
     * ({@code RailwayData.rails} on the server, {@code ClientData.RAILS} on the
     * client). The index may contain one or both directions per rail; missing
     * reverse directions are added so the graph traverses both ways.
     */
    public static Graph buildGraph(Map<BlockPos, Map<BlockPos, Rail>> positionsToRail) {
        final Graph graph = new Graph();
        if (positionsToRail == null) {
            return graph;
        }

        for (Map.Entry<BlockPos, Map<BlockPos, Rail>> entry : positionsToRail.entrySet()) {
            final Map<BlockPos, Rail> connections = entry.getValue();
            if (connections == null) {
                continue;
            }
            for (Map.Entry<BlockPos, Rail> railEntry : connections.entrySet()) {
                final Rail rail = railEntry.getValue();
                if (!MtrCompat.isDrawableRail(rail)) {
                    continue;
                }
                final String railId = MtrCompat.railKey(entry.getKey(), railEntry.getKey());
                graph.railById.putIfAbsent(railId, rail);
                graph.railIdByIdentity.putIfAbsent(rail, railId);
                addDirected(graph, entry.getKey(), railEntry.getKey(), rail, railId);
            }
        }

        // Add reverse traversals for any rail that only has one direction indexed.
        for (Map.Entry<String, Rail> entry : graph.railById.entrySet()) {
            final BlockPos[] ends = graph.railEnds.get(entry.getKey());
            if (ends == null) {
                continue;
            }
            boolean hasReverse = false;
            for (Edge edge : graph.adjacency.getOrDefault(ends[1], List.of())) {
                if (edge.railId.equals(entry.getKey())) {
                    hasReverse = true;
                    break;
                }
            }
            if (!hasReverse) {
                addDirected(graph, ends[1], ends[0], entry.getValue(), entry.getKey());
            }
        }

        return graph;
    }

    private static void addDirected(Graph graph, BlockPos from, BlockPos to, Rail rail, String railId) {
        if (from == null || to == null || rail == null || from.equals(to)) {
            return;
        }
        if (!graph.railById.containsKey(railId)) {
            return;
        }

        // Determine the rail's own geometry orientation by comparing the
        // traversal start with the rail's parameterized start point.
        final BlockPos[] knownEnds = graph.railEnds.get(railId);
        final boolean natural;
        if (knownEnds != null && from.equals(knownEnds[0]) && to.equals(knownEnds[1])) {
            natural = true;
        } else if (knownEnds != null && from.equals(knownEnds[1]) && to.equals(knownEnds[0])) {
            natural = false;
        } else {
            natural = isNaturalOrientation(rail, from, to);
        }

        if (knownEnds == null) {
            graph.railEnds.put(railId, natural ? new BlockPos[]{from, to} : new BlockPos[]{to, from});
        }

        graph.adjacency.computeIfAbsent(from, k -> new ArrayList<>(2))
                .add(new Edge(from, to, rail, !natural, railId));
    }

    private static boolean isNaturalOrientation(Rail rail, BlockPos from, BlockPos to) {
        try {
            final double length = rail.getLength();
            final Vec3 startSample = rail.getPosition(0);
            final Vec3 endSample = rail.getPosition(length);
            if (startSample == null || endSample == null) {
                return true;
            }
            final double dStart = squareDistance(startSample.x, startSample.z, from.getX() + 0.5, from.getZ() + 0.5);
            final double dEnd = squareDistance(endSample.x, endSample.z, from.getX() + 0.5, from.getZ() + 0.5);
            return dStart <= dEnd;
        } catch (Throwable e) {
            return true;
        }
    }

    private static double squareDistance(double x1, double z1, double x2, double z2) {
        final double dx = x1 - x2;
        final double dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    private static double squareDistance(BlockPos a, BlockPos b) {
        return squareDistance(a.getX(), a.getZ(), b.getX(), b.getZ());
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Route path search
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * One edge of a route path: which rail and in which direction.
     */
    public static final class PathEdge {
        public final String hexId;
        public final boolean reversed;
        PathEdge(String hexId, boolean reversed) {
            this.hexId = hexId;
            this.reversed = reversed;
        }
    }

    /**
     * One inter-stop segment: the rail edges to traverse plus the network
     * entry/exit nodes of the two anchor platform rails.
     */
    public static final class SegmentPath {
        public final List<PathEdge> edges;
        /** Endpoint of the from-platform rail where the network path starts. */
        public final BlockPos entryNode;
        /** Endpoint of the to-platform rail where the network path ends. */
        public final BlockPos exitNode;

        SegmentPath(List<PathEdge> edges, BlockPos entryNode, BlockPos exitNode) {
            this.edges = edges;
            this.entryNode = entryNode;
            this.exitNode = exitNode;
        }
    }

    /**
     * Find the rail path for one route through its ordered platforms.
     *
     * @return one {@link SegmentPath} per inter-stop segment (size = stops-1,
     *         plus one extra closure entry for circular routes); an entry is
     *         {@code null} when no rail path was found for that segment. A
     *         caller must not replace a missing segment with a straight chord,
     *         because route colour is only valid when it lies on track geometry.
     */
    public static List<SegmentPath> findRoutePath(Graph graph, List<Platform> platforms, boolean circular) {
        final int stopCount = platforms.size();
        if (graph == null || stopCount < 2) {
            return null;
        }

        final List<SegmentPath> segments = new ArrayList<>();
        for (int i = 0; i < stopCount - 1; i++) {
            final SegmentPath segment = findSegmentPath(graph, platforms.get(i), platforms.get(i + 1));
            segments.add(segment);
        }

        if (circular) {
            segments.add(findSegmentPath(graph, platforms.get(stopCount - 1), platforms.get(0)));
        }
        return segments;
    }

    /**
     * Dijkstra between the two platform rails: sources are both endpoints of
     * {@code fromPlatform}'s rail, targets are both endpoints of
     * {@code toPlatform}'s rail; the two anchor rails are not traversed (their
     * geometry is added by the caller).
     */
    static SegmentPath findSegmentPath(Graph graph, Platform fromPlatform, Platform toPlatform) {
        final Rail fromRail = getPlatformRail(graph, fromPlatform);
        final Rail toRail = getPlatformRail(graph, toPlatform);
        if (fromRail == null || toRail == null) {
            return null;
        }

        final String fromRailId = railIdOf(graph, fromRail);
        final String toRailId = railIdOf(graph, toRail);
        if (fromRailId == null || toRailId == null) {
            return null;
        }

        final BlockPos[] fromEnds = graph.railEnds.get(fromRailId);
        final BlockPos[] toEnds = graph.railEnds.get(toRailId);
        if (fromEnds == null || toEnds == null) {
            return null;
        }

        if (fromRailId.equals(toRailId)) {
            // Adjacent platforms sharing one platform rail
            return new SegmentPath(List.of(), fromEnds[0], fromEnds[1]);
        }

        final Set<String> skip = Set.of(fromRailId, toRailId);

        final Map<BlockPos, Double> dist = new HashMap<>();
        final Map<BlockPos, Edge> prevEdge = new HashMap<>();
        final PriorityQueue<NodeEntry> queue = new PriorityQueue<>();

        for (BlockPos source : fromEnds) {
            dist.put(source, 0.0);
            queue.add(new NodeEntry(source, 0.0));
        }

        BlockPos settledTarget = null;
        while (!queue.isEmpty()) {
            final NodeEntry entry = queue.poll();
            final BlockPos current = entry.node;
            if (entry.dist > dist.getOrDefault(current, Double.POSITIVE_INFINITY)) {
                continue;
            }

            if (current.equals(toEnds[0]) || current.equals(toEnds[1])) {
                settledTarget = current;
                break;
            }

            for (Edge edge : graph.adjacency.getOrDefault(current, List.of())) {
                if (skip.contains(edge.railId)) {
                    continue;
                }
                final double nextDist = entry.dist + edge.length;
                final Double known = dist.get(edge.to);
                if (known == null || nextDist < known) {
                    dist.put(edge.to, nextDist);
                    prevEdge.put(edge.to, edge);
                    queue.add(new NodeEntry(edge.to, nextDist));
                }
            }
        }

        if (settledTarget == null) {
            return null;
        }

        final List<PathEdge> edges = new ArrayList<>();
        BlockPos node = settledTarget;
        while (prevEdge.containsKey(node)) {
            final Edge edge = prevEdge.get(node);
            edges.add(new PathEdge(edge.railId, edge.reversed));
            node = edge.from;
        }
        java.util.Collections.reverse(edges);
        final BlockPos entryNode = node;
        return new SegmentPath(edges, entryNode, settledTarget);
    }

    private static String railIdOf(Graph graph, Rail rail) {
        final String railId = graph.railIdByIdentity.get(rail);
        if (railId != null) {
            return railId;
        }
        for (Map.Entry<String, Rail> entry : graph.railById.entrySet()) {
            if (entry.getValue() == rail) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Resolve the platform's anchor rail. Prefers its exact endpoint pair;
     * falls back to the nearest rail whose endpoints are both close to the
     * platform centre.
     */
    static Rail getPlatformRail(Graph graph, Platform platform) {
        // MTR 3 platforms keep the two endpoint blocks of their platform rail.
        if (platform == null) {
            return null;
        }
        for (Map.Entry<String, BlockPos[]> entry : graph.railEnds.entrySet()) {
            final BlockPos[] ends = entry.getValue();
            if (platform.containsPos(ends[0]) && platform.containsPos(ends[1])) {
                return graph.railById.get(entry.getKey());
            }
        }

        // Fallback: nearest rail to the platform's mid position.
        final BlockPos mid = MtrCompat.midPos(platform);
        if (mid == null) {
            return null;
        }
        Rail best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<String, BlockPos[]> entry : graph.railEnds.entrySet()) {
            final BlockPos[] ends = entry.getValue();
            final double dist = Math.min(squareDistance(mid, ends[0]), squareDistance(mid, ends[1]));
            if (Math.sqrt(dist) > PLATFORM_RAIL_SEARCH_RADIUS || dist >= bestDist) {
                continue;
            }
            bestDist = dist;
            best = graph.railById.get(entry.getKey());
        }
        return best;
    }

    private record NodeEntry(BlockPos node, double dist) implements Comparable<NodeEntry> {
        @Override
        public int compareTo(NodeEntry other) {
            return Double.compare(dist, other.dist);
        }
    }

    /**
     * Resolve a complete route to the exact {@link MapTrack} strokes used by the
     * gray TRACK layer. No rails are concatenated and no new sampling is
     * performed; ROUTE therefore renders the same immutable polylines.
     */
    public static List<MapTrack> toTracks(Graph graph, List<Platform> platforms,
            List<SegmentPath> segments, boolean circular) {
        return toTracks(graph, platforms, segments, circular, null);
    }

    /**
     * Resolve route rails against an already sampled track layer. A snapshot
     * collector can share one geometry per physical rail across every route.
     * If no lookup is supplied, the rails are sampled on demand.
     */
    public static List<MapTrack> toTracks(Graph graph, List<Platform> platforms,
            List<SegmentPath> segments, boolean circular, Map<String, MapTrack> sampledTracks) {
        final int expectedSegments = platforms.size() - 1 + (circular ? 1 : 0);
        if (segments == null || segments.size() != expectedSegments || !isComplete(segments)) {
            return List.of();
        }

        final LinkedHashSet<String> railIds = new LinkedHashSet<>();
        for (int i = 0; i < segments.size(); i++) {
            final Platform from = platforms.get(i);
            final Platform to = platforms.get((i + 1) % platforms.size());
            final Rail fromRail = getPlatformRail(graph, from);
            final Rail toRail = getPlatformRail(graph, to);
            if (fromRail == null || toRail == null) {
                return List.of();
            }
            final String fromRailId = railIdOf(graph, fromRail);
            final String toRailId = railIdOf(graph, toRail);
            if (fromRailId == null || toRailId == null) {
                return List.of();
            }
            railIds.add(fromRailId);
            for (PathEdge edge : segments.get(i).edges) {
                railIds.add(edge.hexId);
            }
            railIds.add(toRailId);
        }

        final List<MapTrack> tracks = new ArrayList<>(railIds.size());
        for (String railId : railIds) {
            final MapTrack track;
            if (sampledTracks == null) {
                final Rail rail = graph.railById.get(railId);
                final List<double[]> points = rail == null ? null : TrackSampler.sample(rail);
                track = points == null ? null : new MapTrack(railId, points);
            } else {
                track = sampledTracks.get(railId);
            }
            if (track == null || track.points.size() < 2) {
                return List.of();
            }
            tracks.add(track);
        }
        return tracks;
    }

    private static boolean isComplete(List<SegmentPath> segments) {
        for (SegmentPath segment : segments) {
            if (segment == null) {
                return false;
            }
        }
        return true;
    }
}
