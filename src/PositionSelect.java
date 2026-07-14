import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class PositionSelect {

    public static void main(String[] args) {

        String sql = "SELECT * FROM positions";

        try {
            Connection conn = DBConnection.getConnection();

            Statement stmt = conn.createStatement();

            ResultSet rs = stmt.executeQuery(sql);

            System.out.println("===== ポジション一覧 =====");

            while (rs.next()) {
                System.out.println(
                        rs.getInt("position_id") + " : "
                      + rs.getString("position_name"));
            }

            rs.close();
            stmt.close();
            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}