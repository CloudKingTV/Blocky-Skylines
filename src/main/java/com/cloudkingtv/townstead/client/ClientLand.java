package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.zone.LandData;

/** The latest zoning and buildings of the player's dimension, from the server. Client thread only. */
public final class ClientLand {
    private static LandData land = new LandData();
    private static int version;

    public static LandData get() {
        return land;
    }

    public static int version() {
        return version;
    }

    public static void set(LandData latest) {
        land = latest;
        version++;
    }

    public static void clear() {
        set(new LandData());
    }

    private ClientLand() {}
}
