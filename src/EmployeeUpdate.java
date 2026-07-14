import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Scanner;

public class EmployeeUpdate {

    public static void main(String[] args) {

        Scanner sc = new Scanner(System.in);

        System.out.print("更新する従業員IDを入力してください: ");
        int employeeId = sc.nextInt();
        sc.nextLine();

        System.out.print("新しい名前を入力してください: ");
        String name = sc.nextLine();

        System.out.println("新しい雇用区分を選択してください");
        System.out.println("1. 社員");
        System.out.println("2. アルバイト");
        System.out.println("3. 新人");
        System.out.print("番号を入力してください: ");

        int type = sc.nextInt();

        String employmentType;

        switch (type) {
            case 1:
                employmentType = "社員";
                break;
            case 2:
                employmentType = "アルバイト";
                break;
            case 3:
                employmentType = "新人";
                break;
            default:
                System.out.println("入力が正しくありません。");
                sc.close();
                return;
        }

        String sql = "UPDATE employees SET name = ?, employment_type = ? WHERE employee_id = ?";

        try {
            Connection conn = DBConnection.getConnection();

            PreparedStatement pstmt = conn.prepareStatement(sql);

            pstmt.setString(1, name);
            pstmt.setString(2, employmentType);
            pstmt.setInt(3, employeeId);

            int result = pstmt.executeUpdate();

            if (result > 0) {
                System.out.println("従業員情報を更新しました。");
            } else {
                System.out.println("指定したIDの従業員は存在しません。");
            }

            pstmt.close();
            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}