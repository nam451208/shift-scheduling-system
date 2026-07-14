import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.LocalDate;

public class WorkShiftSelect {

    public static void main(String[] args) {
        selectWorkShift();
    }

    public static void selectWorkShift() {
        String sql =
            "SELECT " +
            "ws.work_date, " +
            "ws.start_time, " +
            "ws.end_time, " +
            "e.employee_id, " +
            "e.name " +
            "FROM work_shift ws " +
            "JOIN employees e ON ws.employee_id = e.employee_id " +
            "ORDER BY ws.work_date, e.employee_id, ws.start_time";

        try (
            Connection conn = DBConnection.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql);
            ResultSet rs = stmt.executeQuery()
        ) {
            java.sql.Date currentDate = null;

            int currentEmployeeId = -1;
            String currentName = null;
            java.sql.Time currentStartTime = null;
            java.sql.Time currentEndTime = null;

            while (rs.next()) {
                java.sql.Date workDate = rs.getDate("work_date");
                int employeeId = rs.getInt("employee_id");
                String name = rs.getString("name");
                java.sql.Time startTime = rs.getTime("start_time");
                java.sql.Time endTime = rs.getTime("end_time");

                if (currentDate == null || !workDate.equals(currentDate)) {
                    if (currentDate != null) {
                        printShift(currentName, currentStartTime, currentEndTime);
                    }

                    currentDate = workDate;
                    printDateHeader(workDate);

                    currentEmployeeId = employeeId;
                    currentName = name;
                    currentStartTime = startTime;
                    currentEndTime = endTime;
                    continue;
                }

                boolean canMerge =
                    employeeId == currentEmployeeId &&
                    startTime.equals(currentEndTime);

                if (canMerge) {
                    currentEndTime = endTime;
                } else {
                    printShift(currentName, currentStartTime, currentEndTime);

                    currentEmployeeId = employeeId;
                    currentName = name;
                    currentStartTime = startTime;
                    currentEndTime = endTime;
                }
            }

            if (currentDate != null) {
                printShift(currentName, currentStartTime, currentEndTime);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void printDateHeader(java.sql.Date date) {
        LocalDate localDate = date.toLocalDate();

        System.out.println();
        System.out.println(
            "【" +
            localDate +
            "（" + getJapaneseDayOfWeek(localDate) + "）】"
        );
    }

    public static void printShift(
            String name,
            java.sql.Time startTime,
            java.sql.Time endTime) {

        System.out.println(
            name + " " +
            formatTime(startTime) + "～" +
            formatTime(endTime)
        );
    }

    public static String formatTime(java.sql.Time time) {
        return time.toString().substring(0, 5);
    }

    public static String getJapaneseDayOfWeek(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();

        switch (day) {
            case MONDAY:
                return "月";
            case TUESDAY:
                return "火";
            case WEDNESDAY:
                return "水";
            case THURSDAY:
                return "木";
            case FRIDAY:
                return "金";
            case SATURDAY:
                return "土";
            case SUNDAY:
                return "日";
            default:
                return "";
        }
    }
}