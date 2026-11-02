package ru.k6lab.core.runstate;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Статус run и явная таблица переходов. Недопустимый переход - исключение.
 * DEGRADED - отдельный флаг, не статус.
 */
public enum RunStatus {
    QUEUED, PREPARING, RUNNING, STOPPING, FINISHED, FAILED, ABORTED;

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED = new EnumMap<>(RunStatus.class);

    static {
        ALLOWED.put(QUEUED, EnumSet.of(PREPARING, FAILED, ABORTED));
        ALLOWED.put(PREPARING, EnumSet.of(RUNNING, STOPPING, FAILED, ABORTED));
        ALLOWED.put(RUNNING, EnumSet.of(STOPPING, FINISHED, FAILED, ABORTED));
        ALLOWED.put(STOPPING, EnumSet.of(FINISHED, FAILED, ABORTED));
        ALLOWED.put(FINISHED, EnumSet.noneOf(RunStatus.class));
        ALLOWED.put(FAILED, EnumSet.noneOf(RunStatus.class));
        ALLOWED.put(ABORTED, EnumSet.noneOf(RunStatus.class));
    }

    public boolean isTerminal() {
        return this == FINISHED || this == FAILED || this == ABORTED;
    }

    /** Run занимает генераторы. */
    public boolean isActive() {
        return this == PREPARING || this == RUNNING || this == STOPPING;
    }

    public boolean canTransitionTo(RunStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public RunStatus transitionTo(RunStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateTransitionException(this, next);
        }
        return next;
    }

    public static class IllegalStateTransitionException extends IllegalStateException {
        public IllegalStateTransitionException(RunStatus from, RunStatus to) {
            super("Недопустимый переход статуса run: " + from + " -> " + to);
        }
    }
}
