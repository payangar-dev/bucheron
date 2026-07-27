package com.payangar.bucheron.damage;

import com.payangar.bucheron.entity.FallingTreeEntity;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Hurts whatever the trunk sweeps through on its way down.
 *
 * <p>Damage follows kinetic energy, so it scales with the square of a block's actual speed
 * ({@code v = omega * r}). Nothing about "the top hurts more" is hardcoded: it falls out of the
 * geometry, since the crown travels many times faster than the base. Standing by the stump is
 * safe, standing where the canopy lands is not.
 *
 * <p>Only logs hurt. Being brushed by foliage should not injure anyone.
 */
public final class TreeSweep {

    /** Below this block speed, contact is a nudge rather than a blow. */
    private static final float MIN_HURT_SPEED = 0.15F;

    /**
     * Raised when the fall was slowed down, to keep the damage where playtesting had put it. Damage
     * is deliberately tied to real block speed, so any change to the fall's pace moves it: slowing
     * the tree by a quarter cuts a squared term almost in half.
     */
    private static final float DAMAGE_PER_SPEED_SQUARED = 40.0F;
    private static final float MAX_DAMAGE = 30.0F;

    private static final Predicate<Entity> VICTIMS =
        EntitySelector.NO_CREATIVE_OR_SPECTATOR.and(EntitySelector.LIVING_ENTITY_STILL_ALIVE);

    private record SweptLog(AABB box, float speed) {
    }

    private TreeSweep() {
    }

    /**
     * @param fromAngle the fall angle at the previous tick
     * @param toAngle   the fall angle now
     */
    public static void apply(ServerLevel level, FallingTreeEntity tree, float fromAngle, float toAngle) {
        float angularVelocity = Math.abs(tree.angularVelocity());
        if (angularVelocity <= 0.0F) {
            return;
        }

        List<SweptLog> swept = new ArrayList<>();
        AABB searchArea = null;

        for (FallingTreeEntity.Piece piece : tree.pieces()) {
            if (!piece.state().is(BlockTags.LOGS)) {
                continue;
            }

            // The volume this log passed through during the tick, not just where it ended up,
            // otherwise a fast crown would teleport straight over someone's head.
            Vec3 before = tree.worldPositionOf(piece, fromAngle);
            Vec3 after = tree.worldPositionOf(piece, toAngle);
            AABB box = new AABB(before, after).inflate(0.5);

            swept.add(new SweptLog(box, angularVelocity * tree.radiusOf(piece)));
            searchArea = searchArea == null ? box : searchArea.minmax(box);
        }

        if (searchArea == null) {
            return;
        }

        // One world query per tick for the whole tree, then pure geometry per candidate.
        List<Entity> candidates = level.getEntities(tree, searchArea, VICTIMS);
        if (candidates.isEmpty()) {
            return;
        }

        DamageSource source = fallingTreeDamage(level);
        for (Entity victim : candidates) {
            float fastestHit = 0.0F;
            for (SweptLog log : swept) {
                if (log.speed() > fastestHit && victim.getBoundingBox().intersects(log.box())) {
                    fastestHit = log.speed();
                }
            }

            if (fastestHit < MIN_HURT_SPEED) {
                continue;
            }

            // Vanilla invulnerability frames already stop a tree from hitting twice in a row.
            victim.hurt(source, Math.min(MAX_DAMAGE, DAMAGE_PER_SPEED_SQUARED * fastestHit * fastestHit));
        }
    }

    private static DamageSource fallingTreeDamage(ServerLevel level) {
        Holder<DamageType> type = level.registryAccess()
            .lookupOrThrow(Registries.DAMAGE_TYPE)
            .getOrThrow(BucheronDamageTypes.FALLING_TREE);
        return new DamageSource(type);
    }
}
