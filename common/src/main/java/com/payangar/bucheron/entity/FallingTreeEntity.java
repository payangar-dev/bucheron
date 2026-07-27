package com.payangar.bucheron.entity;

import com.payangar.bucheron.BucheronSounds;
import com.payangar.bucheron.damage.TreeSweep;
import com.payangar.bucheron.fx.LeafEffects;
import com.payangar.bucheron.network.TreeShapePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A tree between the cut and the ground.
 *
 * <p>The entity never moves. It stands where the stump was and pivots, which is why the fall is an
 * angle rather than a velocity. Both sides integrate the same pendulum from the same starting
 * angle, so the client animates without a single update packet after the initial shape.
 *
 * <p>The entity is deliberately not saved: a fall lasts a couple of seconds, and half a tree
 * restored from disk would be worse than no tree at all.
 */
public class FallingTreeEntity extends Entity {

    /**
     * Drives the whole fall. Deliberately weaker than a vanilla falling block's 0.04: a tree is a
     * mass that tips over, not a stone that drops, and the extra time is what gives the fall its
     * weight. Lower value, longer fall, in {@code sqrt(1/g)}. The acceleration itself comes from
     * the {@code sin(theta)} term and is there whatever this is set to.
     */
    private static final double GRAVITY = 0.022;

    /**
     * The nudge the cut gives the trunk, in radians per tick.
     *
     * <p>The pendulum's own acceleration is proportional to {@code sin(theta)}, which is zero when
     * the tree stands straight, so something has to start the motion. Seeding a velocity rather
     * than a starting angle is both truer, a tree topples because it was pushed and not because it
     * was already leaning, and visually necessary: any starting angle is a visible jolt on the
     * first frame.
     */
    private static final float START_ANGULAR_VELOCITY = 0.018F;

    /** Flat on the ground. */
    private static final float FLAT_ANGLE = Mth.HALF_PI;

    /** How long the felled tree lies there before it turns into items. */
    private static final int REST_TICKS = 40;

    /** Failsafe: a tree that somehow never lands must not linger. */
    private static final int MAX_LIFETIME_TICKS = 400;

    /** One block of the tree: where it sits relative to the stump, and what it looks like. */
    public record Piece(BlockPos offset, BlockState state) {
    }

    private final List<Piece> pieces = new ArrayList<>();
    private final List<ItemStack> drops = new ArrayList<>();

    private Direction fallDirection = Direction.NORTH;
    private float angle;
    private float previousAngle;
    private float angularVelocity = START_ANGULAR_VELOCITY;

    private boolean grounded;
    private int restTicks;

    /** Pendulum length. Derived from the pieces on both sides so the integration cannot diverge. */
    private int treeHeight = 1;

    public FallingTreeEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    /** Server side, at felling time. */
    public void setShape(Direction direction, List<Piece> shape, List<ItemStack> stacks) {
        fallDirection = direction;
        pieces.clear();
        pieces.addAll(shape);
        drops.clear();
        drops.addAll(stacks);
        recomputeHeight();
    }

    /** Client side, on receiving {@link TreeShapePayload}. */
    public void applyShape(Direction direction, List<Piece> shape) {
        fallDirection = direction;
        pieces.clear();
        pieces.addAll(shape);
        recomputeHeight();
    }

    private void recomputeHeight() {
        int top = 0;
        for (Piece piece : pieces) {
            top = Math.max(top, piece.offset().getY());
        }
        treeHeight = top + 1;
    }

    public List<Piece> pieces() {
        return pieces;
    }

    public Direction fallDirection() {
        return fallDirection;
    }

    /** Interpolated fall angle in radians, so the animation is smooth between ticks. */
    public float fallAngle(float partialTick) {
        return Mth.lerp(partialTick, previousAngle, angle);
    }

    @Override
    public void tick() {
        super.tick();
        previousAngle = angle;
        ServerLevel serverLevel = level() instanceof ServerLevel server ? server : null;

        if (tickCount == 1 && serverLevel != null) {
            broadcastShape(serverLevel);
            playTreeSound(serverLevel, BucheronSounds.TREE_FALLING);
        }

        if (grounded) {
            restTicks++;
            if (restTicks >= REST_TICKS && serverLevel != null) {
                releaseDrops(serverLevel);
            }
            return;
        }

        // Rigid rod pivoting about its base: theta'' = (3g / 2L) * sin(theta).
        // Falling accelerates on its own, and a taller tree takes longer to go down.
        angularVelocity += (float) (3.0 * GRAVITY / (2.0 * Math.max(1, treeHeight)) * Math.sin(angle));
        angle += angularVelocity;

        boolean reachedGround = angle >= FLAT_ANGLE;
        if (reachedGround) {
            angle = FLAT_ANGLE;
        }

        // The landing tick is the fastest part of the fall, so it must be swept like any other.
        if (serverLevel != null) {
            TreeSweep.apply(serverLevel, this, previousAngle, angle);
            LeafEffects.shed(serverLevel, this, angle);

            if (tickCount > MAX_LIFETIME_TICKS) {
                releaseDrops(serverLevel);
                return;
            }
        }

        if (reachedGround) {
            // Flat on the ground, and it stays there. A trunk does not bounce.
            angularVelocity = 0.0F;
            grounded = true;

            if (serverLevel != null) {
                playTreeSound(serverLevel, BucheronSounds.TREE_DOWN);
            }

            // The canopy shatters here, in one go, and only here. Spreading it over the fall read
            // as a slow disintegration; the impact is the event.
            LeafEffects.crushOnContact(this, angle);
        }
    }

    /** Radians per tick. Combined with a piece's radius, this is what makes the crown lethal. */
    public float angularVelocity() {
        return angularVelocity;
    }

    /** Where a piece actually is in the world at the given fall angle. */
    public Vec3 worldPositionOf(Piece piece, float atAngle) {
        Vector3f local = rotatedOffset(piece, atAngle);
        return new Vec3(
            getX() + fallDirection.getStepX() * 0.5F + local.x,
            getY() + local.y,
            getZ() + fallDirection.getStepZ() * 0.5F + local.z);
    }

    /**
     * Tangential velocity of a piece in blocks per tick, which is omega cross r. This is the
     * direction the block is actually travelling, so leaves flung with it follow the sweep of the
     * canopy instead of dropping straight down.
     */
    public Vec3 worldVelocityOf(Piece piece, float atAngle) {
        Vector3f local = rotatedOffset(piece, atAngle);
        Vector3f velocity = rotationAxis().cross(local, new Vector3f()).mul(angularVelocity);
        return new Vec3(velocity.x, velocity.y, velocity.z);
    }

    /** Offset from the pivot, rotated by the current fall angle. */
    private Vector3f rotatedOffset(Piece piece, float atAngle) {
        Vector3f local = new Vector3f(
            piece.offset().getX() - fallDirection.getStepX() * 0.5F,
            piece.offset().getY() + 0.5F,
            piece.offset().getZ() - fallDirection.getStepZ() * 0.5F);
        Vector3f axis = rotationAxis();
        return local.rotateAxis(atAngle, axis.x, axis.y, axis.z);
    }

    /** Horizontal, perpendicular to the fall direction. */
    private Vector3f rotationAxis() {
        return new Vector3f(fallDirection.getStepZ(), 0.0F, -fallDirection.getStepX());
    }

    /** Distance from the pivot, which is what turns angular speed into block speed. */
    public float radiusOf(Piece piece) {
        float dx = piece.offset().getX() - fallDirection.getStepX() * 0.5F;
        float dy = piece.offset().getY() + 0.5F;
        float dz = piece.offset().getZ() - fallDirection.getStepZ() * 0.5F;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Slight pitch variation on top of the two recorded variants, so no two fells sound alike. */
    private void playTreeSound(ServerLevel level, SoundEvent sound) {
        level.playSound(null, getX(), getY(), getZ(), sound, SoundSource.BLOCKS,
            1.0F, 0.95F + random.nextFloat() * 0.1F);
    }

    private void broadcastShape(ServerLevel level) {
        if (pieces.isEmpty()) {
            return;
        }

        List<TreeShapePayload.Piece> wire = new ArrayList<>(pieces.size());
        for (Piece piece : pieces) {
            wire.add(new TreeShapePayload.Piece(piece.offset(), Block.getId(piece.state())));
        }

        TreeShapePayload payload = new TreeShapePayload(getId(), (byte) fallDirection.ordinal(), wire);
        // Exactly the players the spawn packet went to, so a tracked tree is never invisible.
        level.getChunkSource().sendToTrackingPlayers(this, new ClientboundCustomPayloadPacket(payload));
    }

    private void releaseDrops(ServerLevel level) {
        for (ItemStack stack : drops) {
            ItemEntity item = new ItemEntity(level, getX(), getY() + 0.5, getZ(), stack.copy());
            item.setDefaultPickUpDelay();
            level.addFreshEntity(item);
        }
        drops.clear();
        discard();
    }

    @Override
    protected double getDefaultGravity() {
        return 0.0;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
    }
}
