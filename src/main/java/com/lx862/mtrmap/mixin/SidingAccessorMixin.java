package com.lx862.mtrmap.mixin;

import mtr.data.Siding;
import mtr.path.PathData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * MTR 3 stores the driving path MTR generated for a siding in a private field
 * ({@code Siding.path}) and exposes no getter, while MTR 4 exposed the
 * equivalent through {@code Depot#getPath()}. The snapshot uses these paths to
 * colour rails with the route a train actually drives; routes without a
 * generated path fall back to {@code RoutePathfinder}.
 */
@Mixin(value = Siding.class, remap = false)
public interface SidingAccessorMixin {

    @Accessor("path")
    List<PathData> getPath();
}
