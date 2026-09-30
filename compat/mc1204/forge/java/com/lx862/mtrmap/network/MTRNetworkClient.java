package com.lx862.mtrmap.network;
import net.minecraft.client.Minecraft;
/** Physical-client callbacks. Never executed on a dedicated server. */
public final class MTRNetworkClient {
    static void onChunk(NetworkSyncChunk packet) { ClientNetworkSync.onChunkReceived(packet); }
    static void onProbe(NetworkProbeResponse packet) { ClientNetworkSync.onProbeReceived(packet.hashes()); }
    public static boolean canSendToServer() {
        var listener = Minecraft.getInstance().getConnection();
        return listener != null && MTRNetwork.CHANNEL.isRemotePresent(listener.getConnection());
    }
    public static void sendToServer(MapPacket packet) {
        var listener = Minecraft.getInstance().getConnection();
        if (listener != null) MTRNetwork.CHANNEL.send(packet, listener.getConnection());
    }
}
