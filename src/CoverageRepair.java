import java.util.*;

/** Repairs a whole day's coverage, including an earlier start needed for a minimum shift. */
final class CoverageRepair {
    static final int SLOTS = 24;
    static final class Worker {
        final int id;
        final String type;
        final Set<Integer> positions = new HashSet<>();
        final boolean[] available = new boolean[SLOTS];
        final Integer[] assigned = new Integer[SLOTS];
        int requestedSlots;
        Worker(int id, String type) { this.id = id; this.type = type; }
    }
    record Addition(int employee, int slot, int position) { }
    private record Plan(Worker worker, int start, int end, int extra, int surplus) { }

    static List<Addition> repair(List<Worker> workers, Map<Integer, int[]> required) {
        List<Addition> result = new ArrayList<>();
        Map<Integer,int[]> counts = new HashMap<>();
        for (Worker w : workers) for (int s=0;s<SLOTS;s++) {
            if (w.assigned[s] != null) counts.computeIfAbsent(w.assigned[s], k -> new int[SLOTS])[s]++;
        }
        for (int slot=0;slot<SLOTS;slot++) for (var need : required.entrySet()) {
            int position = need.getKey();
            int[] count = counts.computeIfAbsent(position, k -> new int[SLOTS]);
            while (count[slot] < need.getValue()[slot]) {
                Plan best = null;
                for (Worker worker : workers) {
                    if (!worker.positions.contains(position) || worker.assigned[slot] != null
                            || !worker.available[slot] || "NEWBIE".equals(worker.type)) continue;
                    int first=SLOTS, last=-1, requested=0;
                    for (int s=0;s<SLOTS;s++) {
                        if (worker.available[s]) requested++;
                        if (worker.assigned[s] != null) { first=Math.min(first,s); last=s; }
                    }
                    int minimum = Math.min("FULL_TIME".equals(worker.type) ? 16 : 8,
                        worker.requestedSlots > 0 ? worker.requestedSlots : requested);
                    for (int start=0;start<=Math.min(slot,first);start++) {
                        for (int end=Math.max(slot+1,last+1);end<=SLOTS;end++) {
                            if (end-start < minimum) continue;
                            boolean valid=true;
                            int extra=0, surplus=0;
                            for (int s=start;s<end;s++) {
                                if (!worker.available[s]) { valid=false; break; }
                                if (worker.assigned[s] == null) {
                                    extra++;
                                    if (count[s] >= need.getValue()[s]) surplus++;
                                }
                            }
                            if (!valid) continue;
                            Plan candidate = new Plan(worker,start,end,extra,surplus);
                            if (better(candidate,best)) best=candidate;
                        }
                    }
                }
                if (best == null) break; // A real availability/qualification shortage stays visible.
                for (int s=best.start;s<best.end;s++) if (best.worker.assigned[s] == null) {
                    best.worker.assigned[s]=position;
                    count[s]++;
                    result.add(new Addition(best.worker.id,s,position));
                }
            }
        }
        return result;
    }

    private static boolean better(Plan a, Plan b) {
        if (b == null) return true;
        // A feasible part-timer takes over before extending a manager's day.
        int aFull="FULL_TIME".equals(a.worker.type)?1:0;
        int bFull="FULL_TIME".equals(b.worker.type)?1:0;
        if (aFull != bFull) return aFull < bFull;
        if (a.surplus != b.surplus) return a.surplus < b.surplus;
        if (a.extra != b.extra) return a.extra < b.extra;
        if (a.worker.id != b.worker.id) return a.worker.id < b.worker.id;
        return a.start < b.start;
    }
}
