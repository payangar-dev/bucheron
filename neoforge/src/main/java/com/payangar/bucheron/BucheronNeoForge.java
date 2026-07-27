package com.payangar.bucheron;

import com.payangar.bucheron.entity.BucheronEntities;
import com.payangar.bucheron.network.TreeShapePayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Constants.MOD_ID)
public class BucheronNeoForge {

    public BucheronNeoForge(IEventBus modBus) {
        BucheronInit.init();

        DeferredRegister<EntityType<?>> entityTypes = DeferredRegister.create(Registries.ENTITY_TYPE, Constants.MOD_ID);
        entityTypes.register("falling_tree", () -> BucheronEntities.FALLING_TREE);
        entityTypes.register(modBus);

        DeferredRegister<SoundEvent> sounds = DeferredRegister.create(Registries.SOUND_EVENT, Constants.MOD_ID);
        sounds.register("tree_falling", () -> BucheronSounds.TREE_FALLING);
        sounds.register("tree_down", () -> BucheronSounds.TREE_DOWN);
        sounds.register(modBus);

        modBus.addListener((RegisterPayloadHandlersEvent event) ->
            event.registrar("1").playToClient(TreeShapePayload.TYPE, TreeShapePayload.CODEC,
                // The nested lambda keeps client-only classes off a dedicated server's load path.
                (payload, context) -> context.enqueueWork(() ->
                    com.payangar.bucheron.client.BucheronClientNetwork.applyTreeShape(payload))));

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            BucheronNeoForgeClient.init(modBus);
        }
    }
}
