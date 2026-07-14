import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Scanner;

public class EmployeePositionUpdate {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.print("更新する従業員名を入力してください: ");
        String name = scanner.nextLine();

        System.out.println("1. ホール");
        System.out.println("2. キッチン");
        System.out.print("更新するポジションIDを入力してください: ");
        int positionId = scanner.nextInt();

        System.out.print("新しいレベルを入力してください: ");
        int positionLevel = scanner.nextInt();

        String selectEmployeeSql =
            "SELECT employee_id FROM employees " +
            "WHERE name = ? AND is_active = true";

        String updateSql =
            "UPDATE employee_position " +
            "SET position_level = ? " +
            "WHERE employee_id = ? AND position_id = ?";

        try {
            Connection conn = DBConnection.getConnection();

            PreparedStatement selectStmt = conn.prepareStatement(selectEmployeeSql);
            selectStmt.setString(1, name);

            ResultSet rs = selectStmt.executeQuery();

            if (!rs.next()) {
                System.out.println("指定された名前の従業員が見つかりません。");
                rs.close();
                selectStmt.close();
                conn.close();
                return;
            }

            int employeeId = rs.getInt("employee_id");

            PreparedStatement updateStmt = conn.prepareStatement(updateSql);
            updateStmt.setInt(1, positionLevel);
            updateStmt.setInt(2, employeeId);
            updateStmt.setInt(3, positionId);

            int result = updateStmt.executeUpdate();

            if (result > 0) {
                System.out.println(name + "さんのポジションレベルを更新しました。");
            } else {
                System.out.println("指定した従業員・ポジションのデータは存在しません。先にポジション登録してください。");
            }

            rs.close();
            updateStmt.close();
            selectStmt.close();
            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}