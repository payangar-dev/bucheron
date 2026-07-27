package com.payangar.bucheron.entity;

import com.payangar.bucheron.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * The entity type is built here, in common code, but registered by each loader: Fabric registers
 * directly into the vanilla registry, NeoForge goes through a DeferredRegister.
 */
public final class BucheronEntities {

    public static final ResourceKey<EntityType<?>> FALLING_TREE_KEY =
        ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Constants.MOD_ID, "falling_tree"));

    public static final EntityType<FallingTreeEntity> FALLING_TREE =
        EntityType.Builder.<FallingTreeEntity>of(FallingTreeEntity::new, MobCategory.MISC)
            .sized(1.0F, 1.0F)
            .noSummon()
            .noSave()
            .noLootTable()
            .clientTrackingRange(10)
            .updateInterval(1)
            .build(FALLING_TREE_KEY);

    private BucheronEntities() {
    }
}
