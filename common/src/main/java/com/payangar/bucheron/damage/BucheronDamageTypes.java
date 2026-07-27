package com.payangar.bucheron.damage;

import com.payangar.bucheron.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

/**
 * Damage types are datapack entries, so the key lives here and the definition in
 * {@code data/bucheron/damage_type/falling_tree.json}. Reusing vanilla's falling-block type would
 * announce that the victim was squashed by a falling block, which is both wrong and unatmospheric.
 */
public final class BucheronDamageTypes {

    public static final ResourceKey<DamageType> FALLING_TREE =
        ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(Constants.MOD_ID, "falling_tree"));

    private BucheronDamageTypes() {
    }
}
