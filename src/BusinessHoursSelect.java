import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class BusinessHoursSelect {

    public static void main(String[] args) {

        try {
            Connection conn = DBConnection.getConnection();

            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM business_hours");

            System.out.println("営業時間一覧");
            System.out.println("------------------------------");

            while (rs.next()) {
                System.out.println(
                    rs.getInt("business_hour_id") + " "
                    + rs.getString("day_type") + " "
                    + rs.getString("open_time") + " ～ "
                    + rs.getString("close_time")
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