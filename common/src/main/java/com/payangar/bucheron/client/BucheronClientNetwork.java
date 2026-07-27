package com.payangar.bucheron.client;

import com.payangar.bucheron.entity.FallingTreeEntity;
import com.payangar.bucheron.network.TreeShapePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/** Client half of the tree shape sync, shared by both loaders. Must run on the client thread. */
public final class BucheronClientNetwork {

    private BucheronClientNetwork() {
    }

    public static void applyTreeShape(TreeShapePayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        // The shape is sent one tick after the spawn packet, so the entity normally exists by now.
        // If it does not, the tree simply stays invisible rather than breaking anything.
        if (!(level.getEntity(payload.entityId()) instanceof FallingTreeEntity tree)) {
            return;
        }

        List<FallingTreeEntity.Piece> pieces = new ArrayList<>(payload.pieces().size());
        for (TreeShapePayload.Piece piece : payload.pieces()) {
            pieces.add(new FallingTreeEntity.Piece(piece.offset(), Block.stateById(piece.stateId())));
        }

        tree.applyShape(Direction.values()[payload.fallDirection()], pieces);
    }
}
