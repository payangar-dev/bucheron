package com.payangar.bucheron.mixin;

import com.payangar.bucheron.tree.TreeMiningSpeed;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Slows mining down to match the size of the tree the block belongs to.
 *
 * <p>Injected on RETURN so the vanilla progress, tool speed and enchantments included, is computed
 * first and then divided. Runs on both sides by design: this method drives the client's cracking
 * animation as well as the server's validation.
 */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourMixin {

    @Inject(method = "getDestroyProgress", at = @At("RETURN"), cancellable = true)
    private void bucheron$slowDownWithTreeSize(
        BlockState state, Player player, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> callback
    ) {
        float adjusted = TreeMiningSpeed.adjust(state, player, level, pos, callback.getReturnValue());
        if (adjusted != callback.getReturnValue()) {
            callback.setReturnValue(adjusted);
        }
    }
}
