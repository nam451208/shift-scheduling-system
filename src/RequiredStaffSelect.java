import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class RequiredStaffSelect {

    public static void main(String[] args) {

        String sql =
            "SELECT rs.required_staff_id, rs.day_type, rs.time_slot, " +
            "p.position_name, rs.required_count " +
            "FROM required_staff rs " +
            "JOIN positions p ON rs.position_id = p.position_id " +
            "ORDER BY rs.day_type, rs.time_slot, rs.position_id";

        try {
            Connection conn = DBConnection.getConnection();

            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql);

            System.out.println("必要人数一覧");
            System.out.println("--------------------------------");

            while (rs.next()) {
                System.out.println(
                    rs.getInt("required_staff_id") + " "
                    + rs.getString("day_type") + " "
                    + rs.getString("time_slot") + " "
                    + rs.getString("position_name") + " "
                    + rs.getInt("required_count") + "人"
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