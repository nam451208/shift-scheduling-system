import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class RequestShiftSelect {

    public static void main(String[] args) {

        String sql =
            "SELECT r.request_id, r.employee_id, e.name, " +
            "r.work_date, r.start_time, r.end_time, r.is_submitted " +
            "FROM request_shift r " +
            "JOIN employees e ON r.employee_id = e.employee_id " +
            "ORDER BY r.work_date, r.start_time, r.employee_id";

        try {
            Connection conn = DBConnection.getConnection();

            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);

            System.out.println("========== 希望シフト一覧 ==========");
            System.out.printf("%-4s %-10s %-12s %-8s %-8s%n",
                    "ID", "名前", "日付", "開始", "終了");
            System.out.println("-----------------------------------------------");

            while (rs.next()) {
                System.out.printf("%-4d %-10s %-12s %-8s %-8s%n",
                        rs.getInt("request_id"),
                        rs.getString("name"),
                        rs.getDate("work_date"),
                        rs.getString("start_time").substring(0, 5),
                        rs.getString("end_time").substring(0, 5));
            }

            rs.close();
            stmt.close();
            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}