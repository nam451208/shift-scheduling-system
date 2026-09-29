import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Run with: javac -encoding UTF-8 -cp "lib/*" -d bin src/*.java tests/RegressionTests.java
 *           java -cp "bin;lib/*" RegressionTests
 * Uses no database or external services.
 */
public class RegressionTests {
    private static int checks;
    private static final Class<?> CANDIDATE;
    private static final Class<?> RANGE;
    static {
        try {
            CANDIDATE = Class.forName("ShiftGenerator$Candidate");
            RANGE = Class.forName("WebServer$DateRange");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static Object invoke(Class<?> type, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = type.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception)e.getCause();
            throw e;
        }
    }

    private static void field(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Object candidate(int assigned, int requested, String end) throws Exception {
        Constructor<?> constructor = CANDIDATE.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object value = constructor.newInstance();
        field(value, "employmentType", "NEWBIE");
        field(value, "employeeId", 1);
        field(value, "positionId", 2);
        field(value, "dailyWorkCount", assigned);
        field(value, "requestSlots", requested);
        field(value, "requestEndTime", LocalTime.parse(end));
        return value;
    }

    private static boolean eligible(int assigned, int requested, String start, String end) throws Exception {
        LocalTime time = LocalTime.parse(start);
        return (boolean)invoke(ShiftGenerator.class, "canAssignNewbie",
            new Class<?>[]{CANDIDATE, LocalTime.class, LocalTime.class},
            candidate(assigned, requested, end), time, time.plusMinutes(30));
    }

    private static Object range(String start, String end) throws Exception {
        Constructor<?> constructor = RANGE.getDeclaredConstructor(LocalDate.class, LocalDate.class);
        constructor.setAccessible(true);
        return constructor.newInstance(LocalDate.parse(start), LocalDate.parse(end));
    }

    private static String dateField(Object range, String name) throws Exception {
        Field field = RANGE.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(range).toString();
    }

    private static final class FakeDatabase implements InvocationHandler {
        boolean autoCommit = true, committed, rolledBack;
        int inserts, failAt, conflicts;
        final List<LocalTime> starts = new ArrayList<>();
        final List<LocalTime> ends = new ArrayList<>();
        final Map<Integer,Object> parameters = new HashMap<>();
        Connection connection() {
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, this);
        }
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getAutoCommit": return autoCommit;
                case "setAutoCommit": autoCommit = (boolean)args[0]; return null;
                case "commit": committed = true; return null;
                case "rollback": rolledBack = true; return null;
                case "close": return null;
                case "setInt": case "setDate": case "setTime":
                    parameters.put((int)args[0], args[1]); return null;
                case "prepareStatement":
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{PreparedStatement.class}, this);
                case "executeUpdate":
                    inserts++;
                    if (failAt == inserts) throw new SQLException("Simulated insert failure");
                    starts.add(((Time)parameters.get(3)).toLocalTime());
                    ends.add(((Time)parameters.get(4)).toLocalTime());
                    return 1;
                case "executeQuery":
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{ResultSet.class}, this);
                case "next": return true;
                case "getInt": return conflicts;
                default: throw new AssertionError("Unexpected JDBC operation: " + method.getName());
            }
        }
    }

    private static void insert(FakeDatabase db, Object candidate) throws Exception {
        invoke(ShiftGenerator.class, "insertCandidate",
            new Class<?>[]{Connection.class,CANDIDATE,LocalDate.class,LocalTime.class,LocalTime.class},
            db.connection(),candidate,LocalDate.of(2026,10,1),LocalTime.of(18,0),LocalTime.of(18,30));
    }

    public static void main(String[] args) throws Exception {
        check(eligible(0, 6, "10:00", "13:00"), "Exactly three hours must qualify");
        check(eligible(0, 24, "18:00", "22:00"), "18:00 to 21:00 must qualify");
        check(!eligible(0, 24, "18:30", "22:00"), "Must finish by 21:00");
        check(!eligible(0, 5, "10:00", "12:30"), "Short requests must remain unassigned");
        check(!eligible(6, 24, "13:00", "22:00"), "Cannot exceed three hours");
        check(!eligible(1, 24, "13:00", "22:00"), "Cannot start a second block");
        check(!eligible(0, 24, "09:30", "22:00"), "Cannot start before opening");
        check(!eligible(0, 24, "21:00", "22:00"), "Cannot start at cutoff");
        Object input = invoke(WebServer.class,"inputPeriod",new Class<?>[]{RANGE,LocalDate.class},
            range("2026-07-01","2026-07-15"),LocalDate.of(2026,9,28));
        check(dateField(input,"start").equals("2026-09-01"),"Stale dates reset to this month");
        check(dateField(input,"end").equals("2026-09-30"),"Default covers full month");
        input = invoke(WebServer.class,"inputPeriod",new Class<?>[]{RANGE,LocalDate.class},
            range("2026-10-01","2026-10-15"),LocalDate.of(2026,9,28));
        check(dateField(input,"start").equals("2026-10-01"),"Future selections are retained");
        Object leap = invoke(WebServer.class,"currentMonth",new Class<?>[]{LocalDate.class},LocalDate.of(2028,2,15));
        check(dateField(leap,"end").equals("2028-02-29"),"Leap year month end");
        Object december = invoke(WebServer.class,"currentMonth",new Class<?>[]{LocalDate.class},LocalDate.of(2026,12,31));
        check(dateField(december,"end").equals("2026-12-31"),"Year end");
        Object past = invoke(WebServer.class,"parseRequestPeriod",new Class<?>[]{Map.class},
            Map.of("period_start","2026-07-01","period_end","2026-07-31"));
        check(dateField(past,"start").equals("2026-07-01"),"Explicit historical browsing is allowed");
        try {
            invoke(WebServer.class,"parseRequestPeriod",new Class<?>[]{Map.class},
                Map.of("period_start","2026-07-01","period_end","2026-10-31"));
            throw new AssertionError("Oversized range accepted");
        } catch (IllegalArgumentException expected) { checks++; }

        FakeDatabase db = new FakeDatabase();
        insert(db,candidate(0,24,"22:00"));
        check(db.inserts == 6 && db.committed && db.autoCommit,"Reserve all six slots transactionally");
        check(db.starts.get(0).equals(LocalTime.of(18,0))
            && db.ends.get(5).equals(LocalTime.of(21,0)),"Reserved block lasts exactly three hours");
        for (int i=1;i<6;i++) check(db.starts.get(i).equals(db.ends.get(i-1)),"Slots must be consecutive");
        db = new FakeDatabase();
        db.failAt = 3;
        try { insert(db,candidate(0,24,"22:00")); throw new AssertionError("Failure ignored"); }
        catch (SQLException expected) { checks++; }
        check(db.rolledBack && !db.committed && db.autoCommit,"Failure rolls back the entire block");
        db = new FakeDatabase();
        Object regular = candidate(0,24,"22:00");
        field(regular,"employmentType","PART_TIME");
        insert(db,regular);
        check(db.inserts == 1 && !db.committed,"Other employees keep half-hour allocation");
        db = new FakeDatabase();
        boolean available = (boolean)invoke(ShiftGenerator.class,"isNewbieBlockAvailable",
            new Class<?>[]{Connection.class,int.class,LocalDate.class,LocalTime.class,LocalTime.class},
            db.connection(),1,LocalDate.of(2026,10,1),LocalTime.of(18,0),LocalTime.of(21,0));
        check(available,"Conflict-free block available");
        check(db.parameters.get(3).equals(Time.valueOf("21:00:00"))
            && db.parameters.get(4).equals(Time.valueOf("18:00:00")),"Check absences across the full block");
        db.conflicts = 1;
        available = (boolean)invoke(ShiftGenerator.class,"isNewbieBlockAvailable",
            new Class<?>[]{Connection.class,int.class,LocalDate.class,LocalTime.class,LocalTime.class},
            db.connection(),1,LocalDate.of(2026,10,1),LocalTime.of(18,0),LocalTime.of(21,0));
        check(!available,"Absence or existing shift prevents allocation");
        System.out.println("PASS: " + checks + " regression checks (no database changes)");
    }
}
