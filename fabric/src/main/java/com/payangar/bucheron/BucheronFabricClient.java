package com.payangar.bucheron;

import com.payangar.bucheron.client.BucheronClientNetwork;
import com.payangar.bucheron.client.FallingTreeRenderer;
import com.payangar.bucheron.entity.BucheronEntities;
import com.payangar.bucheron.network.TreeShapePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class BucheronFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(BucheronEntities.FALLING_TREE, FallingTreeRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(TreeShapePayload.TYPE,
            (payload, context) -> BucheronClientNetwork.applyTreeShape(payload));
    }
}
