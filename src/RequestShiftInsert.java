import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Scanner;

public class RequestShiftInsert {

    public static void main(String[] args) {

        Scanner sc = new Scanner(System.in);

        try {
            Connection conn = DBConnection.getConnection();

            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(
                "SELECT employee_id, name FROM employees WHERE is_active = true ORDER BY employee_id"
            );

            System.out.println();
            System.out.println("========== 希望シフト登録 ==========");
            System.out.println();
            System.out.println("従業員一覧");
            System.out.println("--------------------------------");
            System.out.printf("%-4s %-10s%n", "ID", "名前");
            System.out.println("--------------------------------");

            while (rs.next()) {
                System.out.printf(
                    "%-4d %-10s%n",
                    rs.getInt("employee_id"),
                    rs.getString("name")
                );
            }

            System.out.println("--------------------------------");

            rs.close();
            stmt.close();

            System.out.print("従業員IDを選択してください: ");
            int employeeId = sc.nextInt();
            sc.nextLine();

            System.out.print("何日分入力しますか: ");
            int count = sc.nextInt();
            sc.nextLine();

            String sql =
                "INSERT INTO request_shift " +
                "(employee_id, work_date, start_time, end_time, is_submitted) " +
                "VALUES (?, ?, ?, ?, ?)";

            PreparedStatement pstmt = conn.prepareStatement(sql);

            for (int i = 1; i <= count; i++) {

                System.out.println();
                System.out.println("========== " + i + "日目 ==========");

                System.out.print("勤務日（yyyy-MM-dd）: ");
                String workDate = sc.nextLine();

                System.out.print("開始時間（HH:mm）: ");
                String startTime = sc.nextLine();

                System.out.print("終了時間（HH:mm）: ");
                String endTime = sc.nextLine();

                pstmt.setInt(1, employeeId);
                pstmt.setString(2, workDate);
                pstmt.setString(3, startTime);
                pstmt.setString(4, endTime);
                pstmt.setBoolean(5, true);

                pstmt.executeUpdate();

                System.out.println("登録しました。");
            }

            pstmt.close();
            conn.close();
            sc.close();

            System.out.println();
            System.out.println("==================================");
            System.out.println("希望シフトをまとめて登録しました。");
            System.out.println("==================================");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}