package com.lx862.mtrmap.wrapper.impl;

import com.lx862.mtrmap.wrapper.MTRRoute;
import com.lx862.mtrmap.wrapper.MTRRoutePlatform;
import mtr.data.Route;

import java.util.ArrayList;
import java.util.List;

/**
 * MTR 3 adapter for {@link Route}. MTR 3 keeps an ordered stop list in
 * {@code Route.platformIds} ({@code List<Route.RoutePlatform>}) and exposes
 * name/colour/visibility as public fields; MTR 4 used getters plus a separate
 * {@code SimplifiedRoute} type for the client side, which MTR 3 does not have.
 */
public class MTRRouteImpl implements MTRRoute {
    private final Route instance;

    public MTRRouteImpl(Route route) {
        this.instance = route;
    }

    @Override
    public String getName() {
        return this.instance.name;
    }

    @Override
    public int getColor() {
        return this.instance.color;
    }

    @Override
    public boolean isHidden() {
        return this.instance.isHidden;
    }

    @Override
    public List<MTRRoutePlatform> getRoutePlatforms() {
        final List<MTRRoutePlatform> platforms = new ArrayList<>();
        if (this.instance.platformIds != null) {
            for (Route.RoutePlatform routePlatform : this.instance.platformIds) {
                if (routePlatform != null) {
                    platforms.add(new MTRRoutePlatformDataImpl(routePlatform));
                }
            }
        }
        return platforms;
    }
}
