package com.cloudkingtv.townstead.city;

import com.cloudkingtv.townstead.registry.TownsteadRegistry;
import com.cloudkingtv.townstead.road.Material;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Server rules for the Town Storehouse: who may use it, and moving items between players and the town's stock. */
public final class StorehouseService {
    /** Reach for the storehouse screen's buttons (a little more than block interaction range). */
    private static final double REACH = 8;

    /** The town whose storehouse is at pos, if the player may use it. */
    public static Optional<City> access(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        // Reach first: a far-off position would load (or generate) its chunk just to read the block.
        if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > REACH * REACH || !level.isLoaded(pos)) return Optional.empty();
        if (!level.getBlockState(pos).is(TownsteadRegistry.TOWN_STOREHOUSE.get())) return Optional.empty();
        Optional<City> city = CityManager.get(level.getServer()).at(level, pos);
        if (city.isEmpty() || !city.get().isMayor(player.getUUID())) {
            player.sendOverlayMessage(Component.translatable("message.townstead.storehouse.not_yours"));
            return Optional.empty();
        }
        return city;
    }

    public static boolean accepts(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Material.BUILDING_MATERIALS) && stack.isComponentsPatchEmpty();
    }

    /** Puts the whole stack in the town's stock. */
    public static void deposit(ServerPlayer player, City city, ItemStack stack) {
        if (!accepts(stack)) return;
        Item item = stack.getItem();
        int n = stack.getCount();
        CityManager.get(player.level().getServer()).editStock(player.level().getServer(), city, stock -> Stock.add(stock, item, n));
        stack.setCount(0);
        feedback(player, Component.translatable("message.townstead.storehouse.deposited", n, new ItemStack(item).getHoverName()));
    }

    /** Puts every building material in the player's inventory (hotbar included) into the stock. */
    public static void depositAll(ServerPlayer player, City city) {
        int total = 0;
        var cities = CityManager.get(player.level().getServer());
        var items = player.getInventory().getNonEquipmentItems();
        java.util.Map<Item, Integer> moved = new java.util.HashMap<>();
        for (ItemStack stack : items) {
            if (!accepts(stack)) continue;
            moved.merge(stack.getItem(), stack.getCount(), Integer::sum);
            total += stack.getCount();
            stack.setCount(0);
        }
        if (total == 0) {
            player.sendOverlayMessage(Component.translatable("message.townstead.storehouse.nothing"));
            return;
        }
        cities.editStock(player.level().getServer(), city, stock -> moved.forEach((item, n) -> Stock.add(stock, item, n)));
        player.getInventory().setChanged();
        feedback(player, Component.translatable("message.townstead.storehouse.deposited_all", total));
    }

    /** Takes up to {@code count} (at most a stack) of one item out of the stock into the player's inventory. */
    public static void withdraw(ServerPlayer player, City city, Identifier id, int count) {
        Item item = Stock.item(id);
        if (item == Items.AIR) return;
        int want = Math.max(1, Math.min(count, item.getDefaultMaxStackSize()));
        int[] taken = new int[1];
        CityManager.get(player.level().getServer()).editStock(player.level().getServer(), city, stock -> taken[0] = Stock.take(stock, item, want));
        if (taken[0] <= 0) return;
        player.getInventory().placeItemBackInInventory(new ItemStack(item, taken[0]), Prediction.SERVER_ONLY);
        player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4f, 1.2f);
    }

    private static void feedback(ServerPlayer player, Component message) {
        player.level().playSound(null, player.blockPosition(), SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.6f, 1.1f);
        player.sendOverlayMessage(message);
    }

    private StorehouseService() {}
}
