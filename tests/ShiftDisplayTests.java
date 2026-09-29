import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;

/** Isolated JDBC fixtures. Run only with DB_URL=jdbc:display-test and a dummy DB_PASSWORD. */
public class ShiftDisplayTests {
    private static List<String[]> rows = List.of();
    private static int index;
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static Object proxy(Class<?> type) {
        return Proxy.newProxyInstance(ShiftDisplayTests.class.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> {
            switch (m.getName()) {
                case "close": return null;
                case "prepareStatement":
                    check(((String)a[0]).contains("ORDER BY ws.work_date, ws.employee_id, ws.start_time"), "Rows requested in chronological order");
                    return proxy(PreparedStatement.class);
                case "executeQuery": index = -1; return proxy(ResultSet.class);
                case "next": return ++index < rows.size();
                case "getDate": return java.sql.Date.valueOf("2026-10-01");
                case "getInt": return Integer.parseInt(rows.get(index)[0]);
                case "getString": return "Sample employee " + rows.get(index)[0];
                case "getTime": return Time.valueOf(rows.get(index)[a[0].equals("start_time") ? 1 : 2] + ":00");
                default: throw new AssertionError("Unexpected JDBC call " + m.getName());
            }
        });
    }
    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = WebServer.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null,args);
    }
    private static List<?> blocks(List<String[]> fixture) throws Exception {
        rows = fixture;
        return (List<?>)invoke("getShiftBlocks", new Class<?>[]{});
    }
    private static String render(List<?> blocks) throws Exception {
        return (String)invoke("renderShiftDay",new Class<?>[]{LocalDate.class,List.class},LocalDate.of(2026,10,1),blocks);
    }
    public static void main(String[] args) throws Exception {
        if (!"jdbc:display-test".equals(System.getenv("DB_URL"))) throw new IllegalStateException("Use isolated fixture URL");
        DriverManager.registerDriver(new Driver() {
            public boolean acceptsURL(String url) { return "jdbc:display-test".equals(url); }
            public Connection connect(String url, Properties props) { return acceptsURL(url) ? (Connection)proxy(Connection.class) : null; }
            public DriverPropertyInfo[] getPropertyInfo(String url,Properties props) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion() { return 1; }
            public int getMinorVersion() { return 0; }
            public boolean jdbcCompliant() { return false; }
            public Logger getParentLogger() { return Logger.getGlobal(); }
        });
        List<String[]> continuous = new ArrayList<>();
        for (LocalTime t=LocalTime.of(11,30);t.isBefore(LocalTime.of(22,0));t=t.plusMinutes(30)) {
            continuous.add(new String[]{"1",t.toString(),t.plusMinutes(30).toString()});
        }
        List<?> normal = blocks(continuous);
        check(normal.size()==1,"Normal consecutive half-hour data becomes one block");
        String normalHtml = render(normal);
        check(normalHtml.contains("11:30-22"),"Normal block has correct full time range");
        List<?> breaks = blocks(List.of(new String[]{"1","11:30","13:00"},new String[]{"1","14:00","17:00"},new String[]{"1","18:00","22:00"}));
        check(breaks.size()==3,"Breaks remain visible");
        String breakHtml = render(breaks);
        List<?> overlap = blocks(List.of(new String[]{"1","11:30","12:00"},new String[]{"1","11:30","13:00"},new String[]{"1","12:30","13:30"},new String[]{"1","13:00","14:00"},new String[]{"1","13:30","14:30"}));
        check(overlap.size()==5,"Overlapping display intervals are separate blocks");
        String overlapHtml = render(overlap);
        // The renderer should be examined separately from SQL ordering.
        List<Object> reversed = new ArrayList<>(breaks);
        Collections.reverse(reversed);
        String reversedHtml = render(reversed);
        check(reversedHtml.indexOf("11:30-13") < reversedHtml.indexOf("14-17")
            && reversedHtml.indexOf("14-17") < reversedHtml.indexOf("18-22"),
            "Renderer sorts valid intervals even when supplied out of order");
        check(reversed.get(0) == breaks.get(2), "Rendering does not mutate caller's block order");
        String body = "<h1>Display investigation (synthetic data)</h1>"
            + "<h2>Normal consecutive slots</h2><div id='normal'>"+normalHtml+"</div>"
            + "<h2>Normal intervals with breaks</h2><div id='breaks'>"+breakHtml+"</div>"
            + "<h2>Overlapping intervals (comparison only)</h2><div id='overlap'>"+overlapHtml+"</div>"
            + "<h2>Non-overlapping intervals passed out of order</h2><div id='reversed'>"+reversedHtml+"</div>"
            + "<pre id='browser-result'></pre><script>"
            + "window.addEventListener('load',()=>{const result={};for(const id of ['normal','breaks','overlap','reversed']){"
            + "const tops=Array.from(document.querySelectorAll('#'+id+' .shift-bar')).map(el=>el.getBoundingClientRect().top);"
            + "result[id]={bars:tops.length,rows:new Set(tops).size};}"
            + "result.pass=result.normal.rows===1&&result.breaks.rows===1&&result.reversed.rows===1&&result.overlap.rows===5;"
            + "document.getElementById('browser-result').textContent=JSON.stringify(result);});</script>";
        String html = (String)invoke("renderLayout",new Class<?>[]{String.class,String.class},"Display investigation",body);
        Files.writeString(Path.of("ShiftSystem/bin/display-investigation.html"),html);
        System.out.println("PASS: " + checks + " display checks. Browser fixture: ShiftSystem/bin/display-investigation.html");
    }
}
