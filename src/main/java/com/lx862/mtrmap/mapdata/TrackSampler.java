package com.lx862.mtrmap.mapdata;

import com.lx862.mtrmap.mtr.MtrCompat;
import mtr.data.Rail;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Samples a rail's real geometry (arcs, slopes) into an X/Z polyline.
 * Shared by the client-side cache and the server-side network collector so both
 * produce identical track data.
 *
 * <p>MTR 3 exposes the curve directly on {@link Rail} ({@code getLength()} /
 * {@code getPosition(double)} returning a {@link Vec3}); MTR 4 needed a separate
 * {@code RailMath} object with a two-argument {@code getPosition}.</p>
 */
public final class TrackSampler {

    /** World-block distance between samples along a rail curve. */
    public static final double SAMPLE_INTERVAL = 8.0;
    private static final int MAX_SAMPLES_PER_RAIL = 256;

    private TrackSampler() {
    }

    /**
     * Sample one rail into a polyline. Returns {@code null} when the rail is not
     * a train rail or has no usable geometry.
     */
    public static List<double[]> sample(Rail rail) {
        try {
            if (!MtrCompat.isDrawableRail(rail)) {
                return null;
            }
            final double length = rail.getLength();
            if (!Double.isFinite(length) || length <= 0) {
                return null;
            }

            final int sampleCount = (int) Math.min(MAX_SAMPLES_PER_RAIL,
                    Math.max(2, Math.ceil(length / SAMPLE_INTERVAL) + 1));
            final ArrayList<double[]> points = new ArrayList<>(sampleCount);
            for (int i = 0; i < sampleCount; i++) {
                final double distance = Math.min(length, i * (length / (sampleCount - 1)));
                final Vec3 pos = rail.getPosition(distance);
                if (pos == null || !Double.isFinite(pos.x) || !Double.isFinite(pos.z)) {
                    return null;
                }
                points.add(new double[]{pos.x, pos.z});
            }
            return points;
        } catch (Throwable e) {
            return null;
        }
    }
}
