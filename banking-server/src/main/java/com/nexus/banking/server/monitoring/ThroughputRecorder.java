package com.nexus.banking.server.monitoring;

import com.nexus.banking.contract.dto.ThroughputPointDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Ring of fixed 10-second buckets (last hour) counting individual ledger entries. */
public final class ThroughputRecorder {
    public static final int BUCKET_SECONDS = 10;
    private static final int MAX_BUCKETS = 360;

    private final TreeMap<Long, int[]> buckets = new TreeMap<>();

    private static long bucketOf(long epochMillis) {
        return epochMillis / 1000 / BUCKET_SECONDS;
    }

    private synchronized void add(int slot, int n, long nowMillis) {
        int[] b = buckets.computeIfAbsent(bucketOf(nowMillis), k -> new int[3]);
        b[slot] += n;
        while (buckets.size() > MAX_BUCKETS) buckets.pollFirstEntry();
    }

    public void stored(int n) { add(0, n, System.currentTimeMillis()); }
    public void replicatedIn(int n) { add(1, n, System.currentTimeMillis()); }
    public void replicatedOut(int n) { add(2, n, System.currentTimeMillis()); }

    /** Contiguous buckets for the last {@code minutes} minutes, empty buckets included as zeros. */
    public List<ThroughputPointDTO> series(int minutes, long nowMillis) {
        long last = bucketOf(nowMillis);
        long first = last - (long) minutes * 60 / BUCKET_SECONDS + 1;
        List<ThroughputPointDTO> out = new ArrayList<>();
        synchronized (this) {
            for (long k = first; k <= last; k++) {
                int[] b = buckets.getOrDefault(k, new int[3]);
                out.add(new ThroughputPointDTO(Instant.ofEpochSecond(k * BUCKET_SECONDS), BUCKET_SECONDS, b[0], b[1], b[2]));
            }
        }
        return out;
    }
}
