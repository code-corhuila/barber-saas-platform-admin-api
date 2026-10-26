package co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence;

import co.edu.corhuila.barbersaas.platformadmin.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.OutboxStore;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The outbox in memory, for running the HTTP contract without a database; same rules as the SQL tables. */
public class InMemoryOutbox implements OutboxStore {

    private record Row(OutboxEvent event, Instant publishedAt, Instant failedAt, String lastError) { }

    private final Map<UUID, Row> rows = new LinkedHashMap<>();
    private final Set<String> notices = new HashSet<>();

    @Override
    public synchronized void add(OutboxEvent event) {
        rows.put(event.id(), new Row(event, null, null, null));
    }

    @Override
    public synchronized boolean addNotice(UUID barbershopId, Instant endsAt, String threshold, OutboxEvent event) {
        if (!notices.add(barbershopId + "|" + endsAt + "|" + threshold)) {
            return false;
        }
        add(event);
        return true;
    }

    @Override
    public synchronized List<Stored> pending(int limit) {
        return rows.values().stream().filter(r -> r.publishedAt() == null && r.failedAt() == null)
                .limit(limit).map(r -> new Stored(r.event(), "none")).toList();
    }

    @Override
    public synchronized boolean markPublished(UUID id, Instant now) {
        Row r = rows.get(id);
        if (r == null) {
            return false;
        }
        rows.put(id, new Row(r.event(), r.publishedAt() == null ? now : r.publishedAt(), r.failedAt(), r.lastError()));
        return true;
    }

    @Override
    public synchronized boolean markFailed(UUID id, String reason, Instant now) {
        Row r = rows.get(id);
        if (r == null) {
            return false;
        }
        rows.put(id, new Row(r.event(), r.publishedAt(), r.failedAt() == null ? now : r.failedAt(), reason));
        return true;
    }
}
