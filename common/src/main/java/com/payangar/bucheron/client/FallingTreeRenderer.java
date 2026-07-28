package com.payangar.bucheron.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.payangar.bucheron.entity.FallingTreeEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;

/** Draws the tree as its own blocks, rotated about the base edge it tips over. */
public class FallingTreeRenderer extends EntityRenderer<FallingTreeEntity, FallingTreeRenderState> {

    public FallingTreeRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public FallingTreeRenderState createRenderState() {
        return new FallingTreeRenderState();
    }

    @Override
    public void extractRenderState(FallingTreeEntity entity, FallingTreeRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.fallAngle = entity.fallAngle(partialTick);
        state.fallDirection = entity.fallDirection();
        state.pieces.clear();

        if (!(entity.level() instanceof ClientLevel level)) {
            return;
        }

        BlockPos stump = entity.blockPosition();
        Map<BlockPos, BlockState> treeBlocks = new HashMap<>();
        for (FallingTreeEntity.Piece piece : entity.pieces()) {
            treeBlocks.put(stump.offset(piece.offset()), piece.state());
        }

        for (FallingTreeEntity.Piece piece : entity.pieces()) {
            BlockPos worldPos = stump.offset(piece.offset());

            MovingBlockRenderState block = new TreePieceRenderState(treeBlocks, level);
            block.randomSeedPos = worldPos;
            block.blockPos = worldPos;
            block.blockState = piece.state();
            block.cardinalLighting = level.cardinalLighting();
            block.lightEngine = level.getLightEngine();

            state.pieces.add(new FallingTreeRenderState.RenderPiece(piece.offset(), block));
        }
    }

    @Override
    public void submit(FallingTreeRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.pieces.isEmpty()) {
            return;
        }

        Direction direction = state.fallDirection;
        poseStack.pushPose();

        // The trunk tips over its bottom edge, not the centre of the stump, so the base does not
        // slide backwards as it goes down.
        float pivotX = direction.getStepX() * 0.5F;
        float pivotZ = direction.getStepZ() * 0.5F;
        poseStack.translate(pivotX, 0.0F, pivotZ);
        // Rotation axis is horizontal and perpendicular to the fall direction.
        poseStack.mulPose(new Quaternionf().rotateAxis(
            state.fallAngle, direction.getStepZ(), 0.0F, -direction.getStepX()));
        poseStack.translate(-pivotX, 0.0F, -pivotZ);

        // Block models are drawn from their corner, while the entity sits at the stump's centre.
        poseStack.translate(-0.5, 0.0, -0.5);

        for (FallingTreeRenderState.RenderPiece piece : state.pieces) {
            poseStack.pushPose();
            poseStack.translate(piece.offset().getX(), piece.offset().getY(), piece.offset().getZ());
            collector.submitMovingBlock(poseStack, piece.block(), state.outlineColor);
            poseStack.popPose();
        }

        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    /**
     * The entity's hitbox is one block at the stump while the canopy reaches well beyond it, so
     * frustum culling on the hitbox alone would make tall trees vanish at the screen edge.
     */
    @Override
    public boolean shouldRender(FallingTreeEntity entity, Frustum culler, double camX, double camY, double camZ) {
        return true;
    }
}
