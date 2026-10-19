package com.cloudkingtv.townstead.progress;

import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.economy.TownEconomy;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.sim.TownModel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Server side of milestones: notices when a town's population reaches the next one, pays out and celebrates. */
public final class Milestones {
    public static void check(ServerLevel level, City city, TownModel model) {
        Milestone next = Milestone.of(city.milestone()).next();
        int people = model.demand.population();
        while (next != null && people >= next.population && next.ordinal() > city.milestone()) {
            reach(level, city, next);
            next = next.next();
        }
    }

    static void reach(ServerLevel level, City city, Milestone m) {
        CityManager.get(level.getServer()).reachMilestone(level.getServer(), city, m.ordinal(), m.reward * TownEconomy.EMERALD);
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(city.owner());
        if (owner != null) {
            owner.connection.send(new ClientboundSetTitleTextPacket(m.displayName()));
            owner.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("milestone.townstead.subtitle", city.name(), m.reward)));
            level.playSound(null, owner.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
            RoadEditor.notify(owner, Component.translatable("milestone.townstead.reached", m.displayName(), m.reward), true);
        }
        Chirps.post(level, city, "milestone", city.townHall(), true, city.name(), m.displayName());
    }

    private Milestones() {}
}
