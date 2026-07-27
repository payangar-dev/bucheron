package com.payangar.bucheron.client;

import com.payangar.bucheron.mixin.client.ParticleAccessor;
import net.minecraft.client.particle.Particle;

/** Ported from the soft-leaves mod, same problem, same fix. */
public final class LeafFling {

    private LeafFling() {
    }

    /**
     * Vanilla falling-leaf particle providers discard the velocity they are given, and the particle
     * itself has friction 1.0 and near-zero gravity, so a flung leaf would hang in the air. Giving
     * it back its velocity plus a little gravity and drag makes it arc and settle.
     */
    public static void apply(Particle particle, double vx, double vy, double vz) {
        if (particle == null || (vx == 0.0 && vy == 0.0 && vz == 0.0)) {
            return;
        }
        particle.setParticleSpeed(vx, vy, vz);
        ((ParticleAccessor) particle).bucheron$setGravity(0.012F);
        ((ParticleAccessor) particle).bucheron$setFriction(0.92F);
    }
}
