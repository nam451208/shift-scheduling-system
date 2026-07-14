import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Time;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

public class ShiftGenerator {

    private static final LocalTime OPEN_TIME = LocalTime.of(10, 0);
    private static final LocalTime CLOSE_TIME = LocalTime.of(22, 0);
    private static final LocalTime NEWBIE_LIMIT_TIME = LocalTime.of(21, 0);

    private static final int FULL_TIME_MIN_SLOTS = 16;
    private static final int FULL_TIME_LONG_SLOTS = 24;
    private static final int PART_TIME_MIN_SLOTS = 8;
    private static final int NEWBIE_MIN_SLOTS = 6;

    public static void main(String[] args) {

        try {
            generateShift();
        } catch (Exception e) {
            System.out.println("シフト自動生成中にエラーが発生しました。");
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    private static void generateShift() throws Exception {

        LocalDate startDate = getStartDate();

        if (startDate == null) {
            System.out.println("希望シフトが登録されていません。");
            return;
        }

        generateShift(startDate, startDate.plusDays(14));
    }

    public static void generateShift(LocalDate startDate, LocalDate endDate) throws Exception {

        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("シフト期間が正しくありません。");
        }

        deleteOldShift(startDate, endDate);

        LocalDate workDate = startDate;

        while (!workDate.isAfter(endDate)) {

            LocalTime timeSlot = OPEN_TIME;

            while (timeSlot.isBefore(CLOSE_TIME)) {

                createShift(workDate, timeSlot, startDate, endDate);
                enforceBusinessRule(workDate, timeSlot, startDate, endDate);

                timeSlot = addThirtyMinutes(timeSlot);
            }

            workDate = workDate.plusDays(1);
        }

        extendShortShifts(startDate, endDate);
        printShortage(startDate, endDate);

        System.out.println("シフト自動生成が完了しました。");
        System.out.println(startDate + " から " + endDate + " まで作成しました。");
    }

    private static LocalDate getStartDate() throws Exception {

        String sql =
            "SELECT MIN(work_date) AS start_date " +
            "FROM request_shift " +
            "WHERE is_submitted = true";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet resultSet = statement.executeQuery()
        ) {
            if (resultSet.next() && resultSet.getDate("start_date") != null) {
                return resultSet.getDate("start_date").toLocalDate();
            }
        }

        return null;
    }

    private static void deleteOldShift(LocalDate startDate, LocalDate endDate) throws Exception {

        String sql =
            "DELETE FROM work_shift " +
            "WHERE work_date BETWEEN ? AND ?";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setDate(1, Date.valueOf(startDate));
            statement.setDate(2, Date.valueOf(endDate));
            statement.executeUpdate();
        }
    }

    private static void createShift(
        LocalDate workDate,
        LocalTime timeSlot,
        LocalDate periodStartDate,
        LocalDate periodEndDate
    ) throws Exception {

        String dayType = getDayType(workDate);
        LocalTime nextTime = addThirtyMinutes(timeSlot);

        String requiredSql =
            "SELECT position_id, required_count " +
            "FROM required_staff " +
            "WHERE day_type = ? " +
            "AND time_slot = ? " +
            "ORDER BY position_id";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement requiredStmt = connection.prepareStatement(requiredSql)
        ) {
            requiredStmt.setString(1, dayType);
            requiredStmt.setTime(2, Time.valueOf(timeSlot));

            try (ResultSet requiredRs = requiredStmt.executeQuery()) {

                while (requiredRs.next()) {

                    int positionId = requiredRs.getInt("position_id");
                    int requiredCount = requiredRs.getInt("required_count");

                    int assignedCount = getAssignedCount(
                        connection,
                        workDate,
                        timeSlot,
                        positionId
                    );

                    while (assignedCount < requiredCount) {

                        Candidate candidate = getBestCandidate(
                            connection,
                            workDate,
                            timeSlot,
                            nextTime,
                            positionId,
                            periodStartDate,
                            periodEndDate
                        );

                        if (candidate == null) {
                            break;
                        }

                        insertShift(
                            connection,
                            candidate.employeeId,
                            workDate,
                            timeSlot,
                            nextTime,
                            candidate.positionId
                        );

                        assignedCount++;
                    }
                }
            }
        }
    }

    private static void enforceBusinessRule(
        LocalDate workDate,
        LocalTime timeSlot,
        LocalDate periodStartDate,
        LocalDate periodEndDate
    ) throws Exception {

        try (Connection connection = DBConnection.getConnection()) {

            int safetyCount = 0;

            while (safetyCount < 10) {

                SlotStatus status = getSlotStatus(connection, workDate, timeSlot);

                if (isBusinessRuleSatisfied(workDate, timeSlot, status)) {
                    return;
                }

                Candidate candidate = getBestCandidate(
                    connection,
                    workDate,
                    timeSlot,
                    addThirtyMinutes(timeSlot),
                    null,
                    periodStartDate,
                    periodEndDate
                );

                if (candidate == null) {
                    return;
                }

                insertShift(
                    connection,
                    candidate.employeeId,
                    workDate,
                    timeSlot,
                    addThirtyMinutes(timeSlot),
                    candidate.positionId
                );

                safetyCount++;
            }
        }
    }

    private static boolean isBusinessRuleSatisfied(
        LocalDate workDate,
        LocalTime timeSlot,
        SlotStatus status
    ) {

        String dayType = getDayType(workDate);

        if ("平日".equals(dayType)) {
            return isWeekdayRuleSatisfied(timeSlot, status);
        }

        if ("金".equals(dayType)) {
            return isFridayRuleSatisfied(timeSlot, status);
        }

        return isWeekendRuleSatisfied(workDate, timeSlot, status);
    }

    private static boolean isWeekdayRuleSatisfied(
        LocalTime timeSlot,
        SlotStatus status
    ) {

        if (isBetween(timeSlot, 10, 11)) {
            return status.totalCount >= 2;
        }

        if (isBetween(timeSlot, 11, 14)) {
            if (status.fullTimeCount > 0) {
                return status.totalCount >= 2;
            }

            return status.levelSum >= 6;
        }

        if (isBetween(timeSlot, 14, 22)) {
            if (status.fullTimeCount > 0) {
                return status.totalCount >= 2;
            }

            return status.totalCount >= 2 && status.levelSum >= 5;
        }

        return true;
    }

    private static boolean isFridayRuleSatisfied(
        LocalTime timeSlot,
        SlotStatus status
    ) {

        if (isBetween(timeSlot, 10, 18)) {
            return isWeekdayRuleSatisfied(timeSlot, status);
        }

        if (isBetween(timeSlot, 18, 21)) {
            if (status.fullTimeCount > 0) {
                return status.totalCount >= 2 && status.nonFullTimeLevelSum >= 2;
            }

            return status.totalCount >= 2 && status.levelSum >= 6;
        }

        if (isBetween(timeSlot, 21, 22)) {
            return status.totalCount >= 2;
        }

        return true;
    }

    private static boolean isWeekendRuleSatisfied(
        LocalDate workDate,
        LocalTime timeSlot,
        SlotStatus status
    ) {

        boolean nextDayRest = isNextDayRest(workDate);

        if (status.fullTimeCount > 0) {

            if (isBetween(timeSlot, 10, 11)) {
                return status.totalCount >= 2;
            }

            if (isBetween(timeSlot, 11, 15)) {
                return status.totalCount >= 4 && status.nonFullTimeLevelSum >= 7;
            }

            if (isBetween(timeSlot, 15, 16)) {
                return status.totalCount >= 3;
            }

            if (isBetween(timeSlot, 16, 21)) {
                if (nextDayRest) {
                    return status.nonFullTimeLevelSum >= 4;
                }

                return status.nonFullTimeLevelSum >= 3;
            }

            if (isBetween(timeSlot, 21, 22)) {
                return status.totalCount >= 2;
            }

            return true;
        }

        if (isBetween(timeSlot, 10, 11)) {
            return status.totalCount >= 2;
        }

        if (isBetween(timeSlot, 11, 15)) {
            return status.totalCount >= 5 && status.levelSum >= 10;
        }

        if (isBetween(timeSlot, 15, 16)) {
            return status.totalCount >= 3;
        }

        if (isBetween(timeSlot, 16, 21)) {
            if (nextDayRest) {
                return status.levelSum >= 7;
            }

            return status.levelSum >= 6;
        }

        if (isBetween(timeSlot, 21, 22)) {
            return status.totalCount >= 2;
        }

        return true;
    }

    private static Candidate getBestCandidate(
        Connection connection,
        LocalDate workDate,
        LocalTime timeSlot,
        LocalTime nextTime,
        Integer targetPositionId,
        LocalDate periodStartDate,
        LocalDate periodEndDate
    ) throws Exception {

        String candidateSql =
            "SELECT " +
            "r.employee_id, " +
            "e.employment_type, " +
            "er.monthly_min_hours, " +
            "ep.position_id, " +
            "ep.position_level, " +
            "r.end_time AS request_end_time, " +

            "(SELECT COUNT(*) " +
            " FROM work_shift ws1 " +
            " WHERE ws1.employee_id = r.employee_id " +
            " AND ws1.work_date = r.work_date) AS daily_work_count, " +

            "(SELECT COUNT(DISTINCT ws1d.work_date) " +
            " FROM work_shift ws1d " +
            " WHERE ws1d.employee_id = r.employee_id " +
            " AND ws1d.work_date BETWEEN ? AND ?) AS period_work_days, " +

            "(SELECT COUNT(*) " +
            " FROM work_shift ws2 " +
            " WHERE ws2.employee_id = r.employee_id " +
            " AND ws2.work_date BETWEEN ? AND ?) AS period_work_count, " +

            "(SELECT COUNT(*) " +
            " FROM work_shift ws3 " +
            " WHERE ws3.employee_id = r.employee_id " +
            " AND ws3.work_date = r.work_date " +
            " AND ws3.end_time = ?) AS continue_count, " +

            "(SELECT COUNT(*) " +
            " FROM work_shift ws4 " +
            " WHERE ws4.employee_id = r.employee_id " +
            " AND ws4.work_date = r.work_date " +
            " AND ws4.position_id = ep.position_id " +
            " AND ws4.end_time = ?) AS same_position_continue_count, " +

            "(SELECT COALESCE(SUM(TIMESTAMPDIFF(MINUTE, rs.start_time, rs.end_time) / 30), 0) " +
            " FROM request_shift rs " +
            " WHERE rs.employee_id = r.employee_id " +
            " AND rs.work_date BETWEEN ? AND ? " +
            " AND rs.is_submitted = true) AS period_request_slots, " +

            "(TIMESTAMPDIFF(MINUTE, r.start_time, r.end_time) / 30) AS request_slots " +

            "FROM request_shift r " +
            "JOIN employees e ON r.employee_id = e.employee_id " +
            "JOIN employment_rules er ON e.employment_type = er.employment_type " +
            "JOIN employee_position ep ON r.employee_id = ep.employee_id " +

            "WHERE r.work_date = ? " +
            "AND r.is_submitted = true " +
            "AND e.is_active = true " +
            "AND (? IS NULL OR ep.position_id = ?) " +
            "AND r.start_time <= ? " +
            "AND r.end_time >= ? " +

            "AND NOT EXISTS ( " +
            "    SELECT 1 FROM employee_day_off edo " +
            "    WHERE edo.employee_id = r.employee_id " +
            "    AND edo.off_date = r.work_date " +
            "    AND ( " +
            "        edo.start_time IS NULL " +
            "        OR edo.end_time IS NULL " +
            "        OR (edo.start_time < ? AND edo.end_time > ?) " +
            "    ) " +
            ") " +

            "AND NOT EXISTS ( " +
            "    SELECT 1 FROM work_shift already " +
            "    WHERE already.employee_id = r.employee_id " +
            "    AND already.work_date = r.work_date " +
            "    AND already.start_time = ? " +
            ") " +

            "AND NOT EXISTS ( " +
            "    SELECT 1 FROM work_shift same_time " +
            "    WHERE same_time.employee_id = r.employee_id " +
            "    AND same_time.work_date = r.work_date " +
            "    AND same_time.start_time < ? " +
            "    AND same_time.end_time > ? " +
            ") " +

            "AND NOT ( " +
            "    e.employment_type = 'NEWBIE' " +
            "    AND ? >= ? " +
            ") " +

            "AND NOT ( " +
            "    EXISTS ( " +
            "        SELECT 1 FROM work_shift before_gap " +
            "        WHERE before_gap.employee_id = r.employee_id " +
            "        AND before_gap.work_date = r.work_date " +
            "        AND before_gap.end_time < ? " +
            "    ) " +
            "    AND NOT EXISTS ( " +
            "        SELECT 1 FROM work_shift continue_work " +
            "        WHERE continue_work.employee_id = r.employee_id " +
            "        AND continue_work.work_date = r.work_date " +
            "        AND continue_work.end_time = ? " +
            "    ) " +
            ") " +

            "ORDER BY r.work_date, " +

            "CASE " +
            "    WHEN daily_work_count > 0 " +
            "    AND daily_work_count < " +
            "        CASE " +
            "            WHEN e.employment_type = 'FULL_TIME' " +
            "            AND period_work_count < er.monthly_min_hours THEN LEAST(?, request_slots) " +
            "            WHEN e.employment_type = 'FULL_TIME' THEN LEAST(?, request_slots) " +
            "            WHEN e.employment_type = 'NEWBIE' THEN LEAST(?, request_slots) " +
            "            WHEN e.employment_type = 'PART_TIME' THEN LEAST(?, request_slots) " +
            "            ELSE daily_work_count + 1 " +
            "        END " +
            "    THEN 0 " +
            "    ELSE 1 " +
            "END ASC, " +

            "continue_count DESC, " +
            "same_position_continue_count DESC, " +

            "CASE " +
            "    WHEN er.monthly_min_hours > 0 " +
            "    AND period_work_count < er.monthly_min_hours THEN 0 " +
            "    ELSE 1 " +
            "END ASC, " +

            "period_work_days ASC, " +
            "period_work_count ASC, " +
            "period_request_slots DESC, " +
            "daily_work_count ASC, " +
            "ep.position_level DESC, " +
            "r.employee_id, " +
            "ep.position_id";

        try (PreparedStatement candidateStmt = connection.prepareStatement(candidateSql)) {

            int index = 1;

            candidateStmt.setDate(index++, Date.valueOf(periodStartDate));
            candidateStmt.setDate(index++, Date.valueOf(periodEndDate));

            candidateStmt.setDate(index++, Date.valueOf(periodStartDate));
            candidateStmt.setDate(index++, Date.valueOf(periodEndDate));

            candidateStmt.setTime(index++, Time.valueOf(timeSlot));
            candidateStmt.setTime(index++, Time.valueOf(timeSlot));

            candidateStmt.setDate(index++, Date.valueOf(periodStartDate));
            candidateStmt.setDate(index++, Date.valueOf(periodEndDate));

            candidateStmt.setDate(index++, Date.valueOf(workDate));

            if (targetPositionId == null) {
                candidateStmt.setNull(index++, Types.INTEGER);
                candidateStmt.setNull(index++, Types.INTEGER);
            } else {
                candidateStmt.setInt(index++, targetPositionId);
                candidateStmt.setInt(index++, targetPositionId);
            }

            candidateStmt.setTime(index++, Time.valueOf(timeSlot));
            candidateStmt.setTime(index++, Time.valueOf(nextTime));

            candidateStmt.setTime(index++, Time.valueOf(nextTime));
            candidateStmt.setTime(index++, Time.valueOf(timeSlot));

            candidateStmt.setTime(index++, Time.valueOf(timeSlot));

            candidateStmt.setTime(index++, Time.valueOf(nextTime));
            candidateStmt.setTime(index++, Time.valueOf(timeSlot));

            candidateStmt.setTime(index++, Time.valueOf(timeSlot));
            candidateStmt.setTime(index++, Time.valueOf(NEWBIE_LIMIT_TIME));

            candidateStmt.setTime(index++, Time.valueOf(timeSlot));
            candidateStmt.setTime(index++, Time.valueOf(timeSlot));

            candidateStmt.setInt(index++, FULL_TIME_LONG_SLOTS);
            candidateStmt.setInt(index++, FULL_TIME_MIN_SLOTS);
            candidateStmt.setInt(index++, NEWBIE_MIN_SLOTS);
            candidateStmt.setInt(index++, PART_TIME_MIN_SLOTS);

            try (ResultSet resultSet = candidateStmt.executeQuery()) {

                while (resultSet.next()) {

                    Candidate candidate = new Candidate();

                    candidate.employeeId = resultSet.getInt("employee_id");
                    candidate.employmentType = resultSet.getString("employment_type");
                    candidate.monthlyMinHours = resultSet.getInt("monthly_min_hours");
                    candidate.positionId = resultSet.getInt("position_id");
                    candidate.dailyWorkCount = resultSet.getInt("daily_work_count");
                    candidate.periodWorkCount = resultSet.getInt("period_work_count");
                    candidate.requestSlots = resultSet.getInt("request_slots");
                    candidate.requestEndTime = resultSet.getTime("request_end_time").toLocalTime();

                    if (canAssignCandidate(
                        candidate,
                        timeSlot,
                        nextTime
                    )) {
                        if ("FULL_TIME".equals(candidate.employmentType) &&
                            shouldAvoidFullTime(
                                connection,
                                candidate,
                                workDate,
                                timeSlot,
                                nextTime,
                                targetPositionId
                            )) {
                            continue;
                        }

                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    private static boolean shouldAvoidFullTime(
        Connection connection,
        Candidate candidate,
        LocalDate workDate,
        LocalTime timeSlot,
        LocalTime nextTime,
        Integer targetPositionId
    ) throws Exception {

        boolean alreadyWorkedEightHours = candidate.dailyWorkCount >= FULL_TIME_MIN_SLOTS;
        boolean alreadyReachedPeriodGoal =
            candidate.monthlyMinHours > 0 &&
            candidate.periodWorkCount >= candidate.monthlyMinHours;

        if (!alreadyWorkedEightHours && !alreadyReachedPeriodGoal) {
            return false;
        }

        return hasNonFullTimeAlternative(
            connection,
            workDate,
            timeSlot,
            nextTime,
            targetPositionId
        );
    }

    private static boolean hasNonFullTimeAlternative(
        Connection connection,
        LocalDate workDate,
        LocalTime timeSlot,
        LocalTime nextTime,
        Integer targetPositionId
    ) throws Exception {

        String sql =
            "SELECT " +
            "r.employee_id, " +
            "e.employment_type, " +
            "ep.position_id, " +
            "r.end_time AS request_end_time, " +

            "(SELECT COUNT(*) " +
            " FROM work_shift ws1 " +
            " WHERE ws1.employee_id = r.employee_id " +
            " AND ws1.work_date = r.work_date) AS daily_work_count, " +

            "(TIMESTAMPDIFF(MINUTE, r.start_time, r.end_time) / 30) AS request_slots " +

            "FROM request_shift r " +
            "JOIN employees e ON r.employee_id = e.employee_id " +
            "JOIN employee_position ep ON r.employee_id = ep.employee_id " +

            "WHERE r.work_date = ? " +
            "AND r.is_submitted = true " +
            "AND e.is_active = true " +
            "AND e.employment_type <> 'FULL_TIME' " +
            "AND (? IS NULL OR ep.position_id = ?) " +
            "AND r.start_time <= ? " +
            "AND r.end_time >= ? " +

            "AND NOT EXISTS ( " +
            "    SELECT 1 FROM employee_day_off edo " +
            "    WHERE edo.employee_id = r.employee_id " +
            "    AND edo.off_date = r.work_date " +
            "    AND ( " +
            "        edo.start_time IS NULL " +
            "        OR edo.end_time IS NULL " +
            "        OR (edo.start_time < ? AND edo.end_time > ?) " +
            "    ) " +
            ") " +

            "AND NOT EXISTS ( " +
            "    SELECT 1 FROM work_shift already " +
            "    WHERE already.employee_id = r.employee_id " +
            "    AND already.work_date = r.work_date " +
            "    AND already.start_time = ? " +
            ") " +

            "AND NOT EXISTS ( " +
            "    SELECT 1 FROM work_shift same_time " +
            "    WHERE same_time.employee_id = r.employee_id " +
            "    AND same_time.work_date = r.work_date " +
            "    AND same_time.start_time < ? " +
            "    AND same_time.end_time > ? " +
            ") " +

            "AND NOT ( " +
            "    e.employment_type = 'NEWBIE' " +
            "    AND ? >= ? " +
            ") " +

            "AND NOT ( " +
            "    EXISTS ( " +
            "        SELECT 1 FROM work_shift before_gap " +
            "        WHERE before_gap.employee_id = r.employee_id " +
            "        AND before_gap.work_date = r.work_date " +
            "        AND before_gap.end_time < ? " +
            "    ) " +
            "    AND NOT EXISTS ( " +
            "        SELECT 1 FROM work_shift continue_work " +
            "        WHERE continue_work.employee_id = r.employee_id " +
            "        AND continue_work.work_date = r.work_date " +
            "        AND continue_work.end_time = ? " +
            "    ) " +
            ") " +
            "ORDER BY e.employment_type, r.employee_id, ep.position_id";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            int index = 1;

            statement.setDate(index++, Date.valueOf(workDate));

            if (targetPositionId == null) {
                statement.setNull(index++, Types.INTEGER);
                statement.setNull(index++, Types.INTEGER);
            } else {
                statement.setInt(index++, targetPositionId);
                statement.setInt(index++, targetPositionId);
            }

            statement.setTime(index++, Time.valueOf(timeSlot));
            statement.setTime(index++, Time.valueOf(nextTime));

            statement.setTime(index++, Time.valueOf(nextTime));
            statement.setTime(index++, Time.valueOf(timeSlot));

            statement.setTime(index++, Time.valueOf(timeSlot));

            statement.setTime(index++, Time.valueOf(nextTime));
            statement.setTime(index++, Time.valueOf(timeSlot));

            statement.setTime(index++, Time.valueOf(timeSlot));
            statement.setTime(index++, Time.valueOf(NEWBIE_LIMIT_TIME));

            statement.setTime(index++, Time.valueOf(timeSlot));
            statement.setTime(index++, Time.valueOf(timeSlot));

            try (ResultSet resultSet = statement.executeQuery()) {

                while (resultSet.next()) {

                    Candidate alternative = new Candidate();

                    alternative.employeeId = resultSet.getInt("employee_id");
                    alternative.employmentType = resultSet.getString("employment_type");
                    alternative.positionId = resultSet.getInt("position_id");
                    alternative.dailyWorkCount = resultSet.getInt("daily_work_count");
                    alternative.requestSlots = resultSet.getInt("request_slots");
                    alternative.requestEndTime = resultSet.getTime("request_end_time").toLocalTime();

                    if (canAssignCandidate(
                        alternative,
                        timeSlot,
                        nextTime
                    )) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean canAssignCandidate(
        Candidate candidate,
        LocalTime timeSlot,
        LocalTime nextTime
    ) {

        if ("FULL_TIME".equals(candidate.employmentType)) {
            return canAssignFullTime(candidate, nextTime);
        }

        if ("PART_TIME".equals(candidate.employmentType)) {
            return canAssignPartTime(candidate, nextTime);
        }

        if ("NEWBIE".equals(candidate.employmentType)) {
            return canAssignNewbie(candidate, timeSlot, nextTime);
        }

        return true;
    }

    private static boolean canAssignFullTime(
        Candidate candidate,
        LocalTime nextTime
    ) {

        LocalTime limitTime = minTime(candidate.requestEndTime, CLOSE_TIME);

        int possibleSlots = candidate.dailyWorkCount + countSlots(nextTime, limitTime);
        int requiredSlots = Math.min(
            FULL_TIME_MIN_SLOTS,
            Math.min(candidate.requestSlots, possibleSlots)
        );

        int afterAssignSlots = candidate.dailyWorkCount + 1;
        int remainingSlots = countSlots(nextTime, limitTime);

        if (afterAssignSlots >= requiredSlots) {
            return true;
        }

        return afterAssignSlots + remainingSlots >= requiredSlots;
    }

    private static boolean canAssignPartTime(
        Candidate candidate,
        LocalTime nextTime
    ) {

        LocalTime limitTime = minTime(candidate.requestEndTime, CLOSE_TIME);

        int requiredSlots = Math.min(PART_TIME_MIN_SLOTS, candidate.requestSlots);
        int afterAssignSlots = candidate.dailyWorkCount + 1;
        int remainingSlots = countSlots(nextTime, limitTime);

        if (afterAssignSlots >= requiredSlots) {
            return true;
        }

        return afterAssignSlots + remainingSlots >= requiredSlots;
    }

    private static boolean canAssignNewbie(
        Candidate candidate,
        LocalTime timeSlot,
        LocalTime nextTime
    ) {

        if (!timeSlot.isBefore(NEWBIE_LIMIT_TIME)) {
            return false;
        }

        LocalTime limitTime = minTime(candidate.requestEndTime, NEWBIE_LIMIT_TIME);

        int requiredSlots = Math.min(NEWBIE_MIN_SLOTS, candidate.requestSlots);
        int afterAssignSlots = candidate.dailyWorkCount + 1;
        int remainingSlots = countSlots(nextTime, limitTime);

        if (afterAssignSlots >= requiredSlots) {
            return true;
        }

        return afterAssignSlots + remainingSlots >= requiredSlots;
    }

    private static SlotStatus getSlotStatus(
        Connection connection,
        LocalDate workDate,
        LocalTime timeSlot
    ) throws Exception {

        String sql =
            "SELECT " +
            "COUNT(*) AS total_count, " +
            "COALESCE(SUM(CASE WHEN e.employment_type = 'FULL_TIME' THEN 1 ELSE 0 END), 0) AS full_time_count, " +
            "COALESCE(SUM(ep.position_level), 0) AS level_sum, " +
            "COALESCE(SUM(CASE WHEN e.employment_type <> 'FULL_TIME' THEN ep.position_level ELSE 0 END), 0) AS non_full_time_level_sum " +
            "FROM work_shift ws " +
            "JOIN employees e ON ws.employee_id = e.employee_id " +
            "JOIN employee_position ep " +
            "ON ep.employee_id = ws.employee_id " +
            "AND ep.position_id = ws.position_id " +
            "WHERE ws.work_date = ? " +
            "AND ws.start_time = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setDate(1, Date.valueOf(workDate));
            statement.setTime(2, Time.valueOf(timeSlot));

            try (ResultSet resultSet = statement.executeQuery()) {

                if (resultSet.next()) {

                    SlotStatus status = new SlotStatus();

                    status.totalCount = resultSet.getInt("total_count");
                    status.fullTimeCount = resultSet.getInt("full_time_count");
                    status.levelSum = resultSet.getInt("level_sum");
                    status.nonFullTimeLevelSum = resultSet.getInt("non_full_time_level_sum");

                    return status;
                }
            }
        }

        return new SlotStatus();
    }

    private static int getAssignedCount(
        Connection connection,
        LocalDate workDate,
        LocalTime timeSlot,
        int positionId
    ) throws Exception {

        String sql =
            "SELECT COUNT(*) AS count " +
            "FROM work_shift " +
            "WHERE work_date = ? " +
            "AND start_time = ? " +
            "AND position_id = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setDate(1, Date.valueOf(workDate));
            statement.setTime(2, Time.valueOf(timeSlot));
            statement.setInt(3, positionId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("count");
                }
            }
        }

        return 0;
    }

    private static void insertShift(
        Connection connection,
        int employeeId,
        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime,
        int positionId
    ) throws Exception {

        String sql =
            "INSERT INTO work_shift " +
            "(employee_id, work_date, start_time, end_time, position_id) " +
            "VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, employeeId);
            statement.setDate(2, Date.valueOf(workDate));
            statement.setTime(3, Time.valueOf(startTime));
            statement.setTime(4, Time.valueOf(endTime));
            statement.setInt(5, positionId);

            statement.executeUpdate();
        }
    }

    private static void extendShortShifts(
        LocalDate startDate,
        LocalDate endDate
    ) throws Exception {

        String sql =
            "SELECT DISTINCT ws.employee_id, ws.work_date, e.employment_type " +
            "FROM work_shift ws " +
            "JOIN employees e ON ws.employee_id = e.employee_id " +
            "WHERE ws.work_date BETWEEN ? AND ? " +
            "AND e.employment_type IN ('FULL_TIME', 'PART_TIME', 'NEWBIE') " +
            "ORDER BY ws.work_date, ws.employee_id";

        try (
            Connection connection = DBConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setDate(1, Date.valueOf(startDate));
            statement.setDate(2, Date.valueOf(endDate));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {

                    int employeeId = resultSet.getInt("employee_id");
                    LocalDate workDate = resultSet.getDate("work_date").toLocalDate();
                    String employmentType = resultSet.getString("employment_type");

                    extendOneEmployeeDay(
                        connection,
                        employeeId,
                        workDate,
                        employmentType
                    );
                }
            }
        }
    }

    private static void extendOneEmployeeDay(
        Connection connection,
        int employeeId,
        LocalDate workDate,
        String employmentType
    ) throws Exception {

        int currentSlots = getDailyWorkSlots(connection, employeeId, workDate);
        int requestSlots = getRequestSlots(connection, employeeId, workDate);

        int requiredSlots;

        if ("FULL_TIME".equals(employmentType)) {
            requiredSlots = Math.min(FULL_TIME_MIN_SLOTS, requestSlots);
        } else if ("NEWBIE".equals(employmentType)) {
            requiredSlots = Math.min(NEWBIE_MIN_SLOTS, requestSlots);
        } else {
            requiredSlots = Math.min(PART_TIME_MIN_SLOTS, requestSlots);
        }

        while (currentSlots < requiredSlots) {

            WorkEnd workEnd = getLastWorkEnd(connection, employeeId, workDate);

            if (workEnd == null) {
                return;
            }

            LocalTime nextStart = workEnd.endTime;
            LocalTime nextEnd = addThirtyMinutes(nextStart);

            if (!nextStart.isBefore(CLOSE_TIME) || nextEnd.isAfter(CLOSE_TIME)) {
                return;
            }

            if ("NEWBIE".equals(employmentType) &&
                !nextStart.isBefore(NEWBIE_LIMIT_TIME)) {
                return;
            }

            if (!isWithinRequest(connection, employeeId, workDate, nextStart, nextEnd)) {
                return;
            }

            int positionId = findExtendablePosition(
                connection,
                employeeId,
                workDate,
                nextStart
            );

            if (positionId == 0) {
                return;
            }

            insertShift(
                connection,
                employeeId,
                workDate,
                nextStart,
                nextEnd,
                positionId
            );

            currentSlots++;
        }
    }

    private static int getDailyWorkSlots(
        Connection connection,
        int employeeId,
        LocalDate workDate
    ) throws Exception {

        String sql =
            "SELECT COUNT(*) AS count " +
            "FROM work_shift " +
            "WHERE employee_id = ? " +
            "AND work_date = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, employeeId);
            statement.setDate(2, Date.valueOf(workDate));

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("count");
                }
            }
        }

        return 0;
    }

    private static int getRequestSlots(
        Connection connection,
        int employeeId,
        LocalDate workDate
    ) throws Exception {

        String sql =
            "SELECT COALESCE(SUM(TIMESTAMPDIFF(MINUTE, start_time, end_time) / 30), 0) AS request_slots " +
            "FROM request_shift " +
            "WHERE employee_id = ? " +
            "AND work_date = ? " +
            "AND is_submitted = true";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, employeeId);
            statement.setDate(2, Date.valueOf(workDate));

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("request_slots");
                }
            }
        }

        return 0;
    }

    private static WorkEnd getLastWorkEnd(
        Connection connection,
        int employeeId,
        LocalDate workDate
    ) throws Exception {

        String sql =
            "SELECT end_time, position_id " +
            "FROM work_shift " +
            "WHERE employee_id = ? " +
            "AND work_date = ? " +
            "ORDER BY end_time DESC " +
            "LIMIT 1";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, employeeId);
            statement.setDate(2, Date.valueOf(workDate));

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    WorkEnd workEnd = new WorkEnd();
                    workEnd.endTime = resultSet.getTime("end_time").toLocalTime();
                    workEnd.positionId = resultSet.getInt("position_id");
                    return workEnd;
                }
            }
        }

        return null;
    }

    private static boolean isWithinRequest(
        Connection connection,
        int employeeId,
        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime
    ) throws Exception {

        String sql =
            "SELECT COUNT(*) AS count " +
            "FROM request_shift " +
            "WHERE employee_id = ? " +
            "AND work_date = ? " +
            "AND is_submitted = true " +
            "AND start_time <= ? " +
            "AND end_time >= ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, employeeId);
            statement.setDate(2, Date.valueOf(workDate));
            statement.setTime(3, Time.valueOf(startTime));
            statement.setTime(4, Time.valueOf(endTime));

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("count") > 0;
                }
            }
        }

        return false;
    }

    private static int findExtendablePosition(
        Connection connection,
        int employeeId,
        LocalDate workDate,
        LocalTime timeSlot
    ) throws Exception {

        String sql =
            "SELECT ws.position_id " +
            "FROM work_shift ws " +
            "WHERE ws.employee_id = ? " +
            "AND ws.work_date = ? " +
            "AND ws.end_time = ? " +
            "ORDER BY ws.position_id " +
            "LIMIT 1";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, employeeId);
            statement.setDate(2, Date.valueOf(workDate));
            statement.setTime(3, Time.valueOf(timeSlot));

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("position_id");
                }
            }
        }

        return 0;
    }

    private static void printShortage(
        LocalDate startDate,
        LocalDate endDate
    ) throws Exception {

        String sql =
            "SELECT p.position_name, rs.required_count, " +
            "COUNT(ws.employee_id) AS assigned_count " +
            "FROM required_staff rs " +
            "JOIN positions p ON rs.position_id = p.position_id " +
            "LEFT JOIN work_shift ws " +
            "ON ws.work_date = ? " +
            "AND ws.start_time = rs.time_slot " +
            "AND ws.position_id = rs.position_id " +
            "WHERE rs.day_type = ? " +
            "AND rs.time_slot = ? " +
            "GROUP BY p.position_name, rs.required_count " +
            "HAVING assigned_count < rs.required_count";

        try (Connection connection = DBConnection.getConnection()) {

            LocalDate workDate = startDate;

            while (!workDate.isAfter(endDate)) {

                String dayType = getDayType(workDate);
                LocalTime timeSlot = OPEN_TIME;

                while (timeSlot.isBefore(CLOSE_TIME)) {

                    try (PreparedStatement statement = connection.prepareStatement(sql)) {

                        statement.setDate(1, Date.valueOf(workDate));
                        statement.setString(2, dayType);
                        statement.setTime(3, Time.valueOf(timeSlot));

                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next()) {

                                int shortage =
                                    resultSet.getInt("required_count") -
                                    resultSet.getInt("assigned_count");

                                System.out.println(
                                    workDate + " " +
                                    formatTime(timeSlot) + " " +
                                    resultSet.getString("position_name") + " " +
                                    shortage + "人不足"
                                );
                            }
                        }
                    }

                    timeSlot = addThirtyMinutes(timeSlot);
                }

                workDate = workDate.plusDays(1);
            }
        }
    }

    private static boolean isBetween(LocalTime timeSlot, int startHour, int endHour) {

        LocalTime startTime = LocalTime.of(startHour, 0);
        LocalTime endTime = LocalTime.of(endHour, 0);

        return !timeSlot.isBefore(startTime) && timeSlot.isBefore(endTime);
    }

    private static boolean isNextDayRest(LocalDate workDate) {

        LocalDate nextDate = workDate.plusDays(1);
        int dayOfWeek = nextDate.getDayOfWeek().getValue();

        return dayOfWeek == 6 || dayOfWeek == 7;
    }

    private static String getDayType(LocalDate date) {

        int dayOfWeek = date.getDayOfWeek().getValue();

        if (dayOfWeek == 5) {
            return "金";
        }

        if (dayOfWeek == 6 || dayOfWeek == 7) {
            return "土日";
        }

        return "平日";
    }

    private static LocalTime addThirtyMinutes(LocalTime time) {

        return time.plusMinutes(30);
    }

    private static int countSlots(LocalTime startTime, LocalTime endTime) {

        if (!startTime.isBefore(endTime)) {
            return 0;
        }

        return (int) (Duration.between(startTime, endTime).toMinutes() / 30);
    }

    private static LocalTime minTime(LocalTime a, LocalTime b) {

        if (a.isBefore(b)) {
            return a;
        }

        return b;
    }

    private static String formatTime(LocalTime time) {

        return String.format(
            "%02d:%02d",
            time.getHour(),
            time.getMinute()
        );
    }

    private static class Candidate {

        int employeeId;
        String employmentType;
        int monthlyMinHours;
        int positionId;
        int dailyWorkCount;
        int periodWorkCount;
        int requestSlots;
        LocalTime requestEndTime;
    }

    private static class SlotStatus {

        int totalCount;
        int fullTimeCount;
        int levelSum;
        int nonFullTimeLevelSum;
    }

    private static class WorkEnd {

        LocalTime endTime;
        int positionId;
    }
}
