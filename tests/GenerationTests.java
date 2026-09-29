import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** No database is used: jobs and failures are controlled explicitly. */
public class GenerationTests {
    private static int checks;
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = START.plusDays(1);

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static class QueueExecutor implements Executor {
        final Queue<Runnable> jobs = new ArrayDeque<>();
        public synchronized void execute(Runnable job) { jobs.add(job); }
        void runNext() { jobs.remove().run(); }
    }

    public static void main(String[] args) throws Exception {
        QueueExecutor queue = new QueueExecutor();
        GenerationService[] holder = new GenerationService[1];
        holder[0] = new GenerationService(queue, (start, end, progress) -> {
            progress.accept(new ShiftGenerator.Progress(ShiftGenerator.Stage.GENERATING,
                24, 48, start.plusDays(1), LocalTime.of(10, 0)));
            check(holder[0].snapshot().state() == GenerationService.State.RUNNING, "Job remains running between updates");
            check(holder[0].snapshot().percent() == 48, "Progress reflects completed slots, with final step reserved");
            check(holder[0].snapshot().toJson().contains("\"completedDays\":1"), "Completed days shown");
            check(holder[0].snapshot().toJson().contains("\"date\":\"2026-10-02\""), "Current date shown");
            progress.accept(new ShiftGenerator.Progress(ShiftGenerator.Stage.ADJUSTING, 48, 48, null, null));
            check(holder[0].snapshot().percent() < 100, "Adjustment must finish before 100 percent");
            check(holder[0].snapshot().toJson().contains("ADJUSTING"), "Adjustment has a distinct status");
        });
        GenerationService service = holder[0];
        check(service.snapshot().state() == GenerationService.State.IDLE, "Initial state");
        check(service.start(START, END), "First job accepted");
        check(service.snapshot().state() == GenerationService.State.RUNNING, "HTTP start can return before work completes");
        check(service.snapshot().percent() == 0, "Queued job begins at zero");
        check(!service.start(START.plusDays(5), END.plusDays(5)), "Duplicate start blocked");
        check(queue.jobs.size() == 1, "Only one job queued");
        check(service.snapshot().start().equals(START), "Rejected start does not replace period");
        queue.runNext();
        check(service.snapshot().state() == GenerationService.State.SUCCEEDED, "Successful completion visible");
        check(service.snapshot().percent() == 100, "Success reaches 100 percent");

        QueueExecutor failedQueue = new QueueExecutor();
        AtomicInteger attempts = new AtomicInteger();
        GenerationService failing = new GenerationService(failedQueue, (start, end, progress) -> {
            progress.accept(new ShiftGenerator.Progress(ShiftGenerator.Stage.GENERATING, 3, 48, start, LocalTime.of(11, 30)));
            if (attempts.incrementAndGet() == 1) throw new Exception("Simulated database failure (expected test output)");
        });
        failing.start(START, END);
        failedQueue.runNext();
        check(failing.snapshot().state() == GenerationService.State.FAILED, "Exceptions become a visible error state");
        check(failing.snapshot().percent() < 100, "Failure never reports completed");
        check(failing.snapshot().progress().completedSlots() == 3, "Failure preserves last progress");
        check(failing.snapshot().toJson().contains("\"state\":\"FAILED\""), "Polling response includes error state");
        check(!failing.snapshot().toJson().contains("database failure"), "Internal exception text is not exposed");
        check(failing.start(START, END), "Retry accepted after error");
        failedQueue.runNext();
        check(failing.snapshot().state() == GenerationService.State.SUCCEEDED, "Retry can succeed");

        GenerationService rejected = new GenerationService(job -> {
            throw new RejectedExecutionException("Simulated executor failure (expected test output)");
        }, (start, end, progress) -> { throw new AssertionError("Must not run"); });
        rejected.start(START, END);
        check(rejected.snapshot().state() == GenerationService.State.FAILED, "Scheduling failure cannot leave job stuck running");

        QueueExecutor concurrentQueue = new QueueExecutor();
        GenerationService concurrent = new GenerationService(concurrentQueue, (start, end, progress) -> {});
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[8];
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                if (concurrent.start(START, END)) accepted.incrementAndGet();
            });
            threads[i].start();
        }
        ready.await();
        go.countDown();
        for (Thread thread : threads) thread.join();
        check(accepted.get() == 1 && concurrentQueue.jobs.size() == 1, "Concurrent HTTP submissions start exactly one job");
        try {
            service.start(START, START.plusDays(62));
            throw new AssertionError("Oversized period accepted");
        } catch (IllegalArgumentException expected) { checks++; }

        java.lang.reflect.Method render = WebServer.class.getDeclaredMethod("renderGenerationProgress");
        render.setAccessible(true);
        String html = (String)render.invoke(null);
        check(html.contains("エラーが発生しました"), "Page explicitly labels generation errors");
        check(html.contains("自動で再接続"), "Connection problems are distinguished from generation errors");
        check(html.contains("event.defaultPrevented"), "Cancelled confirmation does not disable the form");
        check(html.contains("/generate-status"), "Page polls server progress");
        System.out.println("PASS: " + checks + " generation checks");
    }
}
