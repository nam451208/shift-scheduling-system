import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class EmployeeSelect {

    public static void main(String[] args) {

        String sql =
            "SELECT employee_id, name, employment_type " +
            "FROM employees " +
            "WHERE is_active = true " +
            "ORDER BY employee_id";

        try {
            Connection conn = DBConnection.getConnection();

            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);

            System.out.println("========== 従業員一覧 ==========");
            System.out.printf("%-5s %-10s %-10s%n", "ID", "名前", "雇用区分");
            System.out.println("--------------------------------");

            while (rs.next()) {
                System.out.printf("%-5d %-10s %-10s%n",
                        rs.getInt("employee_id"),
                        rs.getString("name"),
                        rs.getString("employment_type"));
            }

            rs.close();
            stmt.close();
            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}