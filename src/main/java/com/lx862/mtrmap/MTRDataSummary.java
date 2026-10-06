package com.lx862.mtrmap;

import com.lx862.mtrmap.config.MTRMapConfig;
import com.lx862.mtrmap.mtr.MtrCompat;
import com.lx862.mtrmap.wrapper.MTRRoute;
import com.lx862.mtrmap.wrapper.MTRRoutePlatform;
import com.lx862.mtrmap.wrapper.impl.MTRRouteImpl;
import it.unimi.dsi.fastutil.longs.Long2ObjectArrayMap;
import mtr.data.DataCache;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.Station;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Route-in-station summary used by the JourneyMap station markers.
 *
 * <p>MTR 3 keeps the platform to station relation in
 * {@link DataCache#platformIdToStation} (MTR 4 had
 * {@code Station#savedRails}) and exposes the whole dimension through
 * {@link RailwayData}; the same summary therefore works for the authoritative
 * server network and for the radius limited client data.</p>
 */
public class MTRDataSummary {
    private final Long2ObjectArrayMap<List<BasicRouteInfo>> stationToRoutes = new Long2ObjectArrayMap<>();
    private final DataCache dataCache;

    private MTRDataSummary(DataCache dataCache, List<MTRRoute> routes) {
        this.dataCache = dataCache;
        if (dataCache == null) {
            return;
        }
        final Map<Long, List<Platform>> platformsByStation = MtrCompat.platformsByStation(dataCache);

        for (Station station : new ArrayList<>(dataCache.stationIdMap.values())) {
            final List<Platform> platforms = platformsByStation.getOrDefault(station.id, List.of());
            if (platforms.isEmpty()) {
                continue;
            }

            final List<MTRRoute> routePassing = new ArrayList<>();
            for (MTRRoute route : new ArrayList<>(routes)) {
                for (MTRRoutePlatform routePlatformData : route.getRoutePlatforms()) {
                    for (Platform platform : platforms) {
                        if (routePlatformData.getPlatformId() == platform.id) {
                            routePassing.add(route);
                        }
                    }
                }
            }

            final List<BasicRouteInfo> basicRouteInfos = new ArrayList<>();
            for (MTRRoute route : routePassing) {
                final BasicRouteInfo basicRouteInfo = BasicRouteInfo.of(route);
                if (basicRouteInfos.contains(basicRouteInfo)) {
                    continue;
                }
                if (!MTRMapConfig.INSTANCE.showHiddenRoute.get() && route.isHidden()) {
                    continue;
                }
                basicRouteInfos.add(basicRouteInfo);
            }

            stationToRoutes.put(station.id, basicRouteInfos);
        }
    }

    /** Server side: the authoritative network of one dimension. */
    public static MTRDataSummary of(RailwayData railwayData) {
        return new MTRDataSummary(railwayData == null ? null : railwayData.dataCache,
                railwayData == null ? List.of()
                        : railwayData.routes.stream().map(MTRRouteImpl::new).collect(Collectors.toList()));
    }

    /** Client side: the radius limited data MTR synced to this client. */
    public static MTRDataSummary of(DataCache dataCache) {
        return new MTRDataSummary(dataCache,
                dataCache == null ? List.of()
                        : dataCache.routeIdMap.values().stream().map(MTRRouteImpl::new)
                                .collect(Collectors.toList()));
    }

    public DataCache getDataCache() {
        return this.dataCache;
    }

    public List<BasicRouteInfo> getRoutesInStation(Station station) {
        return stationToRoutes.get(station.id);
    }

    public record BasicRouteInfo(String name, int color) {
        public static BasicRouteInfo of(MTRRoute mtrRoute) {
            return new BasicRouteInfo(mtrRoute.getName().split("\\|\\|")[0], mtrRoute.getColor());
        }
    }
}
