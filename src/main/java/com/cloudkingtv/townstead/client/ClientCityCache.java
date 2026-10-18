package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.city.CitySummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Latest city summaries received from the server. Client thread only. */
public final class ClientCityCache {
    private static List<CitySummary> cities = List.of();

    public static void set(List<CitySummary> latest) {
        cities = List.copyOf(latest);
    }

    public static List<CitySummary> all() {
        return cities;
    }

    public static Optional<CitySummary> byId(UUID id) {
        return cities.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public static void clear() {
        cities = List.of();
    }

    private ClientCityCache() {}
}
