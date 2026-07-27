package com.payangar.bucheron.mixin;

import com.payangar.bucheron.tree.TreeFelling;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entry point of the whole mod: a log about to be destroyed becomes a falling tree instead.
 *
 * <p>Injected at HEAD on purpose. NeoForge patches the head of this method with its own break
 * event, so anchoring anywhere inside the body would fail to apply on Fabric.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow @Final protected ServerPlayer player;

    @Shadow protected ServerLevel level;

    @Shadow private GameType gameModeForPlayer;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void bucheron$fellWholeTree(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        // isSurvival() covers survival and adventure, and leaves creative and spectator alone.
        if (!gameModeForPlayer.isSurvival()) {
            return;
        }

        if (TreeFelling.tryFell(level, pos, player)) {
            callback.setReturnValue(true);
        }
    }
}
