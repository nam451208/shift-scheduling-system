import java.util.List;
import java.util.Map;

/** Reassigns existing work only; never changes an employee's dates or hours. */
final class PositionAllocator {
    static final class Assignment {
        final int employeeId;
        final int originalPosition;
        final Map<Integer, Integer> skills;
        int position;

        Assignment(int employeeId, int position, Map<Integer, Integer> skills) {
            this.employeeId = employeeId;
            this.originalPosition = position;
            this.position = position;
            this.skills = skills;
        }
    }

    // Required positions arrive in priority order (kitchen first).
    static void rebalance(List<Assignment> assignments, Map<Integer, Integer> required) {
        Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (Assignment a : assignments) counts.merge(a.position, 1, Integer::sum);
        for (var target : required.entrySet()) {
            while (counts.getOrDefault(target.getKey(), 0) < target.getValue()) {
                Assignment chosen = null;
                for (Assignment a : assignments) {
                    Integer targetLevel = a.skills.get(target.getKey());
                    Integer sourceLevel = a.skills.get(a.position);
                    if (a.position == target.getKey() || targetLevel == null || sourceLevel == null) continue;
                    if (counts.get(a.position) <= required.getOrDefault(a.position, 0)) continue;
                    // Existing business rules use total and non-employee skill sums.
                    // Requiring a non-decreasing level preserves both, as well as headcount.
                    if (targetLevel < sourceLevel) continue;
                    chosen = a;
                    break;
                }
                if (chosen == null) break;
                counts.merge(chosen.position, -1, Integer::sum);
                chosen.position = target.getKey();
                counts.merge(chosen.position, 1, Integer::sum);
            }
        }
    }
}
