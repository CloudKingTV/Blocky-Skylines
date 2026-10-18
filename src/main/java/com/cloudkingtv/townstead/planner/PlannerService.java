package com.cloudkingtv.townstead.planner;

import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.network.PlannerStatePayload;
import com.cloudkingtv.townstead.registry.TownsteadRegistry;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of the planner view. The player's body becomes the camera: it flies, is invisible and can't be hurt,
 * so the server keeps streaming chunks around the view. The client steers the position.
 */
public final class PlannerService {
    /** Camera height above the Town Hall when entering. */
    private static final double ENTRY_HEIGHT = 40;

    public static boolean isPlanning(ServerPlayer player) {
        return player.getData(TownsteadRegistry.PLANNER_RETURN).active();
    }

    public static void enter(ServerPlayer player) {
        if (isPlanning(player)) return;
        ServerLevel level = player.level();
        CityManager cities = CityManager.get(level.getServer());
        // Planner opens on the town you stand in, else the town you run.
        Optional<City> city = cities.at(level, player.blockPosition())
                .filter(c -> c.owner().equals(player.getUUID()))
                .or(() -> cities.ownedBy(player.getUUID()).filter(c -> c.dimension().equals(level.dimension())));
        if (city.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("message.townstead.planner.no_town"));
            return;
        }
        var abilities = player.getAbilities();
        player.setData(TownsteadRegistry.PLANNER_RETURN, new PlannerReturn(true, player.position(), player.getYRot(),
                player.getXRot(), abilities.mayfly, abilities.flying, abilities.invulnerable, player.isInvisible()));
        abilities.mayfly = true;
        abilities.flying = true;
        abilities.invulnerable = true;
        player.onUpdateAbilities();
        player.setInvisible(true);

        BlockPos hall = city.get().townHall();
        int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE, hall.getX(), hall.getZ());
        double y = Math.max(ground, hall.getY()) + ENTRY_HEIGHT;
        player.teleportTo(level, hall.getX() + 0.5, y, hall.getZ() + 0.5 + 28, Set.of(), 180f, 55f, true);
        level.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1f);
        PacketDistributor.sendToPlayer(player, new PlannerStatePayload(true, city.get().id()));
    }

    public static void exit(ServerPlayer player) {
        PlannerReturn back = player.getData(TownsteadRegistry.PLANNER_RETURN);
        if (!back.active()) return;
        restore(player, back);
        PacketDistributor.sendToPlayer(player, new PlannerStatePayload(false, PlannerStatePayload.NO_CITY));
    }

    /** Puts the body back. Also used on login if the player disconnected while planning. */
    public static void restore(ServerPlayer player, PlannerReturn back) {
        var abilities = player.getAbilities();
        abilities.mayfly = back.mayfly() || player.isCreative() || player.isSpectator();
        abilities.flying = back.flying() && abilities.mayfly;
        abilities.invulnerable = back.invulnerable() || player.isCreative() || player.isSpectator();
        player.onUpdateAbilities();
        player.setInvisible(back.invisible());
        Vec3 p = back.pos();
        player.teleportTo(player.level(), p.x, p.y, p.z, Set.of(), back.yRot(), back.xRot(), true);
        player.fallDistance = 0;
        player.setData(TownsteadRegistry.PLANNER_RETURN, PlannerReturn.NONE);
    }

    private PlannerService() {}
}
