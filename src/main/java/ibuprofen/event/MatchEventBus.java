package ibuprofen.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Central synchronous event bus and 256-event history ring buffer.
 * Single source of truth connecting WorldState -> MatchMemory, MatchPlanner, and DebugRecorder.
 */
public final class MatchEventBus {

    public static final int HISTORY_CAPACITY = 256;

    private final List<Consumer<MatchEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<MatchEvent> history = new ArrayDeque<>(HISTORY_CAPACITY);

    public void subscribe(Consumer<MatchEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void unsubscribe(Consumer<MatchEvent> listener) {
        listeners.remove(listener);
    }

    public void publish(MatchEvent event) {
        if (event == null) {
            return;
        }
        synchronized (history) {
            if (history.size() >= HISTORY_CAPACITY) {
                history.removeFirst();
            }
            history.addLast(event);
        }
        for (Consumer<MatchEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Throwable ignored) {
                // Never allow a telemetry or observer error to crash the game tick.
            }
        }
    }

    public List<MatchEvent> recentEvents(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        synchronized (history) {
            int n = Math.min(limit, history.size());
            List<MatchEvent> all = new ArrayList<>(history);
            return List.copyOf(all.subList(all.size() - n, all.size()));
        }
    }

    public <T extends MatchEvent> List<T> recentOfType(Class<T> type, int limit) {
        if (type == null || limit <= 0) {
            return List.of();
        }
        synchronized (history) {
            List<T> result = new ArrayList<>();
            var it = history.descendingIterator();
            while (it.hasNext() && result.size() < limit) {
                MatchEvent ev = it.next();
                if (type.isInstance(ev)) {
                    result.add(type.cast(ev));
                }
            }
            return List.copyOf(result);
        }
    }

    public void clearHistory() {
        synchronized (history) {
            history.clear();
        }
    }
}
