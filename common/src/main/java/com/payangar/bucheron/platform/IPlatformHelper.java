package com.payangar.bucheron.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public interface IPlatformHelper {

    String getPlatformName();

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);
}
