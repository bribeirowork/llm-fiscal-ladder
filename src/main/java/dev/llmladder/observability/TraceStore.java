package dev.llmladder.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Retenção curta e limitada: em produção os traces vão para o backend de observabilidade. */
public final class TraceStore {
    private static final int CAPACITY = 500;

    private final Map<String, TaskTrace> traces = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TaskTrace> eldest) {
            return size() > CAPACITY;
        }
    });

    public void save(TaskTrace trace) {
        traces.put(trace.id(), trace);
    }

    public Optional<TaskTrace.View> find(String id, String tenant) {
        return Optional.ofNullable(traces.get(id)).map(TaskTrace::view).filter(v -> v.tenant().equals(tenant));
    }
}
