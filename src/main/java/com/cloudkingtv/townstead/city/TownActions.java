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

    private TownActions() {}
}
