package com.payangar.bucheron;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;

/**
 * Built here in common code and registered by each loader, same split as the entity type.
 *
 * <p>Each event has two variants declared in {@code sounds.json}; vanilla picks between them on its
 * own, so felling two trees in a row does not sound copy-pasted.
 */
public final class BucheronSounds {

    public static final Identifier TREE_FALLING_ID =
        Identifier.fromNamespaceAndPath(Constants.MOD_ID, "tree_falling");
    public static final Identifier TREE_DOWN_ID =
        Identifier.fromNamespaceAndPath(Constants.MOD_ID, "tree_down");

    public static final ResourceKey<SoundEvent> TREE_FALLING_KEY =
        ResourceKey.create(Registries.SOUND_EVENT, TREE_FALLING_ID);
    public static final ResourceKey<SoundEvent> TREE_DOWN_KEY =
        ResourceKey.create(Registries.SOUND_EVENT, TREE_DOWN_ID);

    /** Plays as the tree starts to tip. */
    public static final SoundEvent TREE_FALLING = SoundEvent.createVariableRangeEvent(TREE_FALLING_ID);

    /** Plays when it hits the ground. */
    public static final SoundEvent TREE_DOWN = SoundEvent.createVariableRangeEvent(TREE_DOWN_ID);

    private BucheronSounds() {
    }
}
