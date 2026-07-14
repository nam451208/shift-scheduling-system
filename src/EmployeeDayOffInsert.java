import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Scanner;

public class EmployeeDayOffInsert {

    public static void insertDayOff(Scanner scanner) {

        scanner.nextLine();

        System.out.print("社員名を入力してください：");
        String employeeName = scanner.nextLine();

        Integer employeeId = findFullTimeEmployeeId(employeeName);

        if (employeeId == null) {
            System.out.println("該当する社員が見つかりません。");
            return;
        }

        System.out.print("休みの日を入力してください（例 2026-07-15）：");
        String dateText = scanner.nextLine();

        LocalDate offDate;

        try {
            offDate = LocalDate.parse(dateText);
        } catch (DateTimeParseException e) {
            System.out.println("日付の形式が正しくありません。");
            System.out.println("例：2026-07-15");
            return;
        }

        System.out.println("休みの種類を選択してください");
        System.out.println("1. 終日休み");
        System.out.println("2. 時間指定休み");
        System.out.print("番号を入力してください：");

        String typeText = scanner.nextLine();

        LocalTime startTime = null;
        LocalTime endTime = null;

        if ("2".equals(typeText)) {

            System.out.print("休み開始時間を入力してください（例 10:00）：");
            String startText = scanner.nextLine();

            System.out.print("休み終了時間を入力してください（例 15:00）：");
            String endText = scanner.nextLine();

            try {
                startTime = LocalTime.parse(startText);
                endTime = LocalTime.parse(endText);
            } catch (DateTimeParseException e) {
                System.out.println("時間の形式が正しくありません。");
                System.out.println("例：10:00");
                return;
            }

            if (!startTime.isBefore(endTime)) {
                System.out.println("終了時間は開始時間より後にしてください。");
                return;
            }
        }

        System.out.print("理由を入力してください（省略可）：");
        String reason = scanner.nextLine();

        String sql =
            "INSERT INTO employee_day_off " +
            "(employee_id, off_date, start_time, end_time, reason) " +
            "VALUES (?, ?, ?, ?, ?)";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setInt(1, employeeId);
            statement.setDate(2, Date.valueOf(offDate));

            if (startTime == null) {
                statement.setNull(3, java.sql.Types.TIME);
                statement.setNull(4, java.sql.Types.TIME);
            } else {
                statement.setTime(3, Time.valueOf(startTime));
                statement.setTime(4, Time.valueOf(endTime));
            }

            if (reason.isBlank()) {
                statement.setNull(5, java.sql.Types.VARCHAR);
            } else {
                statement.setString(5, reason);
            }

            statement.executeUpdate();

            if (startTime == null) {
                System.out.println(employeeName + "さんの終日休みを登録しました。");
            } else {
                System.out.println(
                    employeeName + "さんの休みを登録しました。" +
                    startTime + "〜" + endTime
                );
            }

        } catch (Exception e) {

            if (e instanceof SQLException &&
                ((SQLException) e).getErrorCode() == 1062) {

                System.out.println("その日付の休みはすでに登録されています。");

            } else {
                System.out.println("休みの登録中にエラーが発生しました。");
                System.out.println(e.getMessage());
            }
        }
    }

    private static Integer findFullTimeEmployeeId(String employeeName) {

        String sql =
            "SELECT employee_id " +
            "FROM employees " +
            "WHERE name = ? " +
            "AND employment_type = 'FULL_TIME' " +
            "AND is_active = true";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setString(1, employeeName);

            try (ResultSet resultSet = statement.executeQuery()) {

                if (resultSet.next()) {
                    return resultSet.getInt("employee_id");
                }
            }

        } catch (Exception e) {
            System.out.println("社員情報の取得中にエラーが発生しました。");
            System.out.println(e.getMessage());
        }

        return null;
    }
}