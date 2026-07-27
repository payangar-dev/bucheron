package com.payangar.bucheron;

import com.payangar.bucheron.client.FallingTreeRenderer;
import com.payangar.bucheron.entity.BucheronEntities;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Kept separate from the mod entry point so that a dedicated server never loads a class whose
 * signatures reference client-only rendering events.
 */
public final class BucheronNeoForgeClient {

    private BucheronNeoForgeClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
            event.registerEntityRenderer(BucheronEntities.FALLING_TREE, FallingTreeRenderer::new));
    }
}
