package com.cloudkingtv.townstead.client.planner;

/** Scratch-only: the advisor's state for tests. */
public final class AdvisorProbe {
    public static String steps(com.cloudkingtv.townstead.city.CitySummary c) {
        return Advisor.firstSteps(c).toString();
    }

    public static java.util.List<String> tips(com.cloudkingtv.townstead.city.CitySummary c) {
        return Advisor.tips(c, PlannerClient.model()).stream().map(t -> t.key() + (t.key().equals("steps") ? ":" + t.args()[2] : "")).toList();
    }

    /** Each tip as shown, in order: its key and its arguments. */
    public static java.util.List<Object[]> tipArgs(com.cloudkingtv.townstead.city.CitySummary c) {
        return Advisor.tips(c, PlannerClient.model()).stream().map(t -> new Object[] {t.key(), t.args()}).toList();
    }

    private AdvisorProbe() {}
}
