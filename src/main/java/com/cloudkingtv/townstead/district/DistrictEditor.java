package com.cloudkingtv.townstead.district;

import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.road.RoadRaster;
import com.cloudkingtv.townstead.zone.ZoneRules;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Planner edits to districts: painting cells into one (or a new one), erasing, policies and specialisations. */
public final class DistrictEditor {
    /** Paint into a new district / erase, as the {@code district} argument of {@link #paint}. */
    public static final int NEW = -1, ERASE = -2;
    public static final int MAX_DISTRICTS = 16;

    /** Paints cells (only the town's own land) into a district, a new one, or out of any. */
    public static void paint(ServerPlayer player, City city, int district, List<Long> cells) {
        Set<Long> mine = new LinkedHashSet<>();
        for (long c : cells) {
            int bx = RoadRaster.keyX(c) * ZoneRules.CELL, bz = RoadRaster.keyZ(c) * ZoneRules.CELL;
            if (city.tiles().contains(TilePos.ofBlock(bx, bz))) mine.add(c);
        }
        if (mine.isEmpty()) return;
        CityManager cities = CityManager.get(player.level().getServer());
        List<District> list = new ArrayList<>(city.districts());
        int target = district;
        if (district == NEW) {
            if (list.size() >= MAX_DISTRICTS) {
                RoadEditor.notify(player, Component.translatable("district.townstead.too_many", MAX_DISTRICTS), false);
                return;
            }
            target = cities.nextDistrictId(city);
            long seed = target * 7919L + city.id().getLeastSignificantBits();
            String name = District.name(seed);
            for (int tries = 0; tries < 8 && taken(list, name); tries++) name = District.name(seed + tries + 1);
            list.add(new District(target, name, District.PALETTE[(target - 1) % District.PALETTE.length], new LinkedHashSet<>(),
                    EnumSet.noneOf(Policy.class), Specialization.NONE));
            RoadEditor.notify(player, Component.translatable("district.townstead.founded", name), true);
        }
        List<District> out = new ArrayList<>();
        for (District d : list) {
            Set<Long> cs = new LinkedHashSet<>(d.cells());
            cs.removeAll(mine);
            if (d.id() == target) cs.addAll(mine);
            if (!cs.isEmpty()) out.add(d.withCells(cs));
        }
        cities.setDistricts(player.level().getServer(), city, out);
        player.level().playSound(null, player.blockPosition(), district == ERASE ? SoundEvents.BRUSH_GENERIC : SoundEvents.BOOK_PAGE_TURN,
                SoundSource.PLAYERS, 0.6f, 1.2f);
    }

    private static boolean taken(List<District> list, String name) {
        for (District d : list) if (d.name().equals(name)) return true;
        return false;
    }

    /** Turns a policy on or off for a district ({@code district} &lt; 0: the whole town). */
    public static void setPolicy(ServerPlayer player, City city, int district, Policy policy, boolean on) {
        CityManager cities = CityManager.get(player.level().getServer());
        if (district < 0) {
            cities.setPolicy(player.level().getServer(), city, policy, on);
        } else {
            if (!policy.local) return;
            List<District> out = new ArrayList<>();
            for (District d : city.districts()) {
                if (d.id() != district) { out.add(d); continue; }
                Set<Policy> ps = EnumSet.noneOf(Policy.class);
                ps.addAll(d.policies());
                if (on) ps.add(policy); else ps.remove(policy);
                out.add(d.withPolicies(ps));
            }
            cities.setDistricts(player.level().getServer(), city, out);
        }
        player.level().playSound(null, player.blockPosition(), on ? SoundEvents.UI_BUTTON_CLICK.value() : SoundEvents.WOODEN_BUTTON_CLICK_OFF,
                SoundSource.PLAYERS, 0.5f, on ? 1.3f : 0.9f);
    }

    public static void setSpec(ServerPlayer player, City city, int district, Specialization spec) {
        List<District> out = new ArrayList<>();
        for (District d : city.districts()) out.add(d.id() == district ? d.withSpec(spec) : d);
        CityManager.get(player.level().getServer()).setDistricts(player.level().getServer(), city, out);
    }

    public static void delete(ServerPlayer player, City city, int district) {
        List<District> out = new ArrayList<>(city.districts());
        out.removeIf(d -> d.id() == district);
        CityManager.get(player.level().getServer()).setDistricts(player.level().getServer(), city, out);
    }

    private DistrictEditor() {}
}
