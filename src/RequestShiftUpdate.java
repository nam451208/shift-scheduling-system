import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Scanner;

public class RequestShiftUpdate {

    public static void main(String[] args) {

        RequestShiftSelect.main(null);

        Scanner sc = new Scanner(System.in);

        System.out.print("更新する希望シフトIDを入力してください: ");
        int requestId = sc.nextInt();
        sc.nextLine();

        System.out.print("新しい勤務日を入力してください（例: 2026-07-15）: ");
        String workDate = sc.nextLine();

        System.out.print("新しい開始時間を入力してください（例: 10:00:00）: ");
        String startTime = sc.nextLine();

        System.out.print("新しい終了時間を入力してください（例: 15:00:00）: ");
        String endTime = sc.nextLine();

        String sql =
            "UPDATE request_shift " +
            "SET work_date = ?, start_time = ?, end_time = ? " +
            "WHERE request_id = ?";

        try {
            Connection conn = DBConnection.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql);

            pstmt.setString(1, workDate);
            pstmt.setString(2, startTime);
            pstmt.setString(3, endTime);
            pstmt.setInt(4, requestId);

            int result = pstmt.executeUpdate();

            if (result > 0) {
                System.out.println("希望シフトを更新しました。");
            } else {
                System.out.println("該当する希望シフトがありません。");
            }

            pstmt.close();
            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}