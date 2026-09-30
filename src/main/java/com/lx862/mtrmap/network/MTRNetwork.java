package com.lx862.mtrmap.network;
import com.lx862.mtrmap.MTRMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Optional Forge channel with direction-restricted handlers. */
public final class MTRNetwork {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MTRMap.MOD_ID, "network_v5"), () -> "5",
            version -> "5".equals(version) || NetworkRegistry.ABSENT.equals(version)
                    || NetworkRegistry.ACCEPTVANILLA.equals(version),
            version -> "5".equals(version) || NetworkRegistry.ABSENT.equals(version)
                    || NetworkRegistry.ACCEPTVANILLA.equals(version));
    public static void register() {
        CHANNEL.messageBuilder(RequestNetworkSync.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((msg, buf) -> RequestNetworkSync.write(buf, msg)).decoder(RequestNetworkSync::read)
                .consumerMainThread((msg, ctx) -> {
                    ServerPlayer sender = ctx.get().getSender();
                    if (sender != null) ServerNetworkCollector.collectAndSend(sender, msg.dimensionFilter());
                }).add();
        CHANNEL.messageBuilder(NetworkSyncProbe.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder((msg, buf) -> NetworkSyncProbe.write(buf, msg)).decoder(NetworkSyncProbe::read)
                .consumerMainThread((msg, ctx) -> {
                    ServerPlayer sender = ctx.get().getSender();
                    if (sender != null) ServerNetworkCollector.sendProbeResponse(sender);
                }).add();
        CHANNEL.messageBuilder(NetworkSyncChunk.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> NetworkSyncChunk.write(buf, msg)).decoder(NetworkSyncChunk::read)
                .consumerMainThread((msg, ctx) -> MTRNetworkClient.onChunk(msg)).add();
        CHANNEL.messageBuilder(NetworkProbeResponse.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> NetworkProbeResponse.write(buf, msg)).decoder(NetworkProbeResponse::read)
                .consumerMainThread((msg, ctx) -> MTRNetworkClient.onProbe(msg)).add();
    }
    public static void sendToPlayer(ServerPlayer player, MapPacket packet) {
        if (CHANNEL.isRemotePresent(player.connection.connection)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }
}
