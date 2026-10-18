package com.cloudkingtv.townstead.city;

import com.cloudkingtv.townstead.road.Material;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Helpers over a town's material stock: item id to count. Ids (not items) are stored so a removed mod's items
 * don't break loading a save.
 */
public final class Stock {
    public static Item item(Identifier id) {
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == null ? Items.AIR : item;
    }

    public static Identifier id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    public static int count(Map<Identifier, Integer> stock, Material material) {
        int total = 0;
        for (var e : stock.entrySet()) if (material.matches(item(e.getKey()))) total += e.getValue();
        return total;
    }

    /** What's short for a bill, by material (empty when everything is in stock). */
    public static Map<Material, Integer> missing(Map<Identifier, Integer> stock, Map<Material, Integer> cost) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        cost.forEach((m, n) -> {
            int have = count(stock, m);
            if (have < n) out.put(m, n - have);
        });
        return out;
    }

    public static void add(Map<Identifier, Integer> stock, Item item, int n) {
        if (n > 0 && item != Items.AIR) stock.merge(id(item), n, Integer::sum);
    }

    /** Takes {@code n} of an exact item; returns how many were taken. */
    public static int take(Map<Identifier, Integer> stock, Item item, int n) {
        Identifier id = id(item);
        int have = stock.getOrDefault(id, 0), taken = Math.min(have, n);
        if (have - taken <= 0) stock.remove(id);
        else stock.put(id, have - taken);
        return taken;
    }

    /** Consumes a bill, drawing on the most plentiful item of each material first. Call only after {@link #missing}. */
    public static void consume(Map<Identifier, Integer> stock, Map<Material, Integer> cost) {
        cost.forEach((m, n) -> {
            int left = n;
            List<Map.Entry<Identifier, Integer>> matching = new ArrayList<>();
            for (var e : stock.entrySet()) if (m.matches(item(e.getKey()))) matching.add(Map.entry(e.getKey(), e.getValue()));
            matching.sort(Comparator.comparingInt((Map.Entry<Identifier, Integer> e) -> e.getValue()).reversed());
            for (var e : matching) {
                if (left <= 0) break;
                left -= take(stock, item(e.getKey()), left);
            }
        });
    }

    private Stock() {}
}
