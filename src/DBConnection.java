import java.sql.Connection;
import java.sql.DriverManager;

public class DBConnection {

    private static final String URL = getEnvOrDefault(
        "DB_URL",
        "jdbc:mysql://localhost:3306/shift_system"
    );
    private static final String USER = getEnvOrDefault("DB_USER", "root");

    public static Connection getConnection() throws Exception {
        String password = System.getenv("DB_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                "環境変数 DB_PASSWORD が設定されていません。"
            );
        }

        return DriverManager.getConnection(URL, USER, password);
    }

    private static String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
