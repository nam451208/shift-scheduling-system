import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** One generation job per server, with immutable snapshots for HTTP readers. */
final class GenerationService {
    interface Work {
        void run(LocalDate start, LocalDate end, Consumer<ShiftGenerator.Progress> progress) throws Exception;
    }

    enum State { IDLE, RUNNING, SUCCEEDED, FAILED }

    record Snapshot(State state, LocalDate start, LocalDate end, ShiftGenerator.Progress progress) {
        int percent() {
            if (state == State.SUCCEEDED) return 100;
            if (progress == null) return 0;
            // Reserve the last step for the final working-hours adjustment.
            return Math.min(99, progress.completedSlots() * 100 / (progress.totalSlots() + 1));
        }

        String toJson() {
            // All strings below come from enums and java.time, never user-provided text.
            return "{\"state\":\"" + state + "\",\"percent\":" + percent()
                + ",\"start\":\"" + (start == null ? "" : start) + "\",\"end\":\"" + (end == null ? "" : end)
                + "\",\"stage\":\"" + (progress == null ? "" : progress.stage())
                + "\",\"date\":\"" + (progress == null || progress.date() == null ? "" : progress.date())
                + "\",\"time\":\"" + (progress == null || progress.time() == null ? "" : progress.time())
                + "\",\"completedDays\":" + (progress == null ? 0 : progress.completedSlots() / 24)
                + ",\"totalDays\":" + (progress == null ? 0 : progress.totalSlots() / 24) + "}";
        }
    }

    private final Executor executor;
    private final Work work;
    private volatile Snapshot snapshot = new Snapshot(State.IDLE, null, null, null);

    GenerationService(Executor executor, Work work) {
        this.executor = executor;
        this.work = work;
    }

    Snapshot snapshot() { return snapshot; }

    synchronized boolean start(LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days < 1 || days > 62) throw new IllegalArgumentException("Invalid generation period");
        if (snapshot.state() == State.RUNNING) return false;
        snapshot = new Snapshot(State.RUNNING, start, end,
            new ShiftGenerator.Progress(ShiftGenerator.Stage.PREPARING, 0, (int)days * 24, null, null));
        try {
            executor.execute(() -> {
                try {
                    work.run(start, end, progress -> snapshot = new Snapshot(State.RUNNING, start, end, progress));
                    snapshot = new Snapshot(State.SUCCEEDED, start, end, snapshot.progress());
                } catch (Exception e) {
                    snapshot = new Snapshot(State.FAILED, start, end, snapshot.progress());
                    e.printStackTrace();
                }
            });
        } catch (RuntimeException e) {
            snapshot = new Snapshot(State.FAILED, start, end, snapshot.progress());
            e.printStackTrace();
        }
        return true;
    }
}
