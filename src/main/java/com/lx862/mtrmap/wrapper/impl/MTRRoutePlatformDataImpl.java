package com.lx862.mtrmap.wrapper.impl;

import com.lx862.mtrmap.wrapper.MTRRoutePlatform;
import mtr.data.Route;

/**
 * MTR 3 adapter for one stop of a route. MTR 3 stores the stop as
 * {@code Route.RoutePlatform} with a bare platform id and an optional custom
 * destination; MTR 4 resolved the platform object through
 * {@code RoutePlatformData#getPlatform()}.
 */
public class MTRRoutePlatformDataImpl implements MTRRoutePlatform {
    private final Route.RoutePlatform instance;

    public MTRRoutePlatformDataImpl(Route.RoutePlatform data) {
        this.instance = data;
    }

    @Override
    public long getPlatformId() {
        return instance.platformId;
    }
}
