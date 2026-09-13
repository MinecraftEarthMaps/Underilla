package org.popcraft.chunky;

import java.util.TreeMap;

/** The first unfinished position, independent of asynchronous completion order. */
final class CompletionCursor {
    private final TreeMap<Long, Long> completed = new TreeMap<>();
    private long next;

    CompletionCursor(long start) { next = start; }
    synchronized long get() { return next; }

    synchronized void complete(long index) {
        if (index < next) return;
        long start = index, end = index + 1;
        var before = completed.floorEntry(index);
        if (before != null && before.getValue() >= index) {
            start = before.getKey(); end = Math.max(end, before.getValue());
            completed.remove(before.getKey());
        }
        var after = completed.ceilingEntry(start);
        while (after != null && after.getKey() <= end) {
            end = Math.max(end, after.getValue()); completed.remove(after.getKey());
            after = completed.ceilingEntry(start);
        }
        if (start == next) next = end; else completed.put(start, end);
    }
}
