package com.cloudkingtv.townstead.planner;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.phys.Vec3;

/** Saved position and abilities to restore when a player leaves the planner view. */
public record PlannerReturn(boolean active, Vec3 pos, float yRot, float xRot, boolean mayfly, boolean flying,
                            boolean invulnerable, boolean invisible) {
    public static final PlannerReturn NONE = new PlannerReturn(false, Vec3.ZERO, 0, 0, false, false, false, false);

    public static final MapCodec<PlannerReturn> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.BOOL.fieldOf("active").forGetter(PlannerReturn::active),
            Vec3.CODEC.fieldOf("pos").forGetter(PlannerReturn::pos),
            Codec.FLOAT.fieldOf("y_rot").forGetter(PlannerReturn::yRot),
            Codec.FLOAT.fieldOf("x_rot").forGetter(PlannerReturn::xRot),
            Codec.BOOL.fieldOf("mayfly").forGetter(PlannerReturn::mayfly),
            Codec.BOOL.fieldOf("flying").forGetter(PlannerReturn::flying),
            Codec.BOOL.fieldOf("invulnerable").forGetter(PlannerReturn::invulnerable),
            Codec.BOOL.fieldOf("invisible").forGetter(PlannerReturn::invisible)
    ).apply(i, PlannerReturn::new));
}
