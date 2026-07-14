import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Scanner;

public class EmployeePositionInsert {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.print("従業員名を入力してください: ");
        String name = scanner.nextLine();

        System.out.print("ホールレベルを入力してください（担当不可なら0）: ");
        int hallLevel = scanner.nextInt();

        System.out.print("キッチンレベルを入力してください（担当不可なら0）: ");
        int kitchenLevel = scanner.nextInt();

        String selectEmployeeSql =
            "SELECT employee_id FROM employees " +
            "WHERE name = ? AND is_active = true";

        String insertSql =
            "INSERT INTO employee_position " +
            "(employee_id, position_id, position_level) " +
            "VALUES (?, ?, ?)";

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

            int insertCount = 0;

            if (hallLevel > 0) {
                PreparedStatement insertStmt = conn.prepareStatement(insertSql);
                insertStmt.setInt(1, employeeId);
                insertStmt.setInt(2, 1);
                insertStmt.setInt(3, hallLevel);
                insertStmt.executeUpdate();
                insertStmt.close();
                insertCount++;
            }

            if (kitchenLevel > 0) {
                PreparedStatement insertStmt = conn.prepareStatement(insertSql);
                insertStmt.setInt(1, employeeId);
                insertStmt.setInt(2, 2);
                insertStmt.setInt(3, kitchenLevel);
                insertStmt.executeUpdate();
                insertStmt.close();
                insertCount++;
            }

            if (insertCount > 0) {
                System.out.println(name + "さんのポジションを登録しました。");
            } else {
                System.out.println("登録するポジションがありませんでした。");
            }

            rs.close();
            selectStmt.close();
            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}