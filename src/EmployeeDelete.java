import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Scanner;

public class EmployeeDelete {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.print("削除する従業員IDを入力してください: ");
        int employeeId = scanner.nextInt();

        String selectSql = "SELECT name FROM employees WHERE employee_id = ?";

        String[] deleteSqls = {
            "DELETE FROM work_shift WHERE employee_id = ?",
            "DELETE FROM request_shift WHERE employee_id = ?",
            "DELETE FROM shift_submission WHERE employee_id = ?",
            "DELETE FROM employee_position WHERE employee_id = ?",
            "DELETE FROM employees WHERE employee_id = ?"
        };

        try {
            Connection conn = DBConnection.getConnection();

            PreparedStatement selectStmt = conn.prepareStatement(selectSql);
            selectStmt.setInt(1, employeeId);
            ResultSet rs = selectStmt.executeQuery();

            if (!rs.next()) {
                System.out.println("指定された従業員IDは存在しません。");
                rs.close();
                selectStmt.close();
                conn.close();
                return;
            }

            String name = rs.getString("name");

            for (int i = 0; i < deleteSqls.length; i++) {
                try {
                    PreparedStatement stmt = conn.prepareStatement(deleteSqls[i]);
                    stmt.setInt(1, employeeId);
                    stmt.executeUpdate();
                    stmt.close();
                } catch (Exception e) {
                    // 存在しないテーブルがあっても止めない
                }
            }

            System.out.println(name + "さんを関連データごと削除しました。");

            rs.close();
            selectStmt.close();
            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}