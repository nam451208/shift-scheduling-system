import java.sql.Connection;

public class DatabaseTest {
    public static void main(String[] args) {
        try (Connection conn = DBConnection.getConnection()) {
            System.out.println("MySQL接続成功");
        } catch (Exception e) {
            System.out.println("MySQL接続失敗");
            e.printStackTrace();
        }
    }
}
