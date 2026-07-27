package com.payangar.bucheron.mixin.client;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleAccessor {

    @Accessor("gravity")
    void bucheron$setGravity(float gravity);

    @Accessor("friction")
    void bucheron$setFriction(float friction);
}
