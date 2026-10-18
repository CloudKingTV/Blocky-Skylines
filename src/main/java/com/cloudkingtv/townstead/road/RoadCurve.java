package com.cloudkingtv.townstead.road;

import net.minecraft.world.phys.Vec2;

/**
 * A road centreline: a quadratic Bezier on the horizontal plane (a straight road has its control point at the
 * middle). Coordinates are block-space doubles; road points usually sit on block centres (x.5).
 */
public record RoadCurve(double ax, double az, double cx, double cz, double bx, double bz) {

    public static RoadCurve straight(double ax, double az, double bx, double bz) {
        return new RoadCurve(ax, az, (ax + bx) / 2, (az + bz) / 2, bx, bz);
    }

    public double x(double t) {
        double u = 1 - t;
        return u * u * ax + 2 * u * t * cx + t * t * bx;
    }

    public double z(double t) {
        double u = 1 - t;
        return u * u * az + 2 * u * t * cz + t * t * bz;
    }

    /** Unit tangent at t (falls back to the chord for degenerate curves). */
    public Vec2 direction(double t) {
        double dx = 2 * (1 - t) * (cx - ax) + 2 * t * (bx - cx);
        double dz = 2 * (1 - t) * (cz - az) + 2 * t * (bz - cz);
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) {
            dx = bx - ax;
            dz = bz - az;
            len = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
        }
        return new Vec2((float) (dx / len), (float) (dz / len));
    }

    public double length() {
        double total = 0, px = ax, pz = az;
        for (int i = 1; i <= 32; i++) {
            double t = i / 32.0, x = x(t), z = z(t);
            total += Math.sqrt((x - px) * (x - px) + (z - pz) * (z - pz));
            px = x;
            pz = z;
        }
        return total;
    }

    /** Exact split at t (de Casteljau): returns {first, second}. */
    public RoadCurve[] split(double t) {
        double l1x = ax + (cx - ax) * t, l1z = az + (cz - az) * t;
        double l2x = cx + (bx - cx) * t, l2z = cz + (bz - cz) * t;
        double mx = x(t), mz = z(t);
        return new RoadCurve[] {new RoadCurve(ax, az, l1x, l1z, mx, mz), new RoadCurve(mx, mz, l2x, l2z, bx, bz)};
    }

    public RoadCurve reversed() {
        return new RoadCurve(bx, bz, cx, cz, ax, az);
    }

    /** Parameter of the point on the curve closest to (x, z), by sampling then refining. */
    public double closestT(double x, double z) {
        int n = Math.max(8, (int) Math.ceil(length() * 2));
        double best = 0, bestD = Double.MAX_VALUE;
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n, d = dist2(t, x, z);
            if (d < bestD) { bestD = d; best = t; }
        }
        double step = 1.0 / n;
        for (int k = 0; k < 20; k++) {
            step /= 2;
            double lo = Math.max(0, best - step), hi = Math.min(1, best + step);
            if (dist2(lo, x, z) < bestD) { best = lo; bestD = dist2(lo, x, z); }
            else if (dist2(hi, x, z) < bestD) { best = hi; bestD = dist2(hi, x, z); }
        }
        return best;
    }

    public double distance(double t, double x, double z) {
        return Math.sqrt(dist2(t, x, z));
    }

    private double dist2(double t, double x, double z) {
        double dx = x(t) - x, dz = z(t) - z;
        return dx * dx + dz * dz;
    }

    /** Largest turn this curve makes, in degrees (0 for straight). Used to reject hairpins. */
    public double turnDegrees() {
        Vec2 a = direction(0), b = direction(1);
        double dot = Math.max(-1, Math.min(1, a.x * b.x + a.y * b.y));
        return Math.toDegrees(Math.acos(dot));
    }
}
