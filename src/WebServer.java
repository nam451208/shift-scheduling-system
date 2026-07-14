import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class WebServer {

    private static final int PORT = getPort();
    private static final LocalTime OPEN_TIME = LocalTime.of(10, 0);
    private static final LocalTime CLOSE_TIME = LocalTime.of(22, 0);
    private static final String SESSION_COOKIE = "shift_session";
    private static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int PASSWORD_KEY_LENGTH = 256;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, String> LOGIN_USERS = new LinkedHashMap<>();
    private static final ThreadLocal<String> CURRENT_USERNAME = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        validateLoginSettings();
        initializeLoginUsers();
        initializePerformanceIndexes();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", exchange -> {
            try {
                handle(exchange);
            } catch (Exception e) {
                e.printStackTrace();

                try {
                    send(exchange,
                        renderLayout("エラー",
                            "<h1>エラー</h1><p>" + escape(e.getMessage()) + "</p><pre>" + escape(stackTraceToText(e)) + "</pre>"
                        )
                    );
                } catch (Exception sendError) {
                    sendError.printStackTrace();
                }
            } finally {
                CURRENT_USERNAME.remove();
            }
        });

        server.setExecutor(null);
        server.start();

        System.out.println("WebServer started.");
        System.out.println("http://localhost:" + PORT + "/");
    }

    private static int getPort() {
        String value = System.getenv("PORT");

        if (value == null || value.isBlank()) {
            return 8080;
        }

        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("PORTは1から65535で指定してください。");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PORTは数字で指定してください。", e);
        }
    }

    private static void validateLoginSettings() {
        LOGIN_USERS.clear();

        for (int number = 1; number <= 2; number++) {
            String username = System.getenv("APP_USERNAME_" + number);
            String password = System.getenv("APP_PASSWORD_" + number);

            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                throw new IllegalStateException(
                    "APP_USERNAME_" + number + " と APP_PASSWORD_" + number + " を設定してください。"
                );
            }

            username = username.trim();
            if (LOGIN_USERS.containsKey(username)) {
                throw new IllegalStateException("2人のログイン名は別々にしてください。");
            }
            if (password.length() < 8) {
                throw new IllegalStateException("ログインパスワードは8文字以上にしてください。");
            }

            LOGIN_USERS.put(username, password);
        }
    }

    private static void initializeLoginUsers() throws Exception {
        String createSql =
            "CREATE TABLE IF NOT EXISTS app_users (" +
            "username VARCHAR(100) PRIMARY KEY, " +
            "password_hash VARCHAR(500) NOT NULL, " +
            "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP " +
            "ON UPDATE CURRENT_TIMESTAMP)";
        String createSettingsSql =
            "CREATE TABLE IF NOT EXISTS app_settings (" +
            "setting_key VARCHAR(100) PRIMARY KEY, " +
            "setting_value VARCHAR(500) NOT NULL)";
        String insertSql =
            "INSERT IGNORE INTO app_users (username, password_hash) VALUES (?, ?)";

        try (Connection connection = DBConnection.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(createSql)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(createSettingsSql)) {
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                for (Map.Entry<String, String> user : LOGIN_USERS.entrySet()) {
                    statement.setString(1, user.getKey());
                    statement.setString(2, hashPassword(user.getValue()));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }

        LOGIN_USERS.clear();
    }

    private static void initializePerformanceIndexes() throws Exception {
        String[][] indexes = {
            {
                "request_shift", "idx_request_shift_date_status_times",
                "work_date, is_submitted, start_time, end_time, employee_id"
            },
            {
                "request_shift", "idx_request_shift_employee_period",
                "employee_id, work_date, is_submitted"
            },
            {
                "work_shift", "idx_work_shift_employee_date_times",
                "employee_id, work_date, start_time, end_time, position_id"
            },
            {
                "work_shift", "idx_work_shift_date_position_time",
                "work_date, position_id, start_time, employee_id"
            },
            {
                "required_staff", "idx_required_staff_type_time_position",
                "day_type, time_slot, position_id"
            },
            {
                "employee_day_off", "idx_day_off_employee_date_times",
                "employee_id, off_date, start_time, end_time"
            }
        };

        String existsSql =
            "SELECT COUNT(*) AS index_count FROM information_schema.statistics " +
            "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?";

        try (Connection connection = DBConnection.getConnection()) {
            for (String[] index : indexes) {
                boolean exists;
                try (PreparedStatement statement = connection.prepareStatement(existsSql)) {
                    statement.setString(1, index[0]);
                    statement.setString(2, index[1]);
                    try (ResultSet rs = statement.executeQuery()) {
                        rs.next();
                        exists = rs.getInt("index_count") > 0;
                    }
                }

                if (!exists) {
                    String createSql = "CREATE INDEX " + index[1] + " ON "
                        + index[0] + " (" + index[2] + ")";
                    try (PreparedStatement statement = connection.prepareStatement(createSql)) {
                        statement.executeUpdate();
                    }
                    System.out.println("Created database index: " + index[1]);
                }
            }
        }
    }

    private static void handleLogin(HttpExchange exchange) throws Exception {
        if (getSession(exchange) != null) {
            redirect(exchange, "/");
            return;
        }

        if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, renderLoginPage(false));
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendMethodNotAllowed(exchange);
            return;
        }

        Map<String, String> params = getParams(exchange);
        String username = params.getOrDefault("username", "").trim();
        String password = params.getOrDefault("password", "");
        if (!authenticate(username, password)) {
            send(exchange, renderLoginPage(true));
            return;
        }

        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        SESSIONS.put(token, new Session(username, Instant.now().plus(SESSION_LIFETIME)));

        String cookie = SESSION_COOKIE + "=" + token
            + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + SESSION_LIFETIME.toSeconds();
        if (isHttps(exchange)) {
            cookie += "; Secure";
        }
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
        redirect(exchange, "/");
    }

    private static void handleLogout(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            redirect(exchange, "/login");
            return;
        }

        String token = getCookie(exchange, SESSION_COOKIE);
        if (token != null) {
            SESSIONS.remove(token);
        }

        String cookie = SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0";
        if (isHttps(exchange)) {
            cookie += "; Secure";
        }
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
        redirect(exchange, "/login");
    }

    private static boolean authenticate(String username, String password) throws Exception {
        String sql = "SELECT password_hash FROM app_users WHERE username = ?";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, username);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && verifyPassword(password, rs.getString("password_hash"));
            }
        }
    }

    private static String hashPassword(String password) throws Exception {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = derivePassword(password, salt, PASSWORD_ITERATIONS);
        return PASSWORD_ITERATIONS + ":"
            + Base64.getEncoder().encodeToString(salt) + ":"
            + Base64.getEncoder().encodeToString(hash);
    }

    private static boolean verifyPassword(String password, String storedHash) throws Exception {
        if (storedHash == null) {
            return false;
        }

        String[] parts = storedHash.split(":", 3);
        if (parts.length != 3) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = derivePassword(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derivePassword(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, PASSWORD_KEY_LENGTH);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static Session getSession(HttpExchange exchange) {
        String token = getCookie(exchange, SESSION_COOKIE);
        if (token == null) {
            return null;
        }

        Session session = SESSIONS.get(token);
        if (session == null) {
            return null;
        }
        if (session.expiresAt.isBefore(Instant.now())) {
            SESSIONS.remove(token);
            return null;
        }

        CURRENT_USERNAME.set(session.username);
        return session;
    }

    private static String getCookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }

        for (String header : headers) {
            for (String part : header.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    private static boolean isHttps(HttpExchange exchange) {
        String forwardedProto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(forwardedProto);
    }

    private static void sendMethodNotAllowed(HttpExchange exchange) throws Exception {
        byte[] bytes = "Method Not Allowed".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(405, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static String renderLoginPage(boolean failed) {
        String message = failed
            ? "<p class='error'>ログイン名またはパスワードが違います。</p>"
            : "<p>登録されたアカウントでログインしてください。</p>";

        return "<!DOCTYPE html><html lang='ja'><head>"
            + "<meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
            + "<title>ログイン</title>"
            + "<style>"
            + "*{box-sizing:border-box;}body{margin:0;background:#f4f7fb;color:#071421;font-family:Arial,'Yu Gothic',Meiryo,sans-serif;}"
            + ".login-page{min-height:100vh;display:grid;place-items:center;padding:20px;}"
            + ".login-card{width:min(420px,100%);background:#fff;border:1px solid #d5e0ea;border-radius:10px;padding:28px;}"
            + "h1{margin:0 0 12px;}p{color:#52606d;line-height:1.5;}.error{color:#b42318;font-weight:700;}"
            + "label{display:flex;flex-direction:column;gap:8px;margin:0 0 18px;font-weight:700;}"
            + "input{height:46px;padding:0 12px;border:1px solid #cbd6e2;border-radius:6px;font-size:16px;}"
            + "button{width:100%;height:48px;border:0;border-radius:6px;background:#247bd1;color:#fff;font-size:17px;font-weight:700;cursor:pointer;}"
            + "</style></head><body><main class='login-page'><section class='login-card'>"
            + "<h1>シフト管理 ログイン</h1>" + message
            + "<form method='post' action='/login'>"
            + "<label>ログイン名<input name='username' autocomplete='username' required autofocus></label>"
            + "<label>パスワード<input type='password' name='password' autocomplete='current-password' required></label>"
            + "<button type='submit'>ログイン</button>"
            + "</form></section></main></body></html>";
    }

    private static void handle(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/login")) {
            handleLogin(exchange);
            return;
        }

        if (path.equals("/logout")) {
            handleLogout(exchange);
            return;
        }

        Session session = getSession(exchange);
        if (session == null) {
            redirect(exchange, "/login");
            return;
        }

        if (path.equals("/password")) {
            send(exchange, renderLayout("パスワード変更", renderPasswordForm(exchange)));
            return;
        }

        if (path.equals("/password-save")) {
            savePassword(exchange, session);
            return;
        }

        if (path.equals("/")) {
            send(exchange, renderLayout("ホーム", renderHome()));
            return;
        }

        if (path.equals("/generate")) {
            send(exchange, renderLayout("シフト自動生成", renderGenerate(exchange)));
            return;
        }

        if (path.equals("/generate-run")) {
            runGenerate(exchange);
            return;
        }

        if (path.equals("/shifts")) {
            send(exchange, renderLayout("シフト結果", renderShifts()));
            return;
        }

        if (path.equals("/daily")) {
            send(exchange, renderLayout("日付別", renderDaily(exchange)));
            return;
        }

        if (path.equals("/stats")) {
            send(exchange, renderLayout("集計", renderStats()));
            return;
        }

        if (path.equals("/shortage")) {
            send(exchange, renderLayout("不足チェック", renderShortage()));
            return;
        }

        if (path.equals("/requests")) {
            send(exchange, renderLayout("希望一覧", renderRequests()));
            return;
        }

        if (path.equals("/request-form")) {
            send(exchange, renderLayout("希望入力", renderRequestForm(exchange)));
            return;
        }

        if (path.equals("/request-save")) {
            renderRequestSave(exchange);
            return;
        }

        if (path.equals("/employees")) {
            send(exchange, renderLayout("従業員管理", renderEmployees(exchange)));
            return;
        }

        if (path.equals("/employee-form")) {
            send(exchange, renderLayout("従業員登録・編集", renderEmployeeForm(exchange)));
            return;
        }

        if (path.equals("/employee-save")) {
            saveEmployee(exchange);
            return;
        }

        if (path.equals("/employee-delete")) {
            deleteEmployee(exchange);
            return;
        }

        if (path.equals("/positions")) {
            send(exchange, renderLayout("ポジション管理", renderPositions(exchange)));
            return;
        }

        if (path.equals("/position-save")) {
            savePosition(exchange);
            return;
        }

        if (path.equals("/position-delete")) {
            deletePosition(exchange);
            return;
        }

        if (path.equals("/required-staff")) {
            send(exchange, renderLayout("必要人数設定", renderRequiredStaff(exchange)));
            return;
        }

        if (path.equals("/required-staff-save")) {
            saveRequiredStaff(exchange);
            return;
        }

        send(exchange, renderLayout("404", "<h1>ページが見つかりません</h1>"));
    }

    private static String renderPasswordForm(HttpExchange exchange) throws Exception {
        String result = getParams(exchange).get("result");
        StringBuilder html = new StringBuilder();
        html.append("<section class='panel password-panel'>");
        html.append("<h1>パスワード変更</h1>");

        if ("updated".equals(result)) {
            html.append("<p class='success'>パスワードを変更しました。</p>");
        } else if ("current-error".equals(result)) {
            html.append("<p class='error'>現在のパスワードが違います。</p>");
        } else if ("mismatch".equals(result)) {
            html.append("<p class='error'>新しいパスワードが一致していません。</p>");
        } else if ("invalid".equals(result)) {
            html.append("<p class='error'>新しいパスワードは8文字以上にしてください。</p>");
        }

        html.append("<form class='password-form' method='post' action='/password-save'>");
        html.append("<label>現在のパスワード<input type='password' name='current_password' autocomplete='current-password' required></label>");
        html.append("<label>新しいパスワード<input type='password' name='new_password' minlength='8' autocomplete='new-password' required></label>");
        html.append("<label>新しいパスワード（確認）<input type='password' name='confirm_password' minlength='8' autocomplete='new-password' required></label>");
        html.append("<button class='submit-button' type='submit'>変更する</button>");
        html.append("</form></section>");
        return html.toString();
    }

    private static void savePassword(HttpExchange exchange, Session session) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendMethodNotAllowed(exchange);
            return;
        }

        Map<String, String> params = getParams(exchange);
        String currentPassword = params.getOrDefault("current_password", "");
        String newPassword = params.getOrDefault("new_password", "");
        String confirmPassword = params.getOrDefault("confirm_password", "");

        if (newPassword.length() < 8) {
            redirect(exchange, "/password?result=invalid");
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            redirect(exchange, "/password?result=mismatch");
            return;
        }
        if (!authenticate(session.username, currentPassword)) {
            redirect(exchange, "/password?result=current-error");
            return;
        }

        String sql = "UPDATE app_users SET password_hash = ? WHERE username = ?";
        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, hashPassword(newPassword));
            statement.setString(2, session.username);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("ログインユーザーが見つかりません。");
            }
        }

        String currentToken = getCookie(exchange, SESSION_COOKIE);
        SESSIONS.entrySet().removeIf(entry ->
            entry.getValue().username.equals(session.username) && !entry.getKey().equals(currentToken)
        );
        redirect(exchange, "/password?result=updated");
    }

    private static String renderHome() {
        StringBuilder html = new StringBuilder();

        html.append("<section class='panel'>");
        html.append("<h1>シフト自動作成システム</h1>");
        html.append("<p>シフト生成、結果確認、希望入力をWeb画面からできます。</p>");
        html.append("<div class='home-grid'>");
        html.append(homeCard("シフト自動生成", "希望シフトと条件からシフトを作成します。", "/generate"));
        html.append(homeCard("シフト結果", "日別のシフトを横長の表で確認できます。", "/shifts"));
        html.append(homeCard("希望入力", "従業員ごとに指定期間の希望をまとめて登録できます。", "/request-form"));
        html.append(homeCard("不足チェック", "本当に不足している時間だけ確認できます。", "/shortage"));
        html.append("</div>");
        html.append("</section>");

        return html.toString();
    }

    private static String homeCard(String title, String body, String href) {
        return "<a class='home-card' href='" + href + "'>"
            + "<strong>" + title + "</strong>"
            + "<span>" + body + "</span>"
            + "</a>";
    }

    private static String renderGenerate(HttpExchange exchange) throws Exception {
        DateRange period = getConfiguredPeriod();
        String result = getParams(exchange).get("result");
        StringBuilder html = new StringBuilder();

        html.append("<section class='panel generate-panel'>");
        html.append("<h1>シフト自動生成</h1>");
        if ("success".equals(result)) {
            html.append("<p class='success'>指定した期間のシフト自動生成が完了しました。</p>");
            html.append("<p><a class='primary-link' href='/shifts'>シフト結果を見る</a></p>");
        } else if ("invalid".equals(result)) {
            html.append("<p class='error'>開始日と終了日を確認してください。期間は最大62日です。</p>");
        }

        html.append("<p>希望入力とシフト生成に使用する期間を指定します。</p>");
        html.append("<form class='generate-form' method='post' action='/generate-run' ")
            .append("onsubmit=\"return confirm('指定期間の作成済みシフトを削除して、再生成しますか？');\">");
        html.append("<label>開始日<input type='date' name='start_date' required value='")
            .append(period.start).append("'></label>");
        html.append("<label>終了日<input type='date' name='end_date' required value='")
            .append(period.end).append("'></label>");
        html.append("<button class='submit-button' type='submit'>この期間で生成する</button>");
        html.append("</form></section>");
        return html.toString();
    }

    private static void runGenerate(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            redirect(exchange, "/generate?result=invalid");
            return;
        }

        Map<String, String> params = getParams(exchange);
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(params.getOrDefault("start_date", ""));
            end = LocalDate.parse(params.getOrDefault("end_date", ""));
        } catch (Exception e) {
            redirect(exchange, "/generate?result=invalid");
            return;
        }

        long dayCount = Duration.between(start.atStartOfDay(), end.plusDays(1).atStartOfDay()).toDays();
        if (end.isBefore(start) || dayCount < 1 || dayCount > 62) {
            redirect(exchange, "/generate?result=invalid");
            return;
        }

        saveConfiguredPeriod(start, end);
        ShiftGenerator.generateShift(start, end);
        redirect(exchange, "/generate?result=success");
    }

    private static String renderEmployees(HttpExchange exchange) throws Exception {
        Map<String, String> params = getParams(exchange);
        StringBuilder html = new StringBuilder();

        html.append("<h1>従業員管理</h1>");
        appendEmployeeMessage(html, params.get("result"));
        html.append("<p><a class='primary-link' href='/employee-form'>従業員を登録する</a></p>");
        html.append("<table>");
        html.append("<tr><th>ID</th><th>名前</th><th>区分</th><th>担当ポジション</th><th>状態</th><th>操作</th></tr>");

        String sql =
            "SELECT e.employee_id, e.name, e.employment_type, e.is_active, " +
            "GROUP_CONCAT(CONCAT(p.position_name, ' Lv', ep.position_level) " +
            "ORDER BY p.position_id SEPARATOR ', ') AS position_summary " +
            "FROM employees e " +
            "LEFT JOIN employee_position ep ON ep.employee_id = e.employee_id " +
            "LEFT JOIN positions p ON p.position_id = ep.position_id " +
            "GROUP BY e.employee_id, e.name, e.employment_type, e.is_active " +
            "ORDER BY e.employee_id";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rs = statement.executeQuery()
        ) {
            while (rs.next()) {
                html.append("<tr>");
                html.append("<td>").append(rs.getInt("employee_id")).append("</td>");
                html.append("<td>").append(escape(rs.getString("name"))).append("</td>");
                html.append("<td>").append(displayType(rs.getString("employment_type"))).append("</td>");
                String positionSummary = rs.getString("position_summary");
                html.append("<td>").append(positionSummary == null ? "未設定" : escape(positionSummary)).append("</td>");
                html.append("<td>").append(rs.getBoolean("is_active") ? "有効" : "無効").append("</td>");
                html.append("<td><a class='table-link' href='/employee-form?employee_id=")
                    .append(rs.getInt("employee_id")).append("'>編集・昇格</a></td>");
                html.append("</tr>");
            }
        }

        html.append("</table>");
        return html.toString();
    }

    private static String renderEmployeeForm(HttpExchange exchange) throws Exception {
        Map<String, String> params = getParams(exchange);
        String employeeIdText = params.getOrDefault("employee_id", "").trim();
        Integer employeeId = null;
        String name = "";
        String employmentType = "PART_TIME";
        boolean active = true;

        if (!employeeIdText.isEmpty()) {
            try {
                employeeId = Integer.valueOf(employeeIdText);
            } catch (NumberFormatException e) {
                return "<h1>従業員登録・編集</h1><p class='error'>従業員IDが正しくありません。</p>";
            }

            String employeeSql =
                "SELECT name, employment_type, is_active FROM employees WHERE employee_id = ?";

            try (
                Connection connection = DBConnection.getConnection();
                PreparedStatement statement = connection.prepareStatement(employeeSql)
            ) {
                statement.setInt(1, employeeId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return "<h1>従業員登録・編集</h1><p class='error'>従業員が見つかりません。</p>";
                    }
                    name = rs.getString("name");
                    employmentType = rs.getString("employment_type");
                    active = rs.getBoolean("is_active");
                }
            }
        }

        StringBuilder html = new StringBuilder();
        html.append("<h1>").append(employeeId == null ? "従業員登録" : "従業員編集・昇格").append("</h1>");
        html.append("<form class='employee-form' method='post' action='/employee-save'>");
        if (employeeId != null) {
            html.append("<input type='hidden' name='employee_id' value='").append(employeeId).append("'>");
        }

        html.append("<section class='panel form-grid'>");
        html.append("<label>名前<input name='name' maxlength='100' required value='")
            .append(escape(name)).append("'></label>");
        html.append("<label>雇用区分<select name='employment_type' required>");
        appendEmploymentTypeOption(html, "NEWBIE", "新人", employmentType);
        appendEmploymentTypeOption(html, "PART_TIME", "アルバイト", employmentType);
        appendEmploymentTypeOption(html, "FULL_TIME", "正社員", employmentType);
        html.append("</select></label>");
        html.append("<label class='check-label'><input type='checkbox' name='is_active' value='true'");
        if (active) {
            html.append(" checked");
        }
        html.append(">在籍中（シフト作成の対象にする）</label>");
        html.append("</section>");

        html.append("<section class='panel'>");
        html.append("<h2>担当ポジションとレベル</h2>");
        html.append("<p class='muted'>0は担当不可、1以上は担当可能です。数字が高い人ほどシフト候補で優先されます。</p>");
        html.append("<div class='level-grid'>");

        String positionSql =
            "SELECT p.position_id, p.position_name, COALESCE(ep.position_level, 0) AS position_level " +
            "FROM positions p LEFT JOIN employee_position ep " +
            "ON ep.position_id = p.position_id AND ep.employee_id = ? " +
            "ORDER BY p.position_id";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(positionSql)
        ) {
            if (employeeId == null) {
                statement.setNull(1, Types.INTEGER);
            } else {
                statement.setInt(1, employeeId);
            }

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    html.append("<label>").append(escape(rs.getString("position_name")));
                    html.append("<input type='number' min='0' max='10' name='position_")
                        .append(rs.getInt("position_id")).append("' value='")
                        .append(rs.getInt("position_level")).append("'></label>");
                }
            }
        }

        html.append("</div>");
        html.append("</section>");
        html.append("<div class='form-actions'>");
        html.append("<a class='cancel-link' href='/employees'>戻る</a>");
        html.append("<button class='submit-button' type='submit'>保存する</button>");
        html.append("</div>");
        html.append("</form>");
        if (employeeId != null) {
            html.append("<form class='delete-employee-form' method='post' action='/employee-delete' ")
                .append("onsubmit=\"return confirm('この従業員を完全に削除しますか？\\n希望シフト・勤務シフト・ポジション情報も削除され、元に戻せません。');\">");
            html.append("<input type='hidden' name='employee_id' value='").append(employeeId).append("'>");
            html.append("<button class='danger-button' type='submit'>この従業員を完全削除</button>");
            html.append("</form>");
        }
        return html.toString();
    }

    private static void appendEmploymentTypeOption(
        StringBuilder html,
        String value,
        String label,
        String selected
    ) {
        html.append("<option value='").append(value).append("'");
        if (value.equals(selected)) {
            html.append(" selected");
        }
        html.append(">").append(label).append("</option>");
    }

    private static void appendEmployeeMessage(StringBuilder html, String result) {
        if ("created".equals(result)) {
            html.append("<p class='success'>従業員を登録しました。</p>");
        } else if ("updated".equals(result)) {
            html.append("<p class='success'>従業員情報・昇格情報を更新しました。</p>");
        } else if ("deleted".equals(result)) {
            html.append("<p class='success'>従業員と関連データを削除しました。</p>");
        } else if ("invalid".equals(result)) {
            html.append("<p class='error'>入力内容を確認してください。</p>");
        } else if ("not-found".equals(result)) {
            html.append("<p class='error'>指定された従業員が見つかりません。</p>");
        }
    }

    private static void saveEmployee(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            redirect(exchange, "/employees?result=invalid");
            return;
        }

        Map<String, String> params = getParams(exchange);
        String employeeIdText = params.getOrDefault("employee_id", "").trim();
        String name = params.getOrDefault("name", "").trim();
        String employmentType = params.getOrDefault("employment_type", "").trim();
        boolean active = "true".equals(params.get("is_active"));
        Integer employeeId = null;

        if (!employeeIdText.isEmpty()) {
            try {
                employeeId = Integer.valueOf(employeeIdText);
            } catch (NumberFormatException e) {
                redirect(exchange, "/employees?result=not-found");
                return;
            }
        }

        if (name.isEmpty() || name.length() > 100 ||
            !("NEWBIE".equals(employmentType) ||
              "PART_TIME".equals(employmentType) ||
              "FULL_TIME".equals(employmentType))) {
            redirect(exchange, "/employees?result=invalid");
            return;
        }

        try (Connection connection = DBConnection.getConnection()) {
            connection.setAutoCommit(false);

            try {
                boolean created = employeeId == null;

                if (created) {
                    String insertSql =
                        "INSERT INTO employees (name, employment_type, is_active) VALUES (?, ?, ?)";
                    try (PreparedStatement statement = connection.prepareStatement(
                        insertSql,
                        Statement.RETURN_GENERATED_KEYS
                    )) {
                        statement.setString(1, name);
                        statement.setString(2, employmentType);
                        statement.setBoolean(3, active);
                        statement.executeUpdate();

                        try (ResultSet keys = statement.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new IllegalStateException("従業員IDを取得できませんでした。");
                            }
                            employeeId = keys.getInt(1);
                        }
                    }
                } else {
                    String updateSql =
                        "UPDATE employees SET name = ?, employment_type = ?, is_active = ? " +
                        "WHERE employee_id = ?";
                    try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                        statement.setString(1, name);
                        statement.setString(2, employmentType);
                        statement.setBoolean(3, active);
                        statement.setInt(4, employeeId);
                        if (statement.executeUpdate() == 0) {
                            connection.rollback();
                            redirect(exchange, "/employees?result=not-found");
                            return;
                        }
                    }
                }

                saveEmployeePositionLevels(connection, employeeId, params);
                connection.commit();
                redirect(exchange, created
                    ? "/employees?result=created"
                    : "/employees?result=updated");
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof IllegalArgumentException) {
                    redirect(exchange, "/employees?result=invalid");
                    return;
                }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void deleteEmployee(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            redirect(exchange, "/employees?result=invalid");
            return;
        }

        String employeeIdText = getParams(exchange).getOrDefault("employee_id", "").trim();
        int employeeId;
        try {
            employeeId = Integer.parseInt(employeeIdText);
        } catch (NumberFormatException e) {
            redirect(exchange, "/employees?result=not-found");
            return;
        }

        String[] relatedDeleteSqls = {
            "DELETE FROM work_shift WHERE employee_id = ?",
            "DELETE FROM request_shift WHERE employee_id = ?",
            "DELETE FROM employee_day_off WHERE employee_id = ?",
            "DELETE FROM employee_position WHERE employee_id = ?"
        };

        try (Connection connection = DBConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String sql : relatedDeleteSqls) {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setInt(1, employeeId);
                        statement.executeUpdate();
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM employees WHERE employee_id = ?"
                )) {
                    statement.setInt(1, employeeId);
                    if (statement.executeUpdate() == 0) {
                        connection.rollback();
                        redirect(exchange, "/employees?result=not-found");
                        return;
                    }
                }

                connection.commit();
                redirect(exchange, "/employees?result=deleted");
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void saveEmployeePositionLevels(
        Connection connection,
        int employeeId,
        Map<String, String> params
    ) throws Exception {
        String positionsSql = "SELECT position_id FROM positions ORDER BY position_id";
        String upsertSql =
            "INSERT INTO employee_position (employee_id, position_id, position_level) " +
            "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE position_level = VALUES(position_level)";
        String deleteSql =
            "DELETE FROM employee_position WHERE employee_id = ? AND position_id = ?";

        try (
            PreparedStatement positionsStatement = connection.prepareStatement(positionsSql);
            ResultSet positions = positionsStatement.executeQuery();
            PreparedStatement upsertStatement = connection.prepareStatement(upsertSql);
            PreparedStatement deleteStatement = connection.prepareStatement(deleteSql)
        ) {
            while (positions.next()) {
                int positionId = positions.getInt("position_id");
                int level;

                try {
                    level = Integer.parseInt(params.getOrDefault("position_" + positionId, "0"));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("ポジションレベルは数字で入力してください。");
                }

                if (level < 0 || level > 10) {
                    throw new IllegalArgumentException("ポジションレベルは0から10で入力してください。");
                }

                if (level == 0) {
                    deleteStatement.setInt(1, employeeId);
                    deleteStatement.setInt(2, positionId);
                    deleteStatement.executeUpdate();
                } else {
                    upsertStatement.setInt(1, employeeId);
                    upsertStatement.setInt(2, positionId);
                    upsertStatement.setInt(3, level);
                    upsertStatement.executeUpdate();
                }
            }
        }
    }

    private static String renderPositions(HttpExchange exchange) throws Exception {
        Map<String, String> params = getParams(exchange);
        StringBuilder html = new StringBuilder();

        html.append("<h1>ポジション管理</h1>");
        appendPositionMessage(html, params.get("result"));

        html.append("<section class='panel'>");
        html.append("<h2>新しいポジションを登録</h2>");
        html.append("<form class='admin-form' method='post' action='/position-save'>");
        html.append("<label for='new-position-name'>ポジション名</label>");
        html.append("<input id='new-position-name' name='position_name' maxlength='100' required>");
        html.append("<button type='submit'>登録する</button>");
        html.append("</form>");
        html.append("</section>");

        html.append("<table>");
        html.append("<tr><th>ID</th><th>ポジション名</th><th>使用状況</th><th>操作</th></tr>");

        String sql =
            "SELECT p.position_id, p.position_name, " +
            "(SELECT COUNT(*) FROM employee_position ep WHERE ep.position_id = p.position_id) AS employee_count, " +
            "(SELECT COUNT(*) FROM required_staff rs WHERE rs.position_id = p.position_id) AS required_count, " +
            "(SELECT COUNT(*) FROM work_shift ws WHERE ws.position_id = p.position_id) AS shift_count " +
            "FROM positions p ORDER BY p.position_id";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rs = statement.executeQuery()
        ) {
            while (rs.next()) {
                int positionId = rs.getInt("position_id");
                int employeeCount = rs.getInt("employee_count");
                int requiredCount = rs.getInt("required_count");
                int shiftCount = rs.getInt("shift_count");
                boolean inUse = employeeCount + requiredCount + shiftCount > 0;

                html.append("<tr>");
                html.append("<td>").append(positionId).append("</td>");
                html.append("<td>");
                html.append("<form class='inline-form' method='post' action='/position-save'>");
                html.append("<input type='hidden' name='position_id' value='").append(positionId).append("'>");
                html.append("<input name='position_name' maxlength='100' required value='")
                    .append(escape(rs.getString("position_name"))).append("'>");
                html.append("<button type='submit'>更新</button>");
                html.append("</form>");
                html.append("</td>");
                html.append("<td>従業員 ").append(employeeCount)
                    .append(" / 必要人数設定 ").append(requiredCount)
                    .append(" / シフト ").append(shiftCount).append("</td>");
                html.append("<td>");
                if (inUse) {
                    html.append("<span class='muted'>使用中のため削除不可</span>");
                } else {
                    html.append("<form method='post' action='/position-delete' onsubmit=\"return confirm('このポジションを削除しますか？');\">");
                    html.append("<input type='hidden' name='position_id' value='").append(positionId).append("'>");
                    html.append("<button class='danger-button' type='submit'>削除</button>");
                    html.append("</form>");
                }
                html.append("</td>");
                html.append("</tr>");
            }
        }

        html.append("</table>");
        return html.toString();
    }

    private static void appendPositionMessage(StringBuilder html, String result) {
        if ("created".equals(result)) {
            html.append("<p class='success'>ポジションを登録しました。</p>");
        } else if ("updated".equals(result)) {
            html.append("<p class='success'>ポジション名を更新しました。</p>");
        } else if ("deleted".equals(result)) {
            html.append("<p class='success'>ポジションを削除しました。</p>");
        } else if ("invalid".equals(result)) {
            html.append("<p class='error'>ポジション名を入力してください。</p>");
        } else if ("duplicate".equals(result)) {
            html.append("<p class='error'>同じ名前のポジションがすでにあります。</p>");
        } else if ("in-use".equals(result)) {
            html.append("<p class='error'>使用中のポジションは削除できません。</p>");
        } else if ("not-found".equals(result)) {
            html.append("<p class='error'>指定されたポジションが見つかりません。</p>");
        }
    }

    private static void savePosition(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            redirect(exchange, "/positions?result=invalid");
            return;
        }

        Map<String, String> params = getParams(exchange);
        String positionName = params.getOrDefault("position_name", "").trim();
        String positionIdText = params.getOrDefault("position_id", "").trim();

        if (positionName.isEmpty() || positionName.length() > 100) {
            redirect(exchange, "/positions?result=invalid");
            return;
        }

        Integer positionId = null;
        if (!positionIdText.isEmpty()) {
            try {
                positionId = Integer.valueOf(positionIdText);
            } catch (NumberFormatException e) {
                redirect(exchange, "/positions?result=not-found");
                return;
            }
        }

        try (Connection connection = DBConnection.getConnection()) {
            String duplicateSql =
                "SELECT COUNT(*) AS count FROM positions " +
                "WHERE position_name = ? AND (? IS NULL OR position_id <> ?)";

            try (PreparedStatement statement = connection.prepareStatement(duplicateSql)) {
                statement.setString(1, positionName);
                if (positionId == null) {
                    statement.setNull(2, Types.INTEGER);
                    statement.setNull(3, Types.INTEGER);
                } else {
                    statement.setInt(2, positionId);
                    statement.setInt(3, positionId);
                }

                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next() && rs.getInt("count") > 0) {
                        redirect(exchange, "/positions?result=duplicate");
                        return;
                    }
                }
            }

            if (positionId == null) {
                try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO positions (position_name) VALUES (?)"
                )) {
                    statement.setString(1, positionName);
                    statement.executeUpdate();
                }
                redirect(exchange, "/positions?result=created");
                return;
            }

            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE positions SET position_name = ? WHERE position_id = ?"
            )) {
                statement.setString(1, positionName);
                statement.setInt(2, positionId);
                int updated = statement.executeUpdate();
                redirect(exchange, updated > 0
                    ? "/positions?result=updated"
                    : "/positions?result=not-found");
            }
        }
    }

    private static void deletePosition(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            redirect(exchange, "/positions?result=not-found");
            return;
        }

        Map<String, String> params = getParams(exchange);
        int positionId;

        try {
            positionId = Integer.parseInt(params.getOrDefault("position_id", ""));
        } catch (NumberFormatException e) {
            redirect(exchange, "/positions?result=not-found");
            return;
        }

        try (Connection connection = DBConnection.getConnection()) {
            String referenceSql =
                "SELECT " +
                "(SELECT COUNT(*) FROM employee_position WHERE position_id = ?) + " +
                "(SELECT COUNT(*) FROM required_staff WHERE position_id = ?) + " +
                "(SELECT COUNT(*) FROM work_shift WHERE position_id = ?) AS reference_count";

            try (PreparedStatement statement = connection.prepareStatement(referenceSql)) {
                statement.setInt(1, positionId);
                statement.setInt(2, positionId);
                statement.setInt(3, positionId);

                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next() && rs.getInt("reference_count") > 0) {
                        redirect(exchange, "/positions?result=in-use");
                        return;
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM positions WHERE position_id = ?"
            )) {
                statement.setInt(1, positionId);
                int deleted = statement.executeUpdate();
                redirect(exchange, deleted > 0
                    ? "/positions?result=deleted"
                    : "/positions?result=not-found");
            }
        }
    }

    private static String renderRequiredStaff(HttpExchange exchange) throws Exception {
        Map<String, String> params = getParams(exchange);
        String dayCode = normalizeDayCode(params.get("day"));
        String dayType = dayTypeFromCode(dayCode);
        StringBuilder html = new StringBuilder();

        html.append("<h1>必要人数設定</h1>");
        if ("saved".equals(params.get("result"))) {
            html.append("<p class='success'>").append(dayType).append("の必要人数を保存しました。</p>");
        } else if ("invalid".equals(params.get("result"))) {
            html.append("<p class='error'>必要人数は0から99の整数で入力してください。</p>");
        }

        html.append("<form class='filter-form' method='get' action='/required-staff'>");
        html.append("<label>曜日区分</label><select name='day'>");
        appendDayTypeOption(html, "weekday", "平日（月～木）", dayCode);
        appendDayTypeOption(html, "friday", "金曜日", dayCode);
        appendDayTypeOption(html, "weekend", "土日", dayCode);
        html.append("</select><button type='submit'>表示</button></form>");

        Map<Integer, String> positions = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        String positionsSql = "SELECT position_id, position_name FROM positions ORDER BY position_id";
        String countsSql =
            "SELECT position_id, time_slot, required_count FROM required_staff " +
            "WHERE day_type = ? ORDER BY time_slot, position_id";

        try (Connection connection = DBConnection.getConnection()) {
            try (
                PreparedStatement statement = connection.prepareStatement(positionsSql);
                ResultSet rs = statement.executeQuery()
            ) {
                while (rs.next()) {
                    positions.put(rs.getInt("position_id"), rs.getString("position_name"));
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(countsSql)) {
                statement.setString(1, dayType);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        LocalTime time = rs.getTime("time_slot").toLocalTime();
                        counts.put(requiredStaffKey(time, rs.getInt("position_id")),
                            rs.getInt("required_count"));
                    }
                }
            }
        }

        if (positions.isEmpty()) {
            html.append("<section class='panel'><p class='error'>先にポジションを登録してください。</p>");
            html.append("<a class='primary-link' href='/positions'>ポジション管理へ</a></section>");
            return html.toString();
        }

        html.append("<form method='post' action='/required-staff-save'>");
        html.append("<input type='hidden' name='day' value='").append(dayCode).append("'>");
        html.append("<p class='muted'>0人にすると、その時間・ポジションの必要人数設定を削除します。</p>");
        html.append("<div class='required-table-wrap'><table class='required-table'>");
        html.append("<tr><th>時間</th>");
        for (String positionName : positions.values()) {
            html.append("<th>").append(escape(positionName)).append("</th>");
        }
        html.append("</tr>");

        int slotIndex = 0;
        for (LocalTime time = OPEN_TIME; time.isBefore(CLOSE_TIME); time = addThirtyMinutesForWeb(time)) {
            html.append("<tr><th>").append(formatTime(time)).append("</th>");
            for (Integer positionId : positions.keySet()) {
                int count = counts.getOrDefault(requiredStaffKey(time, positionId), 0);
                html.append("<td><input type='number' min='0' max='99' required name='count_")
                    .append(slotIndex).append("_").append(positionId)
                    .append("' value='").append(count).append("'></td>");
            }
            html.append("</tr>");
            slotIndex++;
        }

        html.append("</table></div>");
        html.append("<div class='submit-area'><button class='submit-button' type='submit'>まとめて保存</button></div>");
        html.append("</form>");
        return html.toString();
    }

    private static void saveRequiredStaff(HttpExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            redirect(exchange, "/required-staff?result=invalid");
            return;
        }

        Map<String, String> params = getParams(exchange);
        String dayCode = normalizeDayCode(params.get("day"));
        String dayType = dayTypeFromCode(dayCode);
        Map<Integer, Integer> positionIds = new LinkedHashMap<>();

        try (Connection connection = DBConnection.getConnection()) {
            try (
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT position_id FROM positions ORDER BY position_id"
                );
                ResultSet rs = statement.executeQuery()
            ) {
                while (rs.next()) {
                    int positionId = rs.getInt("position_id");
                    positionIds.put(positionId, positionId);
                }
            }

            Map<String, Integer> values = new LinkedHashMap<>();
            int slotIndex = 0;
            for (LocalTime time = OPEN_TIME; time.isBefore(CLOSE_TIME); time = addThirtyMinutesForWeb(time)) {
                for (Integer positionId : positionIds.keySet()) {
                    String paramName = "count_" + slotIndex + "_" + positionId;
                    int count;
                    try {
                        count = Integer.parseInt(params.getOrDefault(paramName, "0"));
                    } catch (NumberFormatException e) {
                        redirect(exchange, "/required-staff?day=" + dayCode + "&result=invalid");
                        return;
                    }

                    if (count < 0 || count > 99) {
                        redirect(exchange, "/required-staff?day=" + dayCode + "&result=invalid");
                        return;
                    }
                    values.put(requiredStaffKey(time, positionId), count);
                }
                slotIndex++;
            }

            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM required_staff WHERE day_type = ?"
                )) {
                    statement.setString(1, dayType);
                    statement.executeUpdate();
                }

                String insertSql =
                    "INSERT INTO required_staff (day_type, time_slot, position_id, required_count) " +
                    "VALUES (?, ?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                    slotIndex = 0;
                    for (LocalTime time = OPEN_TIME; time.isBefore(CLOSE_TIME); time = addThirtyMinutesForWeb(time)) {
                        for (Integer positionId : positionIds.keySet()) {
                            int count = values.get(requiredStaffKey(time, positionId));
                            if (count > 0) {
                                statement.setString(1, dayType);
                                statement.setTime(2, Time.valueOf(time));
                                statement.setInt(3, positionId);
                                statement.setInt(4, count);
                                statement.addBatch();
                            }
                        }
                        slotIndex++;
                    }
                    statement.executeBatch();
                }

                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }

        redirect(exchange, "/required-staff?day=" + dayCode + "&result=saved");
    }

    private static void appendDayTypeOption(
        StringBuilder html,
        String value,
        String label,
        String selected
    ) {
        html.append("<option value='").append(value).append("'");
        if (value.equals(selected)) {
            html.append(" selected");
        }
        html.append(">").append(label).append("</option>");
    }

    private static String normalizeDayCode(String dayCode) {
        if ("friday".equals(dayCode) || "weekend".equals(dayCode)) {
            return dayCode;
        }
        return "weekday";
    }

    private static String dayTypeFromCode(String dayCode) {
        if ("friday".equals(dayCode)) {
            return "金";
        }
        if ("weekend".equals(dayCode)) {
            return "土日";
        }
        return "平日";
    }

    private static String requiredStaffKey(LocalTime time, int positionId) {
        return time.toString() + "_" + positionId;
    }

    private static LocalTime addThirtyMinutesForWeb(LocalTime time) {
        return time.plusMinutes(30);
    }

    private static String renderRequests() throws Exception {
        StringBuilder html = new StringBuilder();

        html.append("<h1>希望一覧</h1>");
        html.append("<p><a class='primary-link' href='/request-form'>希望を入力する</a></p>");
        html.append("<table>");
        html.append("<tr><th>日付</th><th>曜日</th><th>名前</th><th>区分</th><th>希望時間</th></tr>");

        String sql =
            "SELECT r.work_date, e.name, e.employment_type, r.start_time, r.end_time " +
            "FROM request_shift r " +
            "JOIN employees e ON r.employee_id = e.employee_id " +
            "ORDER BY r.work_date, r.start_time, e.employee_id";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rs = statement.executeQuery()
        ) {
            while (rs.next()) {
                LocalDate date = rs.getDate("work_date").toLocalDate();

                html.append("<tr>");
                html.append("<td>").append(date).append("</td>");
                html.append("<td>").append(dayOfWeek(date)).append("</td>");
                html.append("<td>").append(escape(rs.getString("name"))).append("</td>");
                html.append("<td>").append(displayType(rs.getString("employment_type"))).append("</td>");
                html.append("<td>").append(formatTimeRange(rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime())).append("</td>");
                html.append("</tr>");
            }
        }

        html.append("</table>");
        return html.toString();
    }

    private static String renderRequestForm(HttpExchange exchange) throws Exception {
        Map<String, String> params = getParams(exchange);

        DateRange period = getConfiguredPeriod();
        LocalDate periodStart = period.start;
        LocalDate periodEnd = period.end;
        int periodDays = (int) Duration.between(
            periodStart.atStartOfDay(),
            periodEnd.plusDays(1).atStartOfDay()
        ).toDays();

        int selectedEmployeeId = 0;
        if (params.containsKey("employee_id") && !params.get("employee_id").isBlank()) {
            selectedEmployeeId = Integer.parseInt(params.get("employee_id"));
        }

        List<EmployeeOption> employees = getEmployeeOptions();

        if (selectedEmployeeId == 0 && !employees.isEmpty()) {
            selectedEmployeeId = employees.get(0).employeeId;
        }

        Map<LocalDate, RequestItem> existingRequests =
            getExistingRequests(selectedEmployeeId, periodStart, periodEnd);

        StringBuilder html = new StringBuilder();

        html.append("<h1>希望入力</h1>");

        html.append("<form class='filter-form' method='get' action='/request-form'>");
        html.append("<label>従業員</label>");
        html.append("<select name='employee_id'>");

        for (EmployeeOption employee : employees) {
            html.append("<option value='").append(employee.employeeId).append("'");
            if (employee.employeeId == selectedEmployeeId) {
                html.append(" selected");
            }
            html.append(">").append(escape(employee.name)).append("（").append(displayType(employee.employmentType)).append("）</option>");
        }

        html.append("</select>");
        html.append("<button type='submit'>表示</button>");
        html.append("</form>");

        html.append("<form method='post' action='/request-save' class='request-submit-form'>");
        html.append("<input type='hidden' name='employee_id' value='").append(selectedEmployeeId).append("'>");
        html.append("<input type='hidden' name='period_start' value='").append(periodStart).append("'>");
        html.append("<input type='hidden' name='period_end' value='").append(periodEnd).append("'>");

        html.append("<div class='request-top'>");
        html.append("<div>");
        html.append("<strong>").append(periodStart).append("〜").append(periodEnd).append("</strong>");
        html.append("<span>指定期間の希望をまとめて登録</span>");
        html.append("</div>");
        html.append("<div class='total-box'>合計 <span id='totalHours'>0:00</span></div>");
        html.append("</div>");

        html.append("<div class='request-days'>");

        for (int i = 0; i < periodDays; i++) {
            LocalDate date = periodStart.plusDays(i);
            RequestItem request = existingRequests.get(date);

            String mode = "off";
            String start = "10:00";
            String end = "22:00";

            if (request != null) {
                start = request.start;
                end = request.end;

                if (start.equals("10:00") && end.equals("22:00")) {
                    mode = "all";
                } else {
                    mode = "time";
                }
            }

            html.append("<section class='request-day'>");
            html.append("<div class='request-date'>");
            html.append("<strong>").append(date.toString().replace("-", "/")).append("（").append(dayOfWeek(date)).append("）</strong>");
            html.append("</div>");

            html.append("<div class='request-controls'>");

            html.append("<label class='radio-card'>");
            html.append("<input type='radio' name='mode_").append(i).append("' value='off'");
            if (mode.equals("off")) {
                html.append(" checked");
            }
            html.append(" onchange='updateTotal()'>休み");
            html.append("</label>");

            html.append("<label class='radio-card'>");
            html.append("<input type='radio' name='mode_").append(i).append("' value='all'");
            if (mode.equals("all")) {
                html.append(" checked");
            }
            html.append(" onchange='updateTotal()'>終日OK");
            html.append("</label>");

            html.append("<label class='radio-card'>");
            html.append("<input type='radio' name='mode_").append(i).append("' value='time'");
            if (mode.equals("time")) {
                html.append(" checked");
            }
            html.append(" onchange='updateTotal()'>時間指定");
            html.append("</label>");

            html.append("<div class='time-selects'>");
            html.append("<select name='start_").append(i).append("' onchange='updateTotal()'>");
            appendTimeOptions(html, start);
            html.append("</select>");
            html.append("<span>〜</span>");
            html.append("<select name='end_").append(i).append("' onchange='updateTotal()'>");
            appendTimeOptions(html, end);
            html.append("</select>");
            html.append("</div>");

            html.append("</div>");
            html.append("</section>");
        }

        html.append("</div>");

        html.append("<div class='submit-area'>");
        html.append("<button type='submit' class='submit-button'>提出する</button>");
        html.append("</div>");

        html.append("</form>");

        html.append("<script>");
        html.append("function toMinutes(v){var p=v.split(':');return Number(p[0])*60+Number(p[1]);}");
        html.append("function updateTotal(){");
        html.append("var total=0;");
        html.append("for(var i=0;i<").append(periodDays).append(";i++){");
        html.append("var mode=document.querySelector('input[name=\"mode_'+i+'\"]:checked');");
        html.append("if(!mode){continue;}");
        html.append("if(mode.value==='all'){total+=720;}");
        html.append("if(mode.value==='time'){");
        html.append("var s=document.querySelector('select[name=\"start_'+i+'\"]').value;");
        html.append("var e=document.querySelector('select[name=\"end_'+i+'\"]').value;");
        html.append("var diff=toMinutes(e)-toMinutes(s);");
        html.append("if(diff>0){total+=diff;}");
        html.append("}");
        html.append("}");
        html.append("var h=Math.floor(total/60);");
        html.append("var m=total%60;");
        html.append("document.getElementById('totalHours').textContent=h+':' + String(m).padStart(2,'0');");
        html.append("}");
        html.append("updateTotal();");
        html.append("</script>");

        return html.toString();
    }

    private static void renderRequestSave(HttpExchange exchange) throws Exception {
        Map<String, String> params = getParams(exchange);

        int employeeId = Integer.parseInt(params.get("employee_id"));
        LocalDate periodStart = LocalDate.parse(params.get("period_start"));
        LocalDate periodEnd = LocalDate.parse(params.get("period_end"));
        int periodDays = (int) Duration.between(
            periodStart.atStartOfDay(),
            periodEnd.plusDays(1).atStartOfDay()
        ).toDays();

        if (periodEnd.isBefore(periodStart) || periodDays < 1 || periodDays > 62) {
            redirect(exchange, "/request-form");
            return;
        }

        try (Connection connection = DBConnection.getConnection()) {
            connection.setAutoCommit(false);

            String deleteSql =
                "DELETE FROM request_shift " +
                "WHERE employee_id = ? " +
                "AND work_date BETWEEN ? AND ?";

            try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
                statement.setInt(1, employeeId);
                statement.setDate(2, Date.valueOf(periodStart));
                statement.setDate(3, Date.valueOf(periodEnd));
                statement.executeUpdate();
            }

            String insertSql =
                "INSERT INTO request_shift " +
                "(employee_id, work_date, start_time, end_time, is_submitted) " +
                "VALUES (?, ?, ?, ?, true)";

            try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                for (int i = 0; i < periodDays; i++) {
                    LocalDate date = periodStart.plusDays(i);
                    String mode = params.getOrDefault("mode_" + i, "off");

                    if (mode.equals("off")) {
                        continue;
                    }

                    String startText;
                    String endText;

                    if (mode.equals("all")) {
                        startText = "10:00";
                        endText = "22:00";
                    } else {
                        startText = params.get("start_" + i);
                        endText = params.get("end_" + i);
                    }

                    LocalTime start = LocalTime.parse(startText);
                    LocalTime end = LocalTime.parse(endText);

                    if (!start.isBefore(end)) {
                        continue;
                    }

                    statement.setInt(1, employeeId);
                    statement.setDate(2, Date.valueOf(date));
                    statement.setTime(3, Time.valueOf(start));
                    statement.setTime(4, Time.valueOf(end));
                    statement.addBatch();
                }

                statement.executeBatch();
            }

            connection.commit();
        }

        redirect(exchange, "/requests");
    }

    private static String renderShifts() throws Exception {
        StringBuilder html = new StringBuilder();

        html.append("<h1>シフト結果</h1>");

        List<ShiftBlock> blocks = getShiftBlocks();

        LocalDate currentDate = null;
        List<ShiftBlock> dayBlocks = new ArrayList<>();

        for (ShiftBlock block : blocks) {
            if (currentDate == null) {
                currentDate = block.workDate;
            }

            if (!currentDate.equals(block.workDate)) {
                html.append(renderShiftDay(currentDate, dayBlocks));
                dayBlocks.clear();
                currentDate = block.workDate;
            }

            dayBlocks.add(block);
        }

        if (currentDate != null) {
            html.append(renderShiftDay(currentDate, dayBlocks));
        }

        if (blocks.isEmpty()) {
            html.append("<section class='panel'><p>シフト結果がありません。</p></section>");
        }

        return html.toString();
    }

    private static String renderShiftDay(LocalDate date, List<ShiftBlock> blocks) {
        StringBuilder html = new StringBuilder();

        Map<String, List<ShiftBlock>> byEmployee = new LinkedHashMap<>();

        for (ShiftBlock block : blocks) {
            byEmployee.computeIfAbsent(block.name, key -> new ArrayList<>()).add(block);
        }

        html.append("<section class='shift-card'>");
        html.append("<h2>").append(date).append("（").append(dayOfWeek(date)).append("）</h2>");

        html.append("<div class='shift-chart'>");

        html.append("<div class='shift-header'>");
        html.append("<div class='name-cell'>名前</div>");
        html.append("<div class='hour-area'>");
        for (int hour = 10; hour <= 22; hour++) {
            html.append("<div>").append(String.format("%02d:00", hour)).append("</div>");
        }
        html.append("</div>");
        html.append("</div>");

        for (Map.Entry<String, List<ShiftBlock>> entry : byEmployee.entrySet()) {
            html.append("<div class='shift-row'>");
            html.append("<div class='name-cell'>").append(escape(entry.getKey())).append("</div>");
            html.append("<div class='shift-line'>");

            for (ShiftBlock block : entry.getValue()) {
                int startColumn = timeToHalfHourIndex(block.startTime) + 1;
                int endColumn = timeToHalfHourIndex(block.endTime) + 1;

                if (startColumn < 1) {
                    startColumn = 1;
                }

                if (endColumn > 27) {
                    endColumn = 27;
                }

                if (endColumn <= startColumn) {
                    endColumn = startColumn + 1;
                }

                html.append("<div class='shift-bar' style='grid-column:")
                    .append(startColumn)
                    .append("/")
                    .append(endColumn)
                    .append("'>")
                    .append(formatTimeRange(block.startTime, block.endTime))
                    .append("</div>");
            }

            html.append("</div>");
            html.append("</div>");
        }

        html.append("</div>");
        html.append("</section>");

        return html.toString();
    }

    private static String renderDaily(HttpExchange exchange) throws Exception {
        Map<String, String> params = getParams(exchange);

        LocalDate date = getDefaultStartDate();
        if (params.containsKey("date") && !params.get("date").isBlank()) {
            date = LocalDate.parse(params.get("date"));
        }

        StringBuilder html = new StringBuilder();

        html.append("<h1>日付別</h1>");
        html.append("<form class='filter-form' method='get' action='/daily'>");
        html.append("<label>日付</label>");
        html.append("<input type='date' name='date' value='").append(date).append("'>");
        html.append("<button type='submit'>表示</button>");
        html.append("</form>");

        html.append("<section class='panel'>");
        html.append("<h2>").append(date).append("（").append(dayOfWeek(date)).append("）のシフト</h2>");
        html.append("<table>");
        html.append("<tr><th>名前</th><th>時間</th><th>ポジション</th></tr>");

        String shiftSql =
            "SELECT e.employee_id, e.name, ws.start_time, ws.end_time, p.position_name " +
            "FROM work_shift ws " +
            "JOIN employees e ON ws.employee_id = e.employee_id " +
            "JOIN positions p ON ws.position_id = p.position_id " +
            "WHERE ws.work_date = ? " +
            "ORDER BY e.employee_id, ws.start_time, p.position_id";

        List<DailyShift> dailyShifts = new ArrayList<>();

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(shiftSql)
        ) {
            statement.setDate(1, Date.valueOf(date));

            try (ResultSet rs = statement.executeQuery()) {
                DailyShift current = null;

                while (rs.next()) {
                    int employeeId = rs.getInt("employee_id");
                    LocalTime start = rs.getTime("start_time").toLocalTime();
                    LocalTime end = rs.getTime("end_time").toLocalTime();
                    String positionName = rs.getString("position_name");

                    if (current != null &&
                        current.employeeId == employeeId &&
                        current.end.equals(start)) {
                        current.end = end;
                        current.addPosition(positionName);
                    } else {
                        current = new DailyShift(
                            employeeId,
                            rs.getString("name"),
                            start,
                            end,
                            positionName
                        );
                        dailyShifts.add(current);
                    }
                }
            }
        }

        dailyShifts.sort((a, b) -> {
            int timeComparison = a.start.compareTo(b.start);
            if (timeComparison != 0) {
                return timeComparison;
            }
            return Integer.compare(a.employeeId, b.employeeId);
        });

        if (dailyShifts.isEmpty()) {
            html.append("<tr><td colspan='3' class='muted'>この日のシフトはありません。</td></tr>");
        } else {
            for (DailyShift shift : dailyShifts) {
                html.append("<tr>");
                html.append("<td>").append(escape(shift.name)).append("</td>");
                html.append("<td><strong>")
                    .append(formatTimeRange(shift.start, shift.end)).append("</strong></td>");
                html.append("<td>").append(escape(shift.getPositionSummary())).append("</td>");
                html.append("</tr>");
            }
        }

        html.append("</table>");
        html.append("</section>");

        html.append("<section class='panel'>");
        html.append("<h2>希望を出しているが入っていない人</h2>");
        html.append("<table>");
        html.append("<tr><th>名前</th><th>希望時間</th></tr>");

        String requestSql =
            "SELECT e.name, r.start_time, r.end_time " +
            "FROM request_shift r " +
            "JOIN employees e ON r.employee_id = e.employee_id " +
            "WHERE r.work_date = ? " +
            "AND NOT EXISTS ( " +
            "    SELECT 1 FROM work_shift ws " +
            "    WHERE ws.employee_id = r.employee_id " +
            "    AND ws.work_date = r.work_date " +
            ") " +
            "ORDER BY r.start_time, e.employee_id";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(requestSql)
        ) {
            statement.setDate(1, Date.valueOf(date));

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    html.append("<tr>");
                    html.append("<td>").append(escape(rs.getString("name"))).append("</td>");
                    html.append("<td>").append(formatTimeRange(rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime())).append("</td>");
                    html.append("</tr>");
                }
            }
        }

        html.append("</table>");
        html.append("</section>");

        return html.toString();
    }

    private static String renderStats() throws Exception {
        StringBuilder html = new StringBuilder();

        html.append("<h1>集計</h1>");
        html.append("<table>");
        html.append("<tr><th>名前</th><th>区分</th><th>希望時間</th><th>採用時間</th><th>採用率</th><th>出勤回数</th></tr>");

        String sql =
            "SELECT e.employee_id, e.name, e.employment_type, " +
            "COALESCE(r.request_hours, 0) AS request_hours, " +
            "COALESCE(w.work_hours, 0) AS work_hours, " +
            "COALESCE(w.work_days, 0) AS work_days " +
            "FROM employees e " +
            "LEFT JOIN ( " +
            "    SELECT employee_id, SUM(TIMESTAMPDIFF(MINUTE, start_time, end_time)) / 60 AS request_hours " +
            "    FROM request_shift " +
            "    GROUP BY employee_id " +
            ") r ON e.employee_id = r.employee_id " +
            "LEFT JOIN ( " +
            "    SELECT employee_id, COUNT(*) / 2 AS work_hours, COUNT(DISTINCT work_date) AS work_days " +
            "    FROM work_shift " +
            "    GROUP BY employee_id " +
            ") w ON e.employee_id = w.employee_id " +
            "WHERE e.is_active = true " +
            "ORDER BY CASE e.employment_type " +
            "WHEN 'FULL_TIME' THEN 1 " +
            "WHEN 'PART_TIME' THEN 2 " +
            "WHEN 'NEWBIE' THEN 3 " +
            "ELSE 4 END, e.employee_id";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rs = statement.executeQuery()
        ) {
            while (rs.next()) {
                double requestHours = rs.getDouble("request_hours");
                double workHours = rs.getDouble("work_hours");
                double rate = requestHours == 0 ? 0 : workHours / requestHours * 100;

                html.append("<tr>");
                html.append("<td>").append(escape(rs.getString("name"))).append("</td>");
                html.append("<td>").append(displayType(rs.getString("employment_type"))).append("</td>");
                html.append("<td>").append(String.format("%.1f", requestHours)).append("</td>");
                html.append("<td>").append(String.format("%.1f", workHours)).append("</td>");
                html.append("<td>").append(String.format("%.1f", rate)).append("%</td>");
                html.append("<td>").append(rs.getInt("work_days")).append("</td>");
                html.append("</tr>");
            }
        }

        html.append("</table>");
        return html.toString();
    }

    private static String renderShortage() throws Exception {
        StringBuilder html = new StringBuilder();

        html.append("<h1>不足チェック</h1>");

        List<ShortageItem> shortages = getShortages();

        if (shortages.isEmpty()) {
            html.append("<section class='panel'><p class='success'>不足している時間はありません。</p></section>");
            return html.toString();
        }

        html.append("<table>");
        html.append("<tr><th>日付</th><th>時間</th><th>ポジション</th><th>不足人数</th></tr>");

        for (ShortageItem item : shortages) {
            html.append("<tr class='shortage-row'>");
            html.append("<td>").append(item.date).append("（").append(dayOfWeek(item.date)).append("）</td>");
            html.append("<td>").append(formatTimeRange(item.start, item.end)).append("</td>");
            html.append("<td>").append(escape(item.positionName)).append("</td>");
            html.append("<td>").append(item.shortageCount).append("人不足</td>");
            html.append("</tr>");
        }

        html.append("</table>");
        return html.toString();
    }

    private static List<EmployeeOption> getEmployeeOptions() throws Exception {
        List<EmployeeOption> employees = new ArrayList<>();

        String sql =
            "SELECT employee_id, name, employment_type " +
            "FROM employees " +
            "WHERE is_active = true " +
            "ORDER BY CASE employment_type " +
            "WHEN 'FULL_TIME' THEN 1 " +
            "WHEN 'PART_TIME' THEN 2 " +
            "WHEN 'NEWBIE' THEN 3 " +
            "ELSE 4 END, employee_id";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rs = statement.executeQuery()
        ) {
            while (rs.next()) {
                employees.add(new EmployeeOption(
                    rs.getInt("employee_id"),
                    rs.getString("name"),
                    rs.getString("employment_type")
                ));
            }
        }

        return employees;
    }

    private static Map<LocalDate, RequestItem> getExistingRequests(int employeeId, LocalDate startDate, LocalDate endDate) throws Exception {
        Map<LocalDate, RequestItem> requests = new LinkedHashMap<>();

        String sql =
            "SELECT work_date, start_time, end_time " +
            "FROM request_shift " +
            "WHERE employee_id = ? " +
            "AND work_date BETWEEN ? AND ?";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setInt(1, employeeId);
            statement.setDate(2, Date.valueOf(startDate));
            statement.setDate(3, Date.valueOf(endDate));

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    requests.put(
                        rs.getDate("work_date").toLocalDate(),
                        new RequestItem(formatTime(rs.getTime("start_time")), formatTime(rs.getTime("end_time")))
                    );
                }
            }
        }

        return requests;
    }

    private static List<ShiftBlock> getShiftBlocks() throws Exception {
        List<ShiftBlock> blocks = new ArrayList<>();

        String sql =
            "SELECT ws.work_date, ws.employee_id, e.name, ws.start_time, ws.end_time " +
            "FROM work_shift ws " +
            "JOIN employees e ON ws.employee_id = e.employee_id " +
            "ORDER BY ws.work_date, ws.employee_id, ws.start_time";

        ShiftBlock current = null;
        int currentEmployeeId = -1;

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rs = statement.executeQuery()
        ) {
            while (rs.next()) {
                LocalDate workDate = rs.getDate("work_date").toLocalDate();
                int employeeId = rs.getInt("employee_id");
                String name = rs.getString("name");
                LocalTime startTime = rs.getTime("start_time").toLocalTime();
                LocalTime endTime = rs.getTime("end_time").toLocalTime();

                if (current != null &&
                    current.workDate.equals(workDate) &&
                    currentEmployeeId == employeeId &&
                    current.endTime.equals(startTime)) {

                    current.endTime = endTime;
                } else {
                    if (current != null) {
                        blocks.add(current);
                    }

                    current = new ShiftBlock(workDate, name, startTime, endTime);
                    currentEmployeeId = employeeId;
                }
            }
        }

        if (current != null) {
            blocks.add(current);
        }

        return blocks;
    }

    private static List<ShortageItem> getShortages() throws Exception {
        List<ShortageItem> shortages = new ArrayList<>();
        List<LocalDate> dates = getTargetDates();

        String requiredSql =
            "SELECT rs.time_slot, rs.position_id, p.position_name, rs.required_count " +
            "FROM required_staff rs " +
            "JOIN positions p ON rs.position_id = p.position_id " +
            "WHERE rs.day_type = ? " +
            "ORDER BY rs.position_id, rs.time_slot";

        String assignedSql =
            "SELECT COUNT(*) AS assigned_count " +
            "FROM work_shift " +
            "WHERE work_date = ? " +
            "AND position_id = ? " +
            "AND start_time = ?";

        try (Connection connection = DBConnection.getConnection()) {
            for (LocalDate date : dates) {
                String dayType = getDayType(date);

                try (PreparedStatement requiredStmt = connection.prepareStatement(requiredSql)) {
                    requiredStmt.setString(1, dayType);

                    try (ResultSet rs = requiredStmt.executeQuery()) {
                        ShortageItem current = null;

                        while (rs.next()) {
                            LocalTime slot = rs.getTime("time_slot").toLocalTime();
                            int positionId = rs.getInt("position_id");
                            String positionName = rs.getString("position_name");
                            int requiredCount = rs.getInt("required_count");

                            int assignedCount;

                            try (PreparedStatement assignedStmt = connection.prepareStatement(assignedSql)) {
                                assignedStmt.setDate(1, Date.valueOf(date));
                                assignedStmt.setInt(2, positionId);
                                assignedStmt.setTime(3, Time.valueOf(slot));

                                try (ResultSet assignedRs = assignedStmt.executeQuery()) {
                                    assignedRs.next();
                                    assignedCount = assignedRs.getInt("assigned_count");
                                }
                            }

                            int shortageCount = requiredCount - assignedCount;

                            if (shortageCount <= 0) {
                                if (current != null) {
                                    shortages.add(current);
                                    current = null;
                                }
                                continue;
                            }

                            LocalTime end = slot.plusMinutes(30);

                            if (current != null &&
                                current.date.equals(date) &&
                                current.positionId == positionId &&
                                current.shortageCount == shortageCount &&
                                current.end.equals(slot)) {

                                current.end = end;
                            } else {
                                if (current != null) {
                                    shortages.add(current);
                                }

                                current = new ShortageItem(date, slot, end, positionId, positionName, shortageCount);
                            }
                        }

                        if (current != null) {
                            shortages.add(current);
                        }
                    }
                }
            }
        }

        return shortages;
    }

    private static List<LocalDate> getTargetDates() throws Exception {
        List<LocalDate> dates = new ArrayList<>();

        DateRange period = getConfiguredPeriod();
        LocalDate startDate = period.start;
        LocalDate endDate = period.end;

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            dates.add(date);
        }

        return dates;
    }

    private static LocalDate getDefaultStartDate() throws Exception {
        return getConfiguredPeriod().start;
    }

    private static DateRange getConfiguredPeriod() throws Exception {
        LocalDate fallbackStart = getRequestStartDate();
        LocalDate start = fallbackStart;
        LocalDate end = fallbackStart.plusDays(14);
        String sql =
            "SELECT setting_key, setting_value FROM app_settings " +
            "WHERE setting_key IN ('shift_start_date', 'shift_end_date')";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rs = statement.executeQuery()
        ) {
            while (rs.next()) {
                try {
                    if ("shift_start_date".equals(rs.getString("setting_key"))) {
                        start = LocalDate.parse(rs.getString("setting_value"));
                    } else if ("shift_end_date".equals(rs.getString("setting_key"))) {
                        end = LocalDate.parse(rs.getString("setting_value"));
                    }
                } catch (Exception ignored) {
                    // 壊れた設定値は既定期間に戻す
                }
            }
        }

        if (end.isBefore(start)) {
            end = start.plusDays(14);
        }
        return new DateRange(start, end);
    }

    private static void saveConfiguredPeriod(LocalDate start, LocalDate end) throws Exception {
        String sql =
            "INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?) " +
            "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, "shift_start_date");
            statement.setString(2, start.toString());
            statement.addBatch();
            statement.setString(1, "shift_end_date");
            statement.setString(2, end.toString());
            statement.addBatch();
            statement.executeBatch();
        }
    }

    private static LocalDate getRequestStartDate() throws Exception {
        String sql = "SELECT MIN(work_date) AS start_date FROM request_shift";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rs = statement.executeQuery()
        ) {
            if (rs.next() && rs.getDate("start_date") != null) {
                return rs.getDate("start_date").toLocalDate();
            }
        }

        return LocalDate.now();
    }

    private static String getDayType(LocalDate date) {
        switch (date.getDayOfWeek()) {
            case FRIDAY:
                return "金";
            case SATURDAY:
            case SUNDAY:
                return "土日";
            default:
                return "平日";
        }
    }

    private static void appendTimeOptions(StringBuilder html, String selected) {
        for (LocalTime time = OPEN_TIME; !time.isAfter(CLOSE_TIME); time = time.plusMinutes(30)) {
            String text = time.toString();

            html.append("<option value='").append(text).append("'");
            if (text.equals(selected)) {
                html.append(" selected");
            }
            html.append(">").append(text).append("</option>");
        }
    }

    private static int timeToHalfHourIndex(LocalTime time) {
        int minutes = (int) Duration.between(OPEN_TIME, time).toMinutes();
        return minutes / 30;
    }

    private static Map<String, String> getParams(HttpExchange exchange) throws Exception {
        if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            try (InputStream inputStream = exchange.getRequestBody()) {
                String body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                return parseParams(body);
            }
        }

        String query = exchange.getRequestURI().getRawQuery();
        return parseParams(query);
    }

    private static Map<String, String> parseParams(String text) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();

        if (text == null || text.isBlank()) {
            return params;
        }

        String[] pairs = text.split("&");

        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);

            String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
            String value = "";

            if (keyValue.length > 1) {
                value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
            }

            params.put(key, value);
        }

        return params;
    }

    private static String renderLayout(String title, String body) {
        return "<!DOCTYPE html>"
            + "<html lang='ja'>"
            + "<head>"
            + "<meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
            + "<title>" + escape(title) + "</title>"
            + "<style>"
            + "body{margin:0;background:#f4f7fb;color:#071421;font-family:Arial,'Yu Gothic',Meiryo,sans-serif;}"
            + ".wrap{max-width:1280px;margin:0 auto;padding:32px 24px 64px;}"
            + "h1{font-size:30px;margin:0 0 28px;}h2{font-size:20px;margin:0 0 16px;}"
            + ".nav{display:flex;flex-wrap:wrap;gap:10px;margin:0 0 28px;}"
            + ".nav a{display:inline-flex;align-items:center;justify-content:center;height:46px;padding:0 18px;border:1px solid #ccd8e4;border-radius:6px;background:#fff;color:#071421;text-decoration:none;font-weight:700;}"
            + ".nav a.primary{background:#247bd1;color:#fff;border-color:#247bd1;}"
            + ".nav-user{display:flex;align-items:center;gap:8px;margin-left:auto;font-weight:700;}"
            + ".logout-button{height:46px;padding:0 16px;border:1px solid #ccd8e4;border-radius:6px;background:#fff;color:#071421;font-weight:700;cursor:pointer;}"
            + ".panel,.shift-card{background:#fff;border:1px solid #d5e0ea;border-radius:6px;padding:18px;margin:0 0 28px;}"
            + ".home-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:14px;margin-top:18px;}"
            + ".home-card{display:flex;flex-direction:column;gap:8px;background:#fff;border:1px solid #d5e0ea;border-radius:6px;padding:18px;text-decoration:none;color:#071421;}"
            + ".home-card strong{font-size:18px;}.home-card span{color:#52606d;line-height:1.5;}"
            + "table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #d5e0ea;margin:0 0 24px;}"
            + "th,td{padding:14px 14px;border-bottom:1px solid #e4ebf2;text-align:left;}th{background:#eef3f8;font-weight:700;}"
            + ".success{color:#147a35;font-weight:700;}.error{color:#b42318;font-weight:700;}.shortage-row{background:#fff1f2;}"
            + ".primary-link{display:inline-flex;align-items:center;height:42px;padding:0 16px;background:#247bd1;color:#fff;border-radius:6px;text-decoration:none;font-weight:700;}"
            + ".filter-form{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin:0 0 22px;}"
            + ".filter-form label{font-weight:700;}.filter-form input,.filter-form select{height:42px;padding:0 10px;border:1px solid #cbd6e2;border-radius:6px;background:#fff;}"
            + ".filter-form button{height:42px;padding:0 18px;border:0;border-radius:6px;background:#247bd1;color:#fff;font-weight:700;cursor:pointer;}"
            + ".admin-form,.inline-form{display:flex;flex-wrap:wrap;gap:10px;align-items:center;}"
            + ".admin-form label{font-weight:700;}.admin-form input,.inline-form input{height:40px;min-width:240px;padding:0 10px;border:1px solid #cbd6e2;border-radius:6px;}"
            + ".admin-form button,.inline-form button,.danger-button{height:40px;padding:0 16px;border:0;border-radius:6px;background:#247bd1;color:#fff;font-weight:700;cursor:pointer;}"
            + ".danger-button{background:#b42318;}.muted{color:#687787;font-size:14px;}"
            + ".table-link{display:inline-flex;padding:8px 12px;border-radius:6px;background:#eef6fc;color:#1769aa;text-decoration:none;font-weight:700;}"
            + ".form-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:18px;}"
            + ".form-grid label,.level-grid label{display:flex;flex-direction:column;gap:8px;font-weight:700;}"
            + ".form-grid input,.form-grid select,.level-grid input{height:42px;padding:0 10px;border:1px solid #cbd6e2;border-radius:6px;background:#fff;}"
            + ".form-grid .check-label{flex-direction:row;align-items:center;align-self:end;height:42px;}.check-label input{width:20px;height:20px;}"
            + ".level-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:14px;margin-top:18px;}"
            + ".form-actions{display:flex;justify-content:center;align-items:center;gap:16px;padding:0 0 24px;}"
            + ".cancel-link{display:inline-flex;align-items:center;height:52px;padding:0 24px;color:#52606d;text-decoration:none;font-weight:700;}"
            + ".delete-employee-form{display:flex;justify-content:center;border-top:1px solid #d5e0ea;padding:24px 0 8px;margin-top:8px;}"
            + ".required-table-wrap{overflow:auto;max-height:70vh;border:1px solid #d5e0ea;background:#fff;}"
            + ".required-table{margin:0;border:0;min-width:620px;}.required-table th{position:sticky;top:0;z-index:2;}"
            + ".required-table th:first-child{left:0;z-index:3;min-width:90px;}.required-table td{text-align:center;}"
            + ".required-table input{width:72px;height:38px;padding:0 8px;border:1px solid #cbd6e2;border-radius:6px;text-align:center;}"
            + ".request-submit-form{margin-top:18px;}"
            + ".request-top{position:sticky;top:0;z-index:10;display:flex;justify-content:space-between;align-items:center;background:#0d315a;color:#fff;padding:14px 18px;border-radius:6px;margin-bottom:14px;}"
            + ".request-top strong{display:block;font-size:18px;}.request-top span{display:block;color:#d7e7f7;margin-top:4px;}"
            + ".total-box{font-size:18px;font-weight:700;background:#2f7bc1;padding:10px 16px;border-radius:6px;}"
            + ".request-days{display:flex;flex-direction:column;border:1px solid #d5e0ea;border-radius:6px;overflow:hidden;background:#fff;}"
            + ".request-day{border-bottom:1px solid #dfe7ef;padding:18px 18px 20px;}.request-day:last-child{border-bottom:0;}"
            + ".request-date strong{font-size:26px;}"
            + ".request-controls{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin-top:18px;}"
            + ".radio-card{display:inline-flex;align-items:center;gap:6px;border:1px solid #cbd6e2;background:#fff;border-radius:6px;padding:10px 12px;font-weight:700;}"
            + ".time-selects{display:flex;align-items:center;gap:8px;margin-left:6px;}"
            + ".time-selects select{height:40px;border:1px solid #cbd6e2;border-radius:6px;padding:0 8px;background:#fff;}"
            + ".submit-area{display:flex;justify-content:center;padding:24px 0;}"
            + ".submit-button{height:54px;min-width:240px;border:0;border-radius:999px;background:#247bd1;color:#fff;font-size:20px;font-weight:700;cursor:pointer;}"
            + ".shift-card{overflow-x:auto;padding:0;}.shift-card h2{padding:16px 18px;background:#eef6fc;border-bottom:1px solid #d5e0ea;margin:0;}"
            + ".shift-chart{min-width:1180px;}.shift-header,.shift-row{display:grid;grid-template-columns:180px 936px;}"
            + ".name-cell{padding:14px 12px;border-right:1px solid #d5e0ea;border-bottom:1px solid #e4ebf2;font-weight:700;background:#fff;}"
            + ".shift-header .name-cell{background:#f8fafc;}.hour-area{display:grid;grid-template-columns:repeat(13,72px);border-bottom:1px solid #d5e0ea;background:#f8fafc;}"
            + ".hour-area div{padding:13px 0;text-align:center;font-weight:700;border-right:1px solid #d5e0ea;font-size:14px;}"
            + ".shift-line{position:relative;display:grid;grid-template-columns:repeat(26,36px);min-height:48px;border-bottom:1px solid #e4ebf2;background:repeating-linear-gradient(to right,#fff 0,#fff 35px,#dfe7ef 36px);}"
            + ".shift-bar{align-self:center;height:32px;line-height:32px;background:#2780d8;color:#fff;border-radius:6px;text-align:center;font-size:14px;font-weight:700;overflow:hidden;white-space:nowrap;}"
            + ".login-page{min-height:100vh;display:grid;place-items:center;padding:20px;}"
            + ".login-card{width:min(420px,100%);background:#fff;border:1px solid #d5e0ea;border-radius:10px;padding:28px;}"
            + ".login-card label{display:flex;flex-direction:column;gap:8px;margin:0 0 18px;font-weight:700;}"
            + ".login-card input{height:46px;padding:0 12px;border:1px solid #cbd6e2;border-radius:6px;font-size:16px;}"
            + ".login-card button{width:100%;height:48px;border:0;border-radius:6px;background:#247bd1;color:#fff;font-size:17px;font-weight:700;cursor:pointer;}"
            + ".password-panel{max-width:520px;margin-left:auto;margin-right:auto;}"
            + ".password-form{display:flex;flex-direction:column;gap:18px;}"
            + ".password-form label{display:flex;flex-direction:column;gap:8px;font-weight:700;}"
            + ".password-form input{height:46px;padding:0 12px;border:1px solid #cbd6e2;border-radius:6px;font-size:16px;}"
            + ".generate-panel{max-width:680px;margin-left:auto;margin-right:auto;}"
            + ".generate-form{display:grid;grid-template-columns:1fr 1fr;gap:18px;align-items:end;}"
            + ".generate-form label{display:flex;flex-direction:column;gap:8px;font-weight:700;}"
            + ".generate-form input{height:46px;padding:0 12px;border:1px solid #cbd6e2;border-radius:6px;font-size:16px;}"
            + ".generate-form .submit-button{grid-column:1/-1;justify-self:center;}"
            + "pre{white-space:pre-wrap;background:#0f172a;color:#e5e7eb;padding:16px;border-radius:6px;overflow:auto;}"
            + "@media(max-width:600px){"
            + "*,*::before,*::after{box-sizing:border-box;}"
            + "body{font-size:15px;overflow-x:hidden;}"
            + ".wrap{width:100%;padding:16px 10px 40px;}"
            + "h1{font-size:24px;margin-bottom:20px;}h2{font-size:18px;}"
            + ".nav{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin-bottom:20px;}"
            + ".nav a{width:100%;height:44px;padding:0 6px;font-size:14px;text-align:center;}"
            + ".nav-user{grid-column:1/-1;margin-left:0;justify-content:space-between;background:#fff;padding:8px 10px;border:1px solid #ccd8e4;border-radius:6px;}"
            + ".logout-button{height:38px;}"
            + ".panel{padding:12px;margin-bottom:18px;overflow:hidden;}"
            + ".home-grid{grid-template-columns:1fr;}.home-card{padding:15px;}"
            + "table{display:block;width:100%;min-width:560px;overflow-x:auto;white-space:nowrap;-webkit-overflow-scrolling:touch;}"
            + "th,td{padding:10px 9px;}"
            + ".primary-link{width:100%;justify-content:center;}"
            + ".filter-form{display:grid;grid-template-columns:1fr;align-items:stretch;}"
            + ".filter-form input,.filter-form select,.filter-form button{width:100%;font-size:16px;}"
            + ".admin-form,.inline-form{display:grid;grid-template-columns:1fr;align-items:stretch;}"
            + ".admin-form input,.inline-form input,.admin-form button,.inline-form button{width:100%;min-width:0;font-size:16px;}"
            + ".form-grid,.level-grid{grid-template-columns:1fr;}"
            + ".generate-form{grid-template-columns:1fr;}.generate-form .submit-button{grid-column:1;width:100%;}"
            + ".form-grid input,.form-grid select,.level-grid input{width:100%;font-size:16px;}"
            + ".form-actions{display:grid;grid-template-columns:1fr;gap:8px;}.cancel-link{justify-content:center;order:2;}"
            + ".submit-button{width:100%;min-width:0;font-size:18px;}"
            + ".required-table-wrap{max-height:65vh;-webkit-overflow-scrolling:touch;}.required-table{display:table;min-width:620px;}"
            + ".request-top{position:static;align-items:stretch;gap:12px;padding:12px;flex-direction:column;}"
            + ".total-box{text-align:center;}.request-day{padding:14px 10px 16px;}.request-date strong{font-size:21px;}"
            + ".request-controls{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;}"
            + ".radio-card{justify-content:center;padding:11px 6px;font-size:14px;}"
            + ".time-selects{grid-column:1/-1;width:100%;margin-left:0;justify-content:center;}"
            + ".time-selects select{flex:1;min-width:0;font-size:16px;}"
            + ".submit-area{padding:18px 0;}"
            + ".shift-card{margin-bottom:18px;}.shift-card h2{padding:13px 12px;}"
            + ".shift-card .name-cell{position:sticky;left:0;z-index:2;}"
            + "input,select,button{font-size:16px;}"
            + "}"
            + "</style>"
            + "</head>"
            + "<body><main class='wrap'>"
            + nav()
            + body
            + "</main></body></html>";
    }

    private static String nav() {
        String username = CURRENT_USERNAME.get();
        return "<nav class='nav'>"
            + "<a href='/'>ホーム</a>"
            + "<a class='primary' href='/generate'>シフト自動生成</a>"
            + "<a href='/shifts'>シフト結果</a>"
            + "<a href='/daily'>日付別</a>"
            + "<a href='/stats'>集計</a>"
            + "<a href='/shortage'>不足</a>"
            + "<a href='/requests'>希望</a>"
            + "<a href='/request-form'>希望入力</a>"
            + "<a href='/employees'>従業員</a>"
            + "<a href='/positions'>ポジション</a>"
            + "<a href='/required-staff'>必要人数</a>"
            + "<a href='/password'>パスワード変更</a>"
            + "<span class='nav-user'><span>ログイン中: " + escape(username) + "</span>"
            + "<form method='post' action='/logout'><button class='logout-button' type='submit'>ログアウト</button></form></span>"
            + "</nav>";
    }

    private static void send(HttpExchange exchange, String html) throws Exception {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws Exception {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static String displayType(String type) {
        if (type == null) {
            return "";
        }

        switch (type) {
            case "FULL_TIME":
                return "社員";
            case "PART_TIME":
                return "アルバイト";
            case "NEWBIE":
                return "新人";
            default:
                return type;
        }
    }

    private static String dayOfWeek(LocalDate date) {
        switch (date.getDayOfWeek()) {
            case MONDAY:
                return "月";
            case TUESDAY:
                return "火";
            case WEDNESDAY:
                return "水";
            case THURSDAY:
                return "木";
            case FRIDAY:
                return "金";
            case SATURDAY:
                return "土";
            case SUNDAY:
                return "日";
            default:
                return "";
        }
    }

    private static String formatTime(Time time) {
        if (time == null) {
            return "";
        }

        return formatTime(time.toLocalTime());
    }

    private static String formatTime(LocalTime time) {
        if (time == null) {
            return "";
        }

        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private static String formatTimeRange(LocalTime start, LocalTime end) {
        return formatShortTime(start) + "-" + formatShortTime(end);
    }

    private static String formatShortTime(LocalTime time) {
        if (time.getMinute() == 0) {
            return String.valueOf(time.getHour());
        }

        return String.format("%d:%02d", time.getHour(), time.getMinute());
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }

        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static String stackTraceToText(Exception e) {
        StringBuilder builder = new StringBuilder();
        builder.append(e.toString()).append("\n");

        for (StackTraceElement element : e.getStackTrace()) {
            builder.append("    at ").append(element.toString()).append("\n");
        }

        return builder.toString();
    }

    private static class Session {
        String username;
        Instant expiresAt;

        Session(String username, Instant expiresAt) {
            this.username = username;
            this.expiresAt = expiresAt;
        }
    }

    private static class DateRange {
        LocalDate start;
        LocalDate end;

        DateRange(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }
    }

    private static class EmployeeOption {
        int employeeId;
        String name;
        String employmentType;

        EmployeeOption(int employeeId, String name, String employmentType) {
            this.employeeId = employeeId;
            this.name = name;
            this.employmentType = employmentType;
        }
    }

    private static class RequestItem {
        String start;
        String end;

        RequestItem(String start, String end) {
            this.start = start;
            this.end = end;
        }
    }

    private static class ShiftBlock {
        LocalDate workDate;
        String name;
        LocalTime startTime;
        LocalTime endTime;

        ShiftBlock(LocalDate workDate, String name, LocalTime startTime, LocalTime endTime) {
            this.workDate = workDate;
            this.name = name;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    private static class DailyShift {
        int employeeId;
        String name;
        LocalTime start;
        LocalTime end;
        List<String> positions = new ArrayList<>();

        DailyShift(
            int employeeId,
            String name,
            LocalTime start,
            LocalTime end,
            String positionName
        ) {
            this.employeeId = employeeId;
            this.name = name;
            this.start = start;
            this.end = end;
            this.positions.add(positionName);
        }

        void addPosition(String positionName) {
            if (!positions.get(positions.size() - 1).equals(positionName)) {
                positions.add(positionName);
            }
        }

        String getPositionSummary() {
            return String.join(" → ", positions);
        }
    }

    private static class ShortageItem {
        LocalDate date;
        LocalTime start;
        LocalTime end;
        int positionId;
        String positionName;
        int shortageCount;

        ShortageItem(LocalDate date, LocalTime start, LocalTime end, int positionId, String positionName, int shortageCount) {
            this.date = date;
            this.start = start;
            this.end = end;
            this.positionId = positionId;
            this.positionName = positionName;
            this.shortageCount = shortageCount;
        }
    }
}
