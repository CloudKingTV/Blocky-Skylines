package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.network.ChirpPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;

/** The chirps this client has received, newest last. The planner shows the latest few. */
public final class ClientChirps {
    public record Entry(ChirpPayload chirp, long at) {}

    private static final int KEEP = 40;
    private static final List<Entry> ENTRIES = new ArrayList<>();

    public static void add(ChirpPayload chirp) {
        ENTRIES.add(new Entry(chirp, Util.getMillis()));
        while (ENTRIES.size() > KEEP) ENTRIES.removeFirst();
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.8f, 0.25f));
    }

    public static List<Entry> all() {
        return ENTRIES;
    }

    public static void clear() {
        ENTRIES.clear();
    }

    private ClientChirps() {}
}
