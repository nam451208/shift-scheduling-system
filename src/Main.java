import java.util.Scanner;

public class Main {

    public static void main(String[] args) {

        Scanner sc = new Scanner(System.in);

        while (true) {

            System.out.println();
            System.out.println("=================================");
            System.out.println("   シフト自動作成システム");
            System.out.println("=================================");
            System.out.println("1.従業員一覧");
            System.out.println("2.従業員追加");
            System.out.println("3.従業員削除");
            System.out.println("4.従業員情報更新");
            System.out.println("5.ポジション一覧");
            System.out.println("6.従業員ポジション一覧");
            System.out.println("7.従業員ポジション更新");
            System.out.println("8.従業員ポジション登録");
            System.out.println("9.営業時間一覧");
            System.out.println("10.必要人数一覧");
            System.out.println("11.希望シフト一覧");
            System.out.println("12.希望シフト登録");
            System.out.println("13.希望シフト更新");
            System.out.println("14.シフト結果一覧");
            System.out.println("15.シフト自動生成");
            System.out.println("16.社員休み登録");
            System.out.println("0.終了");
            System.out.print("番号を入力してください：");

            String menuText = sc.nextLine();

            int menu;

            try {
                menu = Integer.parseInt(menuText);
            } catch (NumberFormatException e) {
                System.out.println("数字を入力してください。");
                continue;
            }

            switch (menu) {

                case 1:
                    EmployeeSelect.main(null);
                    break;

                case 2:
                    EmployeeInsert.main(null);
                    break;

                case 3:
                    EmployeeDelete.main(null);
                    break;

                case 4:
                    EmployeeUpdate.main(null);
                    break;

                case 5:
                    PositionSelect.main(null);
                    break;

                case 6:
                    EmployeePositionSelect.main(null);
                    break;

                case 7:
                    EmployeePositionUpdate.main(null);
                    break;

                case 8:
                    EmployeePositionInsert.main(null);
                    break;

                case 9:
                    BusinessHoursSelect.main(null);
                    break;

                case 10:
                    RequiredStaffSelect.main(null);
                    break;

                case 11:
                    RequestShiftSelect.main(null);
                    break;

                case 12:
                    RequestShiftInsert.main(null);
                    break;

                case 13:
                    RequestShiftUpdate.main(null);
                    break;

                case 14:
                    WorkShiftSelect.main(null);
                    break;

                case 15:
                    ShiftGenerator.main(null);
                    break;

                case 16:
                    EmployeeDayOffInsert.insertDayOff(sc);
                    break;

                case 0:
                    System.out.println("終了します。");
                    return;

                default:
                    System.out.println("入力が正しくありません。");
            }
        }
    }
}