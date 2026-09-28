package com.lx862.mtrmap.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Fabric counterpart for the shared client snapshot requester. */
public final class MTRNetworkClient {

    private MTRNetworkClient() {
    }

    public static boolean canSendToServer() {
        return MTRNetwork.canSendToServer();
    }

    public static void sendToServer(CustomPacketPayload payload) {
        MTRNetwork.sendToServer(payload);
    }
}
