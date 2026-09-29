import java.util.*;

/** Synthetic fixtures only: no database or production data is accessed. */
public class PositionAllocationTests {
    private static int checks;
    private static final int HALL = 10, KITCHEN = 30;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static PositionAllocator.Assignment worker(int id, int position, Map<Integer,Integer> skills) {
        return new PositionAllocator.Assignment(id, position, skills);
    }
    private static Map<Integer,Integer> required(int hall, int kitchen) {
        Map<Integer,Integer> result = new LinkedHashMap<>();
        result.put(KITCHEN, kitchen);
        result.put(HALL, hall);
        return result;
    }
    public static void main(String[] args) {
        // October 7/8 pattern: both people in hall, but the manager can cover kitchen.
        var manager = worker(1, HALL, Map.of(HALL,3,KITCHEN,3));
        var hallOnly = worker(2, HALL, Map.of(HALL,2));
        PositionAllocator.rebalance(List.of(manager,hallOnly), required(1,1));
        check(manager.position == KITCHEN && hallOnly.position == HALL, "Flexible worker fills kitchen");
        check(manager.originalPosition == HALL, "Original assignment retained for guarded update");
        PositionAllocator.rebalance(List.of(manager,hallOnly), required(1,1));
        check(manager.position == KITCHEN && hallOnly.position == HALL, "Rebalancing is idempotent");

        var alone = worker(3,HALL,Map.of(HALL,3,KITCHEN,3));
        PositionAllocator.rebalance(List.of(alone),required(1,1));
        check(alone.position == HALL, "True headcount shortage cannot be moved to hall");

        var unqualified = worker(4,HALL,Map.of(HALL,3));
        var unqualified2 = worker(5,HALL,Map.of(HALL,2));
        PositionAllocator.rebalance(List.of(unqualified,unqualified2),required(1,1));
        check(unqualified.position == HALL && unqualified2.position == HALL, "Never assign an unqualified worker");

        var lowerLevel = worker(6,HALL,Map.of(HALL,3,KITCHEN,2));
        PositionAllocator.rebalance(List.of(lowerLevel,hallOnly),required(1,1));
        check(lowerLevel.position == HALL, "Do not reduce business-rule skill sums");

        var a = worker(7,HALL,Map.of(HALL,3,KITCHEN,3));
        var b = worker(8,HALL,Map.of(HALL,2,KITCHEN,3));
        var c = worker(9,HALL,Map.of(HALL,2));
        var d = worker(10,HALL,Map.of(HALL,1));
        PositionAllocator.rebalance(List.of(a,b,c,d),required(2,2));
        check(a.position == KITCHEN && b.position == KITCHEN, "Weekend two-person kitchen shortage filled");
        check(c.position == HALL && d.position == HALL, "Weekend hall minimum preserved");

        var extraKitchen = worker(11,KITCHEN,Map.of(HALL,3,KITCHEN,3));
        var kitchenOnly = worker(12,KITCHEN,Map.of(KITCHEN,3));
        PositionAllocator.rebalance(List.of(extraKitchen,kitchenOnly),required(1,1));
        check(extraKitchen.position == HALL && kitchenOnly.position == KITCHEN, "Hall shortages can also be repaired");
        var noRequirements = worker(13,HALL,Map.of(HALL,3,KITCHEN,3));
        PositionAllocator.rebalance(List.of(noRequirements),Map.of());
        check(noRequirements.position == HALL, "Unconfigured slots stay unchanged");
        System.out.println("PASS: " + checks + " position allocation checks (no database)");
    }
}
