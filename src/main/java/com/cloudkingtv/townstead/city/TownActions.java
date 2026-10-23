package com.cloudkingtv.townstead.city;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Player-initiated town changes, validated on the server. Shared by payload handlers and commands. */
public final class TownActions {
    public static boolean rename(ServerPlayer player, City city, String requested) {
        CityManager cities = CityManager.get(player.level().getServer());
        if (!city.owner().equals(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("message.townstead.not_mayor"));
            return false;
        }
        Optional<String> name = CityManager.sanitizeName(requested);
        if (name.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.townstead.rename.invalid", CityManager.MAX_NAME_LENGTH));
            return false;
        }
        if (name.get().equals(city.name())) return true;
        String old = city.name();
        cities.rename(player.level().getServer(), city, name.get());
        player.sendSystemMessage(Component.translatable("message.townstead.rename.success", old, name.get()));
        return true;
    }

    /** Price of a town's next tile of land, in emeralds: 40 for the first bought, 40 more for each after. */
    public static long tilePrice(int ownedTiles) {
        return Math.round(40L * Math.max(1, ownedTiles - 8) * com.cloudkingtv.townstead.Difficulty.current().costs);
    }

    /** Why a tile can't be bought right now (a translation key), or null if it can. Shared by the planner and the server. */
    public static @org.jspecify.annotations.Nullable String buyProblem(java.util.Set<TilePos> owned, int milestone, long treasury, TilePos tile,
                                                                     java.util.function.Predicate<TilePos> blocked) {
        if (owned.contains(tile)) return "land.townstead.owned";
        boolean next = owned.contains(tile.offset(1, 0)) || owned.contains(tile.offset(-1, 0)) || owned.contains(tile.offset(0, 1)) || owned.contains(tile.offset(0, -1));
        if (!next) return "land.townstead.not_next";
        if (blocked.test(tile)) return "land.townstead.too_close";
        if (!com.cloudkingtv.townstead.TownsteadConfig.UNLOCK_ALL.get()
                && owned.size() >= com.cloudkingtv.townstead.progress.Milestone.tileAllowance(milestone)) return "land.townstead.need_milestone";
        if (treasury < tilePrice(owned.size()) * com.cloudkingtv.townstead.economy.TownEconomy.EMERALD) return "land.townstead.too_poor";
        return null;
    }

    public static void buyTile(ServerPlayer player, City city, TilePos tile) {
        var server = player.level().getServer();
        CityManager cities = CityManager.get(server);
        String problem = buyProblem(city.tiles(), city.milestone(), city.treasury(), tile, t -> cities.blockedForBuying(player.level(), city, t));
        if (problem != null) {
            com.cloudkingtv.townstead.road.RoadEditor.notify(player, Component.translatable(problem), false);
            return;
        }
        long price = tilePrice(city.tiles().size());
        cities.buyTile(server, city, tile, price * com.cloudkingtv.townstead.economy.TownEconomy.EMERALD);
        player.level().playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.7f, 1.4f);
        com.cloudkingtv.townstead.road.RoadEditor.notify(player, Component.translatable("land.townstead.bought", price, city.tiles().size()), true);
        com.cloudkingtv.townstead.progress.Chirps.post(player.level(), city, "land",
                new net.minecraft.core.BlockPos(tile.minBlockX() + TilePos.SIZE / 2, city.townHall().getY(), tile.minBlockZ() + TilePos.SIZE / 2),
                true, city.name());
    }

    private TownActions() {}
}
