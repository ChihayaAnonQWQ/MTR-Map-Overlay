package com.lx862.mtrmap.network;
import com.lx862.mtrmap.MTRMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Fabric 1.20 transport for the shared v5 messages. */
public final class MTRNetwork {
    private static final ResourceLocation REQUEST = id("request_network_sync");
    private static final ResourceLocation PROBE = id("network_sync_probe");
    private static final ResourceLocation CHUNK = id("network_sync_chunk");
    private static final ResourceLocation RESPONSE = id("network_probe_response");
    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(REQUEST, (server, player, handler, buf, sender) -> {
            RequestNetworkSync msg = RequestNetworkSync.read(buf);
            server.execute(() -> ServerNetworkCollector.collectAndSend(player, msg.dimensionFilter()));
        });
        ServerPlayNetworking.registerGlobalReceiver(PROBE, (server, player, handler, buf, sender) ->
                server.execute(() -> ServerNetworkCollector.sendProbeResponse(player)));
    }
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(CHUNK, (client, handler, buf, sender) -> {
            NetworkSyncChunk msg = NetworkSyncChunk.read(buf);
            client.execute(() -> ClientNetworkSync.onChunkReceived(msg));
        });
        ClientPlayNetworking.registerGlobalReceiver(RESPONSE, (client, handler, buf, sender) -> {
            NetworkProbeResponse msg = NetworkProbeResponse.read(buf);
            client.execute(() -> ClientNetworkSync.onProbeReceived(msg.hashes()));
        });
    }
    public static boolean canSendToServer() {
        return ClientPlayNetworking.canSend(REQUEST) && ClientPlayNetworking.canSend(PROBE);
    }
    public static void sendToServer(MapPacket packet) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        if (packet instanceof RequestNetworkSync msg) {
            RequestNetworkSync.write(buf, msg); ClientPlayNetworking.send(REQUEST, buf);
        } else if (packet instanceof NetworkSyncProbe) {
            ClientPlayNetworking.send(PROBE, buf);
        } else { throw new IllegalArgumentException("Unexpected serverbound packet"); }
    }
    public static void sendToPlayer(ServerPlayer player, MapPacket packet) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        ResourceLocation channel;
        if (packet instanceof NetworkSyncChunk msg) {
            NetworkSyncChunk.write(buf, msg); channel = CHUNK;
        } else if (packet instanceof NetworkProbeResponse msg) {
            NetworkProbeResponse.write(buf, msg); channel = RESPONSE;
        } else { throw new IllegalArgumentException("Unexpected clientbound packet"); }
        if (ServerPlayNetworking.canSend(player, channel)) ServerPlayNetworking.send(player, channel, buf);
        else buf.release();
    }
    private static ResourceLocation id(String path) { return new ResourceLocation(MTRMap.MOD_ID, path); }
}
