package com.payangar.bucheron;

import com.payangar.bucheron.entity.BucheronEntities;
import com.payangar.bucheron.network.TreeShapePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public class BucheronFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        BucheronInit.init();
        Registry.register(BuiltInRegistries.ENTITY_TYPE, BucheronEntities.FALLING_TREE_KEY, BucheronEntities.FALLING_TREE);
        Registry.register(BuiltInRegistries.SOUND_EVENT, BucheronSounds.TREE_FALLING_KEY, BucheronSounds.TREE_FALLING);
        Registry.register(BuiltInRegistries.SOUND_EVENT, BucheronSounds.TREE_DOWN_KEY, BucheronSounds.TREE_DOWN);
        PayloadTypeRegistry.clientboundPlay().register(TreeShapePayload.TYPE, TreeShapePayload.CODEC);
    }
}
