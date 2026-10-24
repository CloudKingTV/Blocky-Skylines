package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.TownsteadConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

/**
 * The info views' colours. By default good is green and bad red, with a red-yellow-green scale; with the client
 * setting "colourBlind" good is blue and bad orange (the Okabe-Ito pair), with a dark blue to yellow scale, which
 * read apart with every common kind of colour blindness. Legends name the colours of the palette in use.
 */
public final class Palette {
    static boolean safe() {
        return TownsteadConfig.COLOUR_BLIND.get();
    }

    static int good(int alpha) {
        return safe() ? ARGB.color(alpha, 60, 140, 215) : ARGB.color(alpha, 158, 227, 125);
    }

    static int bad(int alpha) {
        return safe() ? ARGB.color(alpha, 230, 159, 0) : ARGB.color(alpha, 255, 107, 91);
    }

    /** Half-way: amber, or a light grey in the colour-blind palette. */
    static int warn(int alpha) {
        return safe() ? ARGB.color(alpha, 190, 190, 180) : ARGB.color(alpha, 224, 176, 64);
    }

    /** Bad (0) through middling to good (1). */
    static int gradient(float f, int alpha) {
        f = Mth.clamp(f, 0f, 1f);
        if (safe()) {
            // Dark blue, through grey, to yellow (after cividis).
            int r = Math.round(f < 0.5f ? Mth.lerp(f * 2, 30, 125) : Mth.lerp((f - 0.5f) * 2, 125, 250));
            int g = Math.round(f < 0.5f ? Mth.lerp(f * 2, 60, 125) : Mth.lerp((f - 0.5f) * 2, 125, 225));
            int b = Math.round(f < 0.5f ? Mth.lerp(f * 2, 140, 120) : Mth.lerp((f - 0.5f) * 2, 120, 50));
            return ARGB.color(alpha, r, g, b);
        }
        int r = f < 0.5f ? 230 : Math.round(230 - (f - 0.5f) * 2 * 140);
        int g = f < 0.5f ? Math.round(70 + f * 2 * 150) : 220;
        return ARGB.color(alpha, r, g, 70);
    }

    /** A legend, in the words of the palette in use. */
    static Component legend(String key) {
        return Component.translatable(safe() ? key + ".cb" : key);
    }

    private Palette() {}
}
