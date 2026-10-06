package com.lx862.mtrmap.mixin;

import mtr.data.Rail;
import mtr.data.RailwayData;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * MTR 3 keeps the authoritative per-dimension rail map private
 * ({@code RailwayData.rails}, a {@code Map<BlockPos, Map<BlockPos, Rail>>}),
 * while the full-network snapshot has to read it. MTR 4 exposed the same
 * information publicly through {@code Simulator#rails} / {@code positionsToRail}.
 */
@Mixin(value = RailwayData.class, remap = false)
public interface RailwayDataAccessorMixin {

    @Accessor("rails")
    Map<BlockPos, Map<BlockPos, Rail>> getRails();
}
