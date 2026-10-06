package com.lx862.mtrmap.mixin.client;

import com.lx862.mtrmap.MTRMap;
import com.lx862.mtrmap.integration.journeymap.JourneyMapIntegration;
import com.lx862.mtrmap.mapdata.MapDataCache;
import mtr.client.ClientData;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refresh the map overlays whenever MTR pushes new client data.
 *
 * <p>MTR 3 deserialises its whole client dataset - stations, platforms,
 * sidings, routes, depots, lifts - in the single static entry point
 * {@code ClientData.receivePacket(FriendlyByteBuf)}, and then rebuilds
 * {@code ClientData.DATA_CACHE}. MTR 4 instead had a
 * {@code MinecraftClientData#sync()} instance method, which the previous
 * version of this mixin targeted.</p>
 */
@Mixin(value = ClientData.class, remap = false)
public class ClientDataSyncMixin {

    @Inject(method = "receivePacket", at = @At("TAIL"))
    private static void mtrmap$onReceivePacket(FriendlyByteBuf buffer, CallbackInfo callbackInfo) {
        MTRMap.LOGGER.debug("[MTRMap] MTR client data synced, refreshing map overlays");
        // Refresh JourneyMap landmarks on MTR data changes (self-gating).
        JourneyMapIntegration.requestSync();
        // Invalidate the path-layer cache built from MTR client data.
        MapDataCache.onClientDataSynced();
    }
}
