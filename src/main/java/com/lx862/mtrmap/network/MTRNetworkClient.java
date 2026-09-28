package com.lx862.mtrmap.network;

import com.lx862.mtrmap.mixin.client.ClientCommonListenerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** NeoForge transport functions that may only run on the physical client. */
public final class MTRNetworkClient {

    private MTRNetworkClient() {
    }

    static void onChunk(NetworkSyncChunk msg, IPayloadContext context) {
        context.enqueueWork(() -> ClientNetworkSync.onChunkReceived(msg));
    }

    static void onProbe(NetworkProbeResponse msg, IPayloadContext context) {
        context.enqueueWork(() -> ClientNetworkSync.onProbeReceived(msg.hashes()));
    }

    public static boolean canSendToServer() {
        return Minecraft.getInstance().getConnection() instanceof ClientCommonListenerAccessor accessor
                && accessor.mtrmap$getConnectionType() == ConnectionType.NEOFORGE;
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
