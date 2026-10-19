package com.cloudkingtv.townstead.life;

import java.util.UUID;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * One of the town's real residents, out on the streets: a villager-looking walker spawned near players from the
 * town's household records (see {@link Citizens}). Wears the clothes of their trade and their town's style, walks
 * the sidewalks from place to place, and tells you who they are, where they live and work and how they feel when you
 * talk to them. Never saved: they come and go with the players around them.
 */
public class CitizenNpc extends AgeableMob {
    /** How they look: the villager type (town style) times 32 plus the profession (their trade). */
    private static final EntityDataAccessor<Integer> LOOK = SynchedEntityData.defineId(CitizenNpc.class, EntityDataSerializers.INT);

    @Nullable UUID city;
    int home = -1;
    String person = "";

    public CitizenNpc(EntityType<? extends CitizenNpc> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return AgeableMob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.5);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new StrollStreetsGoal(this));
        goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 6f));
        goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(LOOK, 0);
    }

    public int look() { return entityData.get(LOOK); }

    void setLook(int look) { entityData.set(LOOK, look); }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (player instanceof ServerPlayer sp) {
            getNavigation().stop();
            getLookControl().setLookAt(player);
            Citizens.describe(sp, this);
        }
        return InteractionResult.SUCCESS;
    }

    /** Never written to disk: citizens are a sample of the town's people around the players. */
    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) { return !source.isCreativePlayer(); }

    @Override
    public boolean isPushable() { return false; }

    @Override
    protected void doPush(Entity other) {}

    @Override
    public @Nullable AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) { return null; }

    @Override
    public void aiStep() {
        // Children stay children for as long as they're out.
        if (isBaby()) setAge(-24000);
        super.aiStep();
    }

    /** Walks to somewhere else along the town's streets, pausing in between. */
    static final class StrollStreetsGoal extends Goal {
        private final CitizenNpc npc;
        private int cooldown;

        StrollStreetsGoal(CitizenNpc npc) {
            this.npc = npc;
            setFlags(java.util.EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (cooldown > 0) { cooldown--; return false; }
            return npc.getNavigation().isDone();
        }

        @Override
        public void start() {
            cooldown = 40 + npc.getRandom().nextInt(120);
            if (!(npc.level() instanceof ServerLevel level) || npc.city == null) return;
            Vec3 target = Citizens.streetPoint(level, npc.city, npc.position(), 28, npc.getRandom());
            if (target != null) npc.getNavigation().moveTo(target.x, target.y, target.z, npc.isBaby() ? 0.75 : 0.55);
        }

        @Override
        public boolean canContinueToUse() {
            return !npc.getNavigation().isDone();
        }
    }
}
