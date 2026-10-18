package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.road.RoadNetwork;

/** The latest road graph of the player's dimension, from the server. Client thread only. */
public final class ClientRoads {
    private static RoadNetwork network = new RoadNetwork();
    private static int version;

    public static RoadNetwork network() {
        return network;
    }

    /** Bumped on every update so cached previews know to recompute. */
    public static int version() {
        return version;
    }

    public static void set(RoadNetwork latest) {
        network = latest;
        version++;
    }

    public static void clear() {
        set(new RoadNetwork());
    }

    private ClientRoads() {}
}
