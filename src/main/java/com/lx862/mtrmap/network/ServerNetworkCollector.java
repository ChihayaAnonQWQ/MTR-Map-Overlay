package com.lx862.mtrmap.network;

import com.lx862.mtrmap.MTRMap;
import com.lx862.mtrmap.mapdata.MapDataBuilder;
import com.lx862.mtrmap.mixin.RailwayDataAccessorMixin;
import com.lx862.mtrmap.mixin.SidingAccessorMixin;
import com.lx862.mtrmap.mtr.MtrCompat;
import mtr.data.Depot;
import mtr.data.Platform;
import mtr.data.Rail;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.Siding;
import mtr.data.Station;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server-side collector for the full-network map snapshot.
 *
 * <p>MTR 3 keeps one authoritative {@link RailwayData} per level, reachable
 * through {@link RailwayData#getInstance(net.minecraft.world.level.Level)}, and
 * everything runs on the server thread (the packet handler is registered with
 * {@code consumerMainThread}). MTR 4 instead exposed a list of background
 * {@code Simulator} instances that had to be read on their own threads, which
 * the previous version of this collector did.</p>
 *
 * <p>Route colours come from MTR's own driving paths where MTR generated one
 * (the {@code Siding.path} of a depot's sidings, read through
 * {@link SidingAccessorMixin}); routes without a generated path are snapped onto
 * the rail graph by {@link MapDataBuilder}.</p>
 */
public final class ServerNetworkCollector {

    private ServerNetworkCollector() {
    }

    /**
     * Collect a full-network snapshot for every dimension (or for one
     * {@code dimensionFilter}), independent of any receiver. Keeping collection
     * and delivery apart lets a single request fan out to one player without
     * re-reading MTR state per receiver.
     */
    public static List<NetworkSnapshotCodec.PendingDimension> collectAll(MinecraftServer server,
            String dimensionFilter) {
        final List<NetworkSnapshotCodec.PendingDimension> dimensions = new ArrayList<>();
        if (server == null) {
            return dimensions;
        }
        for (ServerLevel level : server.getAllLevels()) {
            final String dimensionKey = MtrCompat.dimensionKey(level);
            if (dimensionFilter != null && !dimensionFilter.equals(dimensionKey)) {
                continue;
            }
            try {
                final RailwayData railwayData = RailwayData.getInstance(level);
                if (railwayData == null) {
                    continue;
                }
                final Map<BlockPos, Map<BlockPos, Rail>> rails = railsOf(railwayData);
                dimensions.add(collect(railwayData, rails, dimensionKey, computeHash(railwayData, rails)));
            } catch (Throwable e) {
                MTRMap.LOGGER.error("[MTRMap] Failed to collect the network snapshot for {}: {}",
                        dimensionKey, e.getMessage(), e);
            }
        }
        return dimensions;
    }

    public static void collectAndSend(ServerPlayer player, String dimensionFilter) {
        final MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        final long requestTime = System.currentTimeMillis();
        final List<NetworkSnapshotCodec.PendingDimension> dimensions = collectAll(server, dimensionFilter);
        for (int index = 0; index < dimensions.size(); index++) {
            send(player, dimensions.get(index), requestTime, index);
        }
    }

    /**
     * Compute a lightweight change-detection hash per dimension. The client
     * compares against its cached hashes and only pulls full snapshots for
     * dimensions that actually changed. Cost is O(network size) with trivial
     * per-element work, so polling is cheap.
     */
    public static void sendProbeResponse(ServerPlayer player) {
        final MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        final List<NetworkProbeResponse.DimensionHash> hashes = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            try {
                final RailwayData railwayData = RailwayData.getInstance(level);
                if (railwayData == null) {
                    continue;
                }
                final Map<BlockPos, Map<BlockPos, Rail>> rails = railsOf(railwayData);
                hashes.add(new NetworkProbeResponse.DimensionHash(MtrCompat.dimensionKey(level),
                        computeHash(railwayData, rails)));
            } catch (Throwable e) {
                MTRMap.LOGGER.debug("[MTRMap] Failed to hash {}: {}", level.dimension().location(), e.getMessage());
            }
        }
        if (!hashes.isEmpty()) {
            MTRNetwork.sendToPlayer(player, new NetworkProbeResponse(hashes));
        }
    }

    private static NetworkSnapshotCodec.PendingDimension collect(RailwayData railwayData,
            Map<BlockPos, Map<BlockPos, Rail>> rails, String dimensionKey, long hash) {
        // MTR rebuilds the id maps (platformIdMap, platformIdToStation, ...) in
        // DataCache#sync; make sure they describe the current state before the
        // snapshot resolves platforms through them.
        try {
            if (railwayData.dataCache != null) {
                railwayData.dataCache.sync();
            }
        } catch (Throwable e) {
            MTRMap.LOGGER.debug("[MTRMap] Failed to refresh the MTR data cache: {}", e.getMessage());
        }

        final MapDataBuilder.Input input = new MapDataBuilder.Input();
        input.dataCache = railwayData.dataCache;
        input.routes = railwayData.routes;
        input.stations = railwayData.stations;
        input.depots = railwayData.depots;
        input.rails = rails;
        input.realPaths = collectSidingDrivingPaths(railwayData);
        input.fallbackPathfinding = true;

        final MapDataBuilder.Result result = MapDataBuilder.build(input);
        // Diagnostic line: distinguishes "no rails at all" from "rails exist but
        // routes have no complete path", which is what makes route colours vanish.
        MTRMap.LOGGER.info("[MTRMap] Collected {}: {} rail nodes, {} rails, {} tracks, "
                        + "{} routes ({} from MTR driving paths, {} snapped onto rails, {} unroutable), {} landmarks",
                dimensionKey, input.rails.size(), result.physicalRails, result.tracks.size(), result.routes.size(),
                result.realPathRoutes, result.fallbackRoutes, result.unroutableRoutes, result.landmarks.size());
        final NetworkSnapshotCodec.PendingDimension dimension =
                new NetworkSnapshotCodec.PendingDimension(dimensionKey, hash);
        dimension.routes.addAll(result.routes);
        dimension.tracks.addAll(result.tracks);
        dimension.landmarks.addAll(result.landmarks);
        return dimension;
    }

    /**
     * MTR generates the driving path of a depot's trains into its sidings
     * ({@code Siding.path}). Group every generated path with the routes of its
     * depot so the map can colour the rails a train actually drives.
     */
    private static List<MapDataBuilder.RealPath> collectSidingDrivingPaths(RailwayData railwayData) {
        final List<MapDataBuilder.RealPath> realPaths = new ArrayList<>();
        for (Siding siding : railwayData.sidings) {
            try {
                final List<PathData> path = pathOf(siding);
                if (path == null || path.isEmpty()) {
                    continue;
                }
                final Depot depot = railwayData.dataCache == null ? null
                        : railwayData.dataCache.sidingIdToDepot.get(siding.id);
                final List<Route> candidates = MtrCompat.depotRoutes(railwayData.dataCache, depot);
                if (candidates.isEmpty()) {
                    continue;
                }
                final Route primary = candidates.get(0);
                realPaths.add(new MapDataBuilder.RealPath(candidates, "siding:" + MtrCompat.hexId(siding.id),
                        primary.name, primary.color, path));
            } catch (Throwable e) {
                MTRMap.LOGGER.debug("[MTRMap] Failed to read a siding path on the server: {}", e.getMessage());
            }
        }
        return realPaths;
    }

    private static Map<BlockPos, Map<BlockPos, Rail>> railsOf(RailwayData railwayData) {
        try {
            final Map<BlockPos, Map<BlockPos, Rail>> rails =
                    ((RailwayDataAccessorMixin) (Object) railwayData).getRails();
            return rails == null ? Map.of() : rails;
        } catch (Throwable e) {
            MTRMap.LOGGER.debug("[MTRMap] Failed to read the server rail map: {}", e.getMessage());
            return Map.of();
        }
    }

    private static List<PathData> pathOf(Siding siding) {
        return ((SidingAccessorMixin) (Object) siding).getPath();
    }

    /** Cheap O(network) content hash for change detection. */
    private static long computeHash(RailwayData railwayData, Map<BlockPos, Map<BlockPos, Rail>> rails) {
        long hash = 0;

        int connectionCount = 0;
        if (rails != null) {
            for (Map.Entry<BlockPos, Map<BlockPos, Rail>> entry : rails.entrySet()) {
                final Map<BlockPos, Rail> connections = entry.getValue();
                if (connections == null) {
                    continue;
                }
                connectionCount += connections.size();
                for (BlockPos to : connections.keySet()) {
                    hash = hash * 31 + MtrCompat.railKey(entry.getKey(), to).hashCode();
                }
            }
        }
        hash = hash * 31 + connectionCount;

        for (Route route : railwayData.routes) {
            hash = hash * 31 + (int) route.id;
            hash = hash * 31 + Objects.hashCode(route.name);
            hash = hash * 31 + route.color;
            if (route.platformIds != null) {
                for (Route.RoutePlatform routePlatform : route.platformIds) {
                    hash = hash * 31 + Long.hashCode(routePlatform.platformId);
                    hash = hash * 31 + Objects.hashCode(routePlatform.customDestination);
                }
            }
        }

        for (Platform platform : railwayData.platforms) {
            final BlockPos pos = platform.getMidPos();
            hash = hash * 31 + Long.hashCode(platform.id);
            hash = hash * 31 + Objects.hashCode(platform.name);
            if (pos != null) {
                hash = hash * 31 + pos.getX();
                hash = hash * 31 + pos.getY();
                hash = hash * 31 + pos.getZ();
            }
        }

        for (Station station : railwayData.stations) {
            final BlockPos center = station.getCenter();
            hash = hash * 31 + Long.hashCode(station.id);
            hash = hash * 31 + Objects.hashCode(station.name);
            if (center != null) {
                hash = hash * 31 + center.getX();
                hash = hash * 31 + center.getZ();
            }
        }

        int sidingPathLength = 0;
        for (Siding siding : railwayData.sidings) {
            try {
                final List<PathData> path = pathOf(siding);
                sidingPathLength += path == null ? 0 : path.size();
            } catch (Throwable ignored) {
                // A missing path is not a hash failure.
            }
        }

        for (Depot depot : railwayData.depots) {
            final BlockPos center = depot.getCenter();
            hash = hash * 31 + Long.hashCode(depot.id);
            hash = hash * 31 + Objects.hashCode(depot.name);
            if (center != null) {
                hash = hash * 31 + center.getX();
                hash = hash * 31 + center.getZ();
            }
            hash = hash * 31 + (depot.routeIds == null ? 0 : depot.routeIds.size());
        }
        hash = hash * 31 + sidingPathLength;

        return hash;
    }

    private static void send(ServerPlayer player, NetworkSnapshotCodec.PendingDimension dimension, long requestTime,
            int dimensionIndex) {
        try {
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream(1 << 16);
            final DataOutputStream dataOut = new DataOutputStream(byteOut);
            NetworkSnapshotCodec.writeDimensionList(dataOut, List.of(dimension));
            dataOut.flush();
            final byte[] payload = byteOut.toByteArray();
            if (payload.length > NetworkChunkAssembler.MAX_BYTES) {
                throw new IllegalArgumentException("Snapshot exceeds transfer size limit: " + payload.length);
            }

            final int totalChunks = Math.max(1, (payload.length - 1) / NetworkChunkAssembler.CHUNK_SIZE + 1);
            if (totalChunks > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Snapshot requires too many chunks: " + totalChunks);
            }
            final int transferId = (int) (requestTime ^ (31 * payload.length) ^ (dimensionIndex * 1_000_003))
                    ^ player.getUUID().hashCode();
            for (int chunk = 0; chunk < totalChunks; chunk++) {
                final int from = chunk * NetworkChunkAssembler.CHUNK_SIZE;
                final int to = Math.min(payload.length, from + NetworkChunkAssembler.CHUNK_SIZE);
                final byte[] slice = new byte[to - from];
                System.arraycopy(payload, from, slice, 0, slice.length);
                MTRNetwork.sendToPlayer(player,
                        new NetworkSyncChunk(transferId, (short) chunk, (short) totalChunks,
                                dimension.snapshotHash, slice));
            }

            MTRMap.LOGGER.info("[MTRMap] Sent full-network snapshot for {} to {} ({} routes, {} rails, {} bytes, {} chunk(s))",
                    dimension.dimensionId, player.getGameProfile().getName(),
                    dimension.routes.size(), dimension.tracks.size(), payload.length, totalChunks);
        } catch (Throwable e) {
            MTRMap.LOGGER.error("[MTRMap] Failed to send network snapshot for {}: {}",
                    dimension.dimensionId, e.getMessage(), e);
        }
    }
}
