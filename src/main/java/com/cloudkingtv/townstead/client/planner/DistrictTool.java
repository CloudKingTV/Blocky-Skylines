package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.district.District;
import com.cloudkingtv.townstead.district.DistrictEditor;
import org.jspecify.annotations.Nullable;

/**
 * The districts tool, as in Cities: Skylines: paint cells into a new district, into the selected one, or out of any;
 * or click a district to select it and see its policies.
 */
public final class DistrictTool {
    /** 0: paint a new district, 1: paint into the selected one, 2: erase, 3: select. */
    public static int mode;
    /** The selected district's id, or -1. */
    public static int selected = -1;
    /** After painting a new district: select whichever district owns this cell once the server answers. */
    private static long pendingCell = Long.MIN_VALUE;

    /** The district id a finished stroke paints into (or {@link DistrictEditor#NEW} / {@link DistrictEditor#ERASE}). */
    static int strokeTarget() {
        return switch (mode) {
            case 1 -> selected >= 0 ? selected : DistrictEditor.NEW;
            case 2 -> DistrictEditor.ERASE;
            default -> DistrictEditor.NEW;
        };
    }

    static void painted(long firstCell) {
        if (strokeTarget() == DistrictEditor.NEW) pendingCell = firstCell;
    }

    static @Nullable District selected(CitySummary city) {
        if (pendingCell != Long.MIN_VALUE) {
            District d = city.civics().district(pendingCell);
            if (d != null) { selected = d.id(); pendingCell = Long.MIN_VALUE; if (mode == 0) mode = 1; }
        }
        for (District d : city.districts()) if (d.id() == selected) return d;
        return null;
    }

    static void reset() {
        pendingCell = Long.MIN_VALUE;
    }

    private DistrictTool() {}
}
