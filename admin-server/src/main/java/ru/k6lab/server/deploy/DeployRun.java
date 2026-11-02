package ru.k6lab.server.deploy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Исполнение одной операции: нумерует шаги, пишет журнал и прогресс. */
public class DeployRun {

    private final long id;
    private final DeployLog log;
    private final DeployStore store;
    private final List<Deployment.StepInfo> steps = new ArrayList<>();
    private int counter;

    public DeployRun(long id, DeployLog log, DeployStore store) {
        this.id = id;
        this.log = log;
        this.store = store;
    }

    public long id() {
        return id;
    }

    public DeployLog log() {
        return log;
    }

    public synchronized List<Deployment.StepInfo> steps() {
        return List.copyOf(steps);
    }

    /** Выполняет шаг. Исключение помечает шаг как FAILED и пробрасывается дальше. */
    public <T> T step(String name, Callable<T> body) throws Exception {
        int n = begin(name);
        try {
            T result = body.call();
            end(n, "OK", null);
            return result;
        } catch (Exception e) {
            end(n, "FAILED", e.getMessage());
            throw e;
        }
    }

    public void stage(String name, ThrowingRunnable body) throws Exception {
        step(name, () -> {
            body.run();
            return null;
        });
    }

    /** Шаг, который не нужен в этой конфигурации. */
    public void skipped(String name, String reason) {
        int n = begin(name);
        end(n, "SKIPPED", reason);
    }

    private synchronized int begin(String name) {
        int n = ++counter;
        steps.add(new Deployment.StepInfo(n, name, "RUNNING", null, Instant.now(), null));
        log.line("▶ Шаг " + n + ": " + name);
        log.stepChanged();
        persist();
        return n;
    }

    private synchronized void end(int n, String status, String message) {
        Deployment.StepInfo old = steps.get(n - 1);
        steps.set(n - 1, new Deployment.StepInfo(n, old.name(), status, message, old.startedAt(), Instant.now()));
        log.line(switch (status) {
            case "OK" -> "✔ Шаг " + n + " выполнен";
            case "SKIPPED" -> "– Шаг " + n + " пропущен" + (message == null ? "" : " (" + message + ")");
            default -> "✖ Шаг " + n + " завершился ошибкой: " + message;
        });
        log.stepChanged();
        persist();
    }

    private void persist() {
        try {
            store.saveProgress(id, steps, log.snapshot());
        } catch (RuntimeException ignored) {
            // прогресс - best-effort, итог сохраняется отдельно
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
