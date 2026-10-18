package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.city.TilePos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * RTS-style orbit camera for the planner. It looks at a focus point on the ground from {@code distance} away;
 * pitch steepens as you zoom out. The local player's body is moved to the eye position after each tick, so
 * vanilla entity interpolation smooths the motion and the server streams chunks around the view.
 */
public final class PlannerCamera {
    public static final float MIN_DISTANCE = 12f, MAX_DISTANCE = 140f;
    /** The server kicks players moving over ~10 blocks per tick; stay well under it. */
    private static final double MAX_STEP_PER_TICK = 8.0;

    private double focusX, focusZ, targetX, targetZ, groundY;
    private float yaw, targetYaw;
    private float distance = 48f, targetDistance = 48f;
    private Vec3 lastEye;

    // Held inputs, set by PlannerScreen.
    boolean forward, back, left, right, rotateLeft, rotateRight;

    public void reset(double x, double z, float yaw) {
        focusX = targetX = x;
        focusZ = targetZ = z;
        this.yaw = targetYaw = yaw;
        distance = targetDistance = 48f;
        Minecraft mc = Minecraft.getInstance();
        groundY = mc.level == null ? 64 : mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(x), Mth.floor(z));
        lastEye = null;
        forward = back = left = right = rotateLeft = rotateRight = false;
    }

    public void zoom(double scrollSteps) {
        targetDistance = Mth.clamp((float) (targetDistance * Math.pow(0.88, scrollSteps)), MIN_DISTANCE, MAX_DISTANCE);
    }

    public void rotate(float degrees) {
        targetYaw += degrees;
    }

    public float distance() {
        return distance;
    }

    /** Pitch in degrees: oblique when close, nearly top-down when far. */
    public float pitch() {
        float t = (distance - MIN_DISTANCE) / (MAX_DISTANCE - MIN_DISTANCE);
        return Mth.lerp(t, 38f, 72f);
    }

    public void tick(LocalPlayer player, CitySummary city) {
        double speed = (0.6 + distance * 0.035) * TownsteadConfig.PLANNER_PAN_SPEED.get();
        double fx = -Mth.sin(yaw * Mth.DEG_TO_RAD), fz = Mth.cos(yaw * Mth.DEG_TO_RAD);
        double mx = 0, mz = 0;
        if (forward) { mx += fx; mz += fz; }
        if (back) { mx -= fx; mz -= fz; }
        if (left) { mx += fz; mz -= fx; }
        if (right) { mx -= fz; mz += fx; }
        double len = Math.sqrt(mx * mx + mz * mz);
        if (len > 0) { targetX += mx / len * speed; targetZ += mz / len * speed; }
        if (rotateLeft) targetYaw -= 3.5f;
        if (rotateRight) targetYaw += 3.5f;

        clampToCity(city);

        float ease = TownsteadConfig.REDUCE_MOTION.get() ? 0.55f : 0.3f;
        focusX += (targetX - focusX) * ease;
        focusZ += (targetZ - focusZ) * ease;
        yaw += (targetYaw - yaw) * ease;
        distance += (targetDistance - distance) * ease;
        int ground = player.level().getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(focusX), Mth.floor(focusZ));
        groundY += (ground - groundY) * 0.2;

        Vec3 look = Vec3.directionFromRotation(pitch(), yaw);
        Vec3 eye = new Vec3(focusX, groundY, focusZ).subtract(look.scale(distance));
        int groundAtEye = player.level().getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(eye.x), Mth.floor(eye.z));
        if (eye.y < groundAtEye + 4) eye = new Vec3(eye.x, groundAtEye + 4, eye.z);
        if (lastEye != null) {
            Vec3 step = eye.subtract(lastEye);
            if (step.length() > MAX_STEP_PER_TICK) eye = lastEye.add(step.normalize().scale(MAX_STEP_PER_TICK));
        }
        lastEye = eye;

        player.setDeltaMovement(Vec3.ZERO);
        player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(pitch());
    }

    /** Keep the focus over the town (plus a margin) so you can't wander off into unloaded land. */
    private void clampToCity(CitySummary city) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (TilePos t : city.tiles()) {
            minX = Math.min(minX, t.minBlockX());
            minZ = Math.min(minZ, t.minBlockZ());
            maxX = Math.max(maxX, t.minBlockX() + TilePos.SIZE);
            maxZ = Math.max(maxZ, t.minBlockZ() + TilePos.SIZE);
        }
        int margin = TilePos.SIZE;
        targetX = Mth.clamp(targetX, minX - margin, maxX + margin);
        targetZ = Mth.clamp(targetZ, minZ - margin, maxZ + margin);
    }
}
