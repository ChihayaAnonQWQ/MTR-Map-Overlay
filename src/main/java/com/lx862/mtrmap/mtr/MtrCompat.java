package com.lx862.mtrmap.mtr;

import mtr.data.AreaBase;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.Platform;
import mtr.data.Rail;
import mtr.data.Route;
import mtr.data.SavedRailBase;
import mtr.data.Station;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MTR 3 adaptation layer.
 *
 * <p>MTR 3 keeps its data model in {@code mtr.data.*} / {@code mtr.client.*} and
 * works directly with Minecraft types ({@link BlockPos}, {@code Vec3}); MTR 4
 * instead exposes a platform independent {@code org.mtr.core} model with its own
 * {@code Position}, {@code RailMath}, {@code SimplifiedRoute} and
 * {@code MinecraftClientData} types. Everything in this mod that has to know
 * about that difference goes through this class, so the rest of the map layer
 * stays loader/version neutral.</p>
 *
 * <p>Notable MTR 3 facts encoded here:</p>
 * <ul>
 *   <li>routes expose their ordered stops as {@code route.platformIds}
 *       ({@code List<Route.RoutePlatform>}), not as {@code getRoutePlatforms()};</li>
 *   <li>the platform to station mapping lives in
 *       {@link DataCache#platformIdToStation}, not in
 *       {@code Station.savedRails};</li>
 *   <li>rails have no hex id, so a physical rail is identified by its endpoint
 *       pair (direction independent);</li>
 *   <li>names/colours/ids are public fields ({@code name}, {@code color},
 *       {@code id}) instead of getters.</li>
 * </ul>
 */
public final class MtrCompat {

    private MtrCompat() {
    }

    /** Dimension key in the same {@code namespace/path} form the map integrations use. */
    public static String dimensionKey(Level level) {
        return dimensionKey(level.dimension());
    }

    public static String dimensionKey(ResourceKey<Level> dimension) {
        return dimension.location().getNamespace() + "/" + dimension.location().getPath();
    }

    /** Human readable id of a data object (MTR 3 has no {@code getHexId()}). */
    public static String hexId(long id) {
        return Long.toHexString(id);
    }

    /**
     * Canonical, direction independent id of the physical rail between two
     * nodes. MTR 3 stores a connection under both directions, so the unordered
     * endpoint pair is what identifies one drawn track.
     */
    public static String railKey(BlockPos first, BlockPos second) {
        final long a = first.asLong();
        final long b = second.asLong();
        return a <= b ? Long.toHexString(a) + "-" + Long.toHexString(b)
                : Long.toHexString(b) + "-" + Long.toHexString(a);
    }

    /**
     * Node block a rail endpoint sits on. MTR returns block <em>centres</em>
     * (a node at {@code (0,64,0)} samples as {@code (0.5,64,0.5)}), so floor is
     * the correct inverse and matches the {@link BlockPos} keys of the rail map.
     */
    public static BlockPos nodeOf(net.minecraft.world.phys.Vec3 position) {
        return new BlockPos((int) Math.floor(position.x), (int) Math.floor(position.y),
                (int) Math.floor(position.z));
    }

    /**
     * {@link #railKey} derived from a rail's own geometry.
     *
     * <p>Needed because MTR deserialises each {@code Siding} (and therefore the
     * {@code PathData} / {@code Rail} objects inside it) independently from the
     * rail map, so object identity cannot be used to map a rail back to its
     * drawn track: the same physical rail exists as several equal-looking but
     * distinct instances.</p>
     */
    public static String railKeyOf(Rail rail) {
        try {
            final double length = rail.getLength();
            final net.minecraft.world.phys.Vec3 start = rail.getPosition(0);
            final net.minecraft.world.phys.Vec3 end = rail.getPosition(length);
            if (start == null || end == null) {
                return null;
            }
            return railKey(nodeOf(start), nodeOf(end));
        } catch (Throwable e) {
            return null;
        }
    }

    /** True when the rail is part of the train network this mod draws. */
    public static boolean isDrawableRail(Rail rail) {
        return rail != null && rail.isValid() && rail.transportMode == TransportMode.TRAIN;
    }

    /** One representative rail per physical connection, keyed by {@link #railKey}. */
    public static Map<String, Rail> uniqueRails(Map<BlockPos, Map<BlockPos, Rail>> rails) {
        final Map<String, Rail> result = new LinkedHashMap<>();
        if (rails == null) {
            return result;
        }
        for (Map.Entry<BlockPos, Map<BlockPos, Rail>> fromEntry : rails.entrySet()) {
            final Map<BlockPos, Rail> connections = fromEntry.getValue();
            if (connections == null) {
                continue;
            }
            for (Map.Entry<BlockPos, Rail> toEntry : connections.entrySet()) {
                final Rail rail = toEntry.getValue();
                if (!isDrawableRail(rail)) {
                    continue;
                }
                result.putIfAbsent(railKey(fromEntry.getKey(), toEntry.getKey()), rail);
            }
        }
        return result;
    }

    /** Endpoints of every physical rail, keyed by {@link #railKey}. */
    public static Map<String, BlockPos[]> railEndpoints(Map<BlockPos, Map<BlockPos, Rail>> rails) {
        final Map<String, BlockPos[]> result = new HashMap<>();
        if (rails == null) {
            return result;
        }
        for (Map.Entry<BlockPos, Map<BlockPos, Rail>> fromEntry : rails.entrySet()) {
            final Map<BlockPos, Rail> connections = fromEntry.getValue();
            if (connections == null) {
                continue;
            }
            for (Map.Entry<BlockPos, Rail> toEntry : connections.entrySet()) {
                if (!isDrawableRail(toEntry.getValue())) {
                    continue;
                }
                result.putIfAbsent(railKey(fromEntry.getKey(), toEntry.getKey()),
                        new BlockPos[]{fromEntry.getKey(), toEntry.getKey()});
            }
        }
        return result;
    }

    /** Identity lookup from a rail instance back to its {@link #railKey}. */
    public static Map<Rail, String> railKeysByIdentity(Map<BlockPos, Map<BlockPos, Rail>> rails) {
        final Map<Rail, String> result = new java.util.IdentityHashMap<>();
        if (rails == null) {
            return result;
        }
        for (Map.Entry<BlockPos, Map<BlockPos, Rail>> fromEntry : rails.entrySet()) {
            final Map<BlockPos, Rail> connections = fromEntry.getValue();
            if (connections == null) {
                continue;
            }
            for (Map.Entry<BlockPos, Rail> toEntry : connections.entrySet()) {
                final Rail rail = toEntry.getValue();
                if (!isDrawableRail(rail)) {
                    continue;
                }
                result.putIfAbsent(rail, railKey(fromEntry.getKey(), toEntry.getKey()));
            }
        }
        return result;
    }

    public static Station stationOfPlatform(DataCache dataCache, long platformId) {
        return dataCache == null || dataCache.platformIdToStation == null ? null
                : dataCache.platformIdToStation.get(platformId);
    }

    /** Station name of a platform, or {@code null} when the platform is unassigned. */
    public static String stationNameOfPlatform(DataCache dataCache, long platformId) {
        final Station station = stationOfPlatform(dataCache, platformId);
        return station == null ? null : station.name;
    }

    /** Platforms grouped by their station id, in one pass over the platform map. */
    public static Map<Long, List<Platform>> platformsByStation(DataCache dataCache) {
        final Map<Long, List<Platform>> result = new HashMap<>();
        if (dataCache == null || dataCache.platformIdMap == null || dataCache.platformIdToStation == null) {
            return result;
        }
        for (Platform platform : dataCache.platformIdMap.values()) {
            if (platform == null) {
                continue;
            }
            final Station station = dataCache.platformIdToStation.get(platform.id);
            if (station != null) {
                result.computeIfAbsent(station.id, ignored -> new ArrayList<>()).add(platform);
            }
        }
        return result;
    }

    /** Routes grouped by the platforms they call at. */
    public static Map<Long, List<Route>> routesByPlatform(Collection<Route> routes) {
        final Map<Long, List<Route>> result = new HashMap<>();
        if (routes == null) {
            return result;
        }
        for (Route route : routes) {
            if (route == null || route.platformIds == null) {
                continue;
            }
            for (Route.RoutePlatform routePlatform : route.platformIds) {
                if (routePlatform == null) {
                    continue;
                }
                result.computeIfAbsent(routePlatform.platformId, ignored -> new ArrayList<>()).add(route);
            }
        }
        return result;
    }

    public static boolean isCircular(Route route) {
        return route != null && (route.circularState == Route.CircularState.CLOCKWISE
                || route.circularState == Route.CircularState.ANTICLOCKWISE);
    }

    /**
     * Centre of an area, or {@code null} when MTR has no corners yet. MTR 3
     * computes the centre from {@code corner1}/{@code corner2}, which are unset
     * until a station/depot is drawn in game.
     */
    public static BlockPos areaCenter(AreaBase area) {
        try {
            return area == null ? null : area.getCenter();
        } catch (Throwable e) {
            return null;
        }
    }

    /** Mid position of a platform/siding, or {@code null} when MTR has none yet. */
    public static BlockPos midPos(SavedRailBase savedRail) {
        try {
            return savedRail == null ? null : savedRail.getMidPos();
        } catch (Throwable e) {
            return null;
        }
    }

    /** Route label used on landmarks and tooltips: name plus destination when set. */
    public static String routeLabel(Route route, int stopIndex) {
        final String destination = route.getDestination(stopIndex);
        if (destination == null || destination.isEmpty() || Route.destinationIsReset(destination)) {
            return route.name;
        }
        return route.name + "→" + destination;
    }

    /** First candidate route that calls at the platform, mirroring MTR's deterministic ordering. */
    public static Route resolveServingRoute(List<Route> candidates, long platformId) {
        if (candidates == null) {
            return null;
        }
        for (Route route : candidates) {
            if (route != null && route.containsPlatformId(platformId)) {
                return route;
            }
        }
        return null;
    }

    /** Depot routes resolved through the data cache (MTR 3 keeps route ids, not route objects). */
    public static List<Route> depotRoutes(DataCache dataCache, Depot depot) {
        final List<Route> result = new ArrayList<>();
        if (dataCache == null || dataCache.routeIdMap == null || depot == null || depot.routeIds == null) {
            return result;
        }
        for (Long routeId : depot.routeIds) {
            final Route route = routeId == null ? null : dataCache.routeIdMap.get(routeId);
            if (route != null) {
                result.add(route);
            }
        }
        return result;
    }

    /** Vertical centre of an area, used where MTR 4 exposed {@code getMaxY()}. */
    public static int areaY(BlockPos center) {
        return center == null ? 0 : center.getY();
    }
}
