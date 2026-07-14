import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class EmployeePositionSelect {

    public static void main(String[] args) {

        String sql =
            "SELECT e.employee_id, e.name, p.position_name, ep.position_level " +
            "FROM employee_position ep " +
            "JOIN employees e ON ep.employee_id = e.employee_id " +
            "JOIN positions p ON ep.position_id = p.position_id " +
            "ORDER BY e.employee_id, p.position_id";

        try {
           Connection conn = DBConnection.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);

            System.out.println("===== 従業員ポジション一覧 =====");

            while (rs.next()) {
                System.out.println(
                    "ID:" + rs.getInt("employee_id")
                    + " 名前:" + rs.getString("name")
                    + " ポジション:" + rs.getString("position_name")
                    + " Lv:" + rs.getInt("position_level")
                );
            }

            rs.close();
            stmt.close();
            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}