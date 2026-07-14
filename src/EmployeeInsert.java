import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Scanner;

public class EmployeeInsert {

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        System.out.print("従業員名を入力してください：");
        String name = scanner.nextLine();

        System.out.println("従業員区分を選択してください。");
        System.out.println("1. アルバイト");
        System.out.println("2. 社員");
        System.out.print("番号を入力してください：");

        int typeNumber = scanner.nextInt();

        String employeeType;

        if (typeNumber == 1) {
            employeeType = "PART_TIME";
        } else if (typeNumber == 2) {
            employeeType = "FULL_TIME";
        } else {
            System.out.println("入力が正しくありません。");
            return;
        }

        String sql =
            "INSERT INTO employees " +
            "(name, employee_type, is_active) " +
            "VALUES (?, ?, true)";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setString(1, name);
            statement.setString(2, employeeType);

            statement.executeUpdate();

            System.out.println("従業員を登録しました。");

        } catch (Exception e) {
            System.out.println("従業員登録中にエラーが発生しました。");
            System.out.println(e.getMessage());
        }
    }
}