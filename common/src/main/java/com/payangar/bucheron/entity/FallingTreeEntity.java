package com.payangar.bucheron.entity;

import com.payangar.bucheron.BucheronSounds;
import com.payangar.bucheron.LeafParticles;
import com.payangar.bucheron.damage.TreeSweep;
import com.payangar.bucheron.network.TreeShapePayload;
import com.payangar.bucheron.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private static final double SHAPE_SEND_RADIUS = 96.0;

    /** Capped per tick so a big canopy cannot flood clients with particle packets. */
    private static final int MAX_PARTICLES_PER_TICK = 3;

    /**
     * Faster leaves shed more. Below this speed, nothing sheds at all.
     *
     * <p>Kept deliberately sparse: the trail in mid-air is only a hint of movement. The real burst
     * belongs to the moment a leaf block hits something, which is when the canopy actually breaks
     * apart.
     */
    private static final float SHED_CHANCE_PER_SPEED = 0.15F;
    private static final float MIN_SHED_SPEED = 0.01F;

    /** Per crushed leaf block. One packet each, because each needs its own direction. */
    private static final int BURST_PARTICLES = 8;

    /** Ejection speed of a burst, varied per particle so the spray is not a uniform shell. */
    private static final double BURST_SPEED = 0.35;

    /**
     * Extra push on the vertical component of a burst.
     *
     * <p>A direction picked uniformly over the hemisphere spends most of its area near the horizon,
     * so an even spread ends up hugging the ground. Weighting the vertical axis tilts the whole
     * spray upwards and is what makes the cloud rise rather than spread.
     */
    private static final double BURST_VERTICAL_GAIN = 1.6;

    /**
     * A canopy scraping the ground crushes many blocks at once, and playing every break sound
     * would be both wasteful and mush to the ear. Impacts falling inside the same cell of this
     * grid are merged into a single louder sound.
     *
     * <p>Merging by locality rather than capping matters: a cap silences sounds by iteration
     * order, so two impacts at opposite ends of a big tree could cancel each other while two
     * adjacent ones both play.
     */
    private static final int CRUSH_SOUND_CELL = 3;

    /** Leaves are thrown along the canopy's motion, but a fraction of it reads better than 1:1. */
    private static final float FLING_SCALE = 0.35F;

    /**
     * How much of a leaf's downward motion is turned into lift.
     *
     * <p>A leaf does not ride the branch into the ground. The canopy shoves a wall of air ahead of
     * itself, that air escapes upwards once it is squeezed against the ground, and the leaf goes
     * with it. So the horizontal part of the tangential velocity is carried over as is, while the
     * downward part is reflected rather than kept.
     *
     * <p>The arc then comes for free from the particle engine: {@code v = v * friction - gravity}
     * damps the initial burst exponentially, giving a sharp rise, an apex, and a slow drift down.
     * That asymmetry is what reads as a leaf rather than as a thrown pebble.
     */
    private static final float UPWARD_FLING = 0.8F;

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
            shedLeaves(serverLevel);

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
            //
            // Runs on both sides: the calculation is identical and the client knows the blocks, so
            // the canopy comes apart the same way there without a single packet.
            crushLeavesOnContact(serverLevel);
        }
    }

    /** Radians per tick. Combined with a piece's radius, this is what makes the crown lethal. */
    public float angularVelocity() {
        return angularVelocity;
    }

    /**
     * Leaves rain down as the canopy sweeps through the air. The chance scales with how fast the
     * block is actually moving, so the crown showers while the stump barely sheds anything.
     */
    private void shedLeaves(ServerLevel level) {
        if (pieces.isEmpty()) {
            return;
        }

        int emitted = 0;
        int size = pieces.size();
        // Start somewhere random, otherwise the per-tick cap would always favour the same leaves.
        int start = random.nextInt(size);

        for (int i = 0; i < size && emitted < MAX_PARTICLES_PER_TICK; i++) {
            Piece piece = pieces.get((start + i) % size);
            if (!piece.state().is(BlockTags.LEAVES)) {
                continue;
            }

            float speed = Math.abs(angularVelocity) * radiusOf(piece);
            if (speed < MIN_SHED_SPEED || random.nextFloat() > speed * SHED_CHANCE_PER_SPEED) {
                continue;
            }

            Vec3 pos = worldPositionOf(piece, angle);
            Vec3 tangential = worldVelocityOf(piece, angle).scale(FLING_SCALE);
            // Keep the horizontal sweep, reflect the plunge into lift. Leaves on the far side of
            // the pivot are already rising, and Math.abs leaves their lift untouched.
            Vec3 velocity = new Vec3(tangential.x, Math.abs(tangential.y) * UPWARD_FLING, tangential.z);

            // count = 0 makes the three trailing values a velocity rather than random offsets.
            // It is the only way to fling a leaf: with a positive count the particles would simply
            // hang where they spawned, which is what made the first version look lifeless.
            level.sendParticles(LeafParticles.resolve(piece.state(), level, BlockPos.containing(pos)),
                pos.x, pos.y, pos.z, 0, velocity.x, velocity.y, velocity.z, 1.0);
            emitted++;
        }
    }

    /**
     * A leaf block does not pass through terrain: it bursts against it and leaves the tree.
     *
     * <p>This is where most of the foliage comes from. A canopy sweeping into the ground shatters,
     * and that contact is the visible event, far more than the trail it leaves in the air.
     *
     * @param serverLevel null on the client, which only removes the crushed blocks
     */
    private void crushLeavesOnContact(ServerLevel serverLevel) {
        if (pieces.isEmpty()) {
            return;
        }

        List<Piece> crushed = new ArrayList<>();
        Map<Long, CrushCell> cells = new HashMap<>();

        for (Piece piece : pieces) {
            if (!piece.state().is(BlockTags.LEAVES)) {
                continue;
            }

            Vec3 pos = worldPositionOf(piece, angle);
            BlockPos at = BlockPos.containing(pos);
            // Collision shape rather than "not air", so grass and flowers do not shred a canopy.
            if (level().getBlockState(at).getCollisionShape(level(), at).isEmpty()) {
                continue;
            }

            crushed.add(piece);

            if (serverLevel != null) {
                // Particles stay per block: the burst has to happen where the leaves actually were.
                // One packet each, because count > 0 spreads them randomly, which is exactly right
                // for something shattering in every direction.
                burstLeaves(serverLevel, piece, at, pos);
                cells.computeIfAbsent(soundCellKey(pos), key -> new CrushCell(pos, piece.state())).merge(pos);
            }
        }

        if (serverLevel != null) {
            playMergedCrushSounds(serverLevel, cells);
        }
        pieces.removeAll(crushed);
    }

    /**
     * Sprays a leaf block into the air on impact.
     *
     * <p>Directions are spread over the **upper** hemisphere, never the full sphere: the ground is
     * right there, so a leaf thrown downwards makes no sense. Each particle gets its own velocity,
     * which needs one packet each ({@code count = 0} is the only form that carries a velocity), but
     * a burst happens once per felled tree rather than every tick, so the cost is a one-off.
     */
    private void burstLeaves(ServerLevel level, Piece piece, BlockPos at, Vec3 pos) {
        ParticleOptions particle = LeafParticles.resolve(piece.state(), level, at);

        for (int i = 0; i < BURST_PARTICLES; i++) {
            // Vertical component first, then the horizontal radius that keeps the direction a unit
            // vector. The square root biases the pick towards the zenith, so fewer leaves skim the
            // ground, and the gain then lifts the whole spray.
            double up = Math.sqrt(random.nextDouble());
            double radius = Math.sqrt(1.0 - up * up);
            double azimuth = random.nextDouble() * Math.PI * 2.0;

            double speed = BURST_SPEED * (0.6 + random.nextDouble() * 0.8);
            double vx = Math.cos(azimuth) * radius * speed;
            double vz = Math.sin(azimuth) * radius * speed;
            double vy = up * speed * BURST_VERTICAL_GAIN;

            level.sendParticles(particle,
                pos.x + (random.nextDouble() - 0.5) * 0.5,
                pos.y + (random.nextDouble() - 0.5) * 0.4,
                pos.z + (random.nextDouble() - 0.5) * 0.5,
                0, vx, vy, vz, 1.0);
        }
    }

    /** Impacts merged into one sound: their centre of mass, their material, and how many. */
    private static final class CrushCell {

        private final BlockState state;
        private double x;
        private double y;
        private double z;
        private int count;

        private CrushCell(Vec3 first, BlockState state) {
            this.state = state;
            this.x = first.x;
            this.y = first.y;
            this.z = first.z;
            this.count = 1;
        }

        private void merge(Vec3 pos) {
            // Running average, so the sound comes from the middle of the impacts it stands for.
            count++;
            x += (pos.x - x) / count;
            y += (pos.y - y) / count;
            z += (pos.z - z) / count;
        }
    }

    private void playMergedCrushSounds(ServerLevel level, Map<Long, CrushCell> cells) {
        for (CrushCell cell : cells.values()) {
            // The block's own break sound, without breaking anything: these leaves belong to the
            // falling tree, not to the world. Going through SoundType means the sound follows the
            // species for free, cherry included.
            SoundType sound = cell.state.getSoundType();
            float volume = sound.getVolume() * Math.min(0.95F, 0.5F + 0.1F * cell.count);
            level.playSound(null, cell.x, cell.y, cell.z, sound.getBreakSound(), SoundSource.BLOCKS,
                volume, sound.getPitch() * (0.9F + random.nextFloat() * 0.2F));
        }
    }

    private static long soundCellKey(Vec3 pos) {
        long x = Math.floorDiv((long) Math.floor(pos.x), CRUSH_SOUND_CELL);
        long y = Math.floorDiv((long) Math.floor(pos.y), CRUSH_SOUND_CELL);
        long z = Math.floorDiv((long) Math.floor(pos.z), CRUSH_SOUND_CELL);
        return (x & 0x1FFFFFL) | ((y & 0x1FFFFFL) << 21) | ((z & 0x1FFFFFL) << 42);
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
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(this) <= SHAPE_SEND_RADIUS * SHAPE_SEND_RADIUS) {
                Services.PLATFORM.sendToPlayer(player, payload);
            }
        }
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
