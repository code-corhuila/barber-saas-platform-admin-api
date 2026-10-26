package co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence;

import co.edu.corhuila.barbersaas.platformadmin.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.OutboxStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** platform_admin.outbox_event and platform_admin.subscription_notice (DEC-PLAT-07, ADR-016). */
public class JdbcOutbox implements OutboxStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcOutbox(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    @Override
    public void add(OutboxEvent event) {
        insert(event);
    }

    /** The notice's primary key refuses a second one: the event is then rolled back with it. */
    @Override
    public boolean addNotice(UUID barbershopId, Instant endsAt, String threshold, OutboxEvent event) {
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO platform_admin.subscription_notice (barbershop_id, ends_at, threshold) "
                        + "VALUES (?, ?, ?)", barbershopId, Timestamp.from(endsAt), threshold);
                insert(event);
            });
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** idx_outbox_event_unpublished serves this read: pending only, oldest first. */
    @Override
    public List<Stored> pending(int limit) {
        return jdbc.query("SELECT id, aggregate_id, event_type, payload::text AS payload, correlation_id, occurred_at "
                        + "FROM platform_admin.outbox_event WHERE published_at IS NULL AND failed_at IS NULL "
                        + "ORDER BY occurred_at, id LIMIT ?",
                (rs, n) -> new Stored(new OutboxEvent(rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"),
                        fromJson(rs.getString("payload")), rs.getTimestamp("occurred_at").toInstant()),
                        rs.getString("correlation_id")), limit);
    }

    @Override
    public boolean markPublished(UUID id, Instant now) {
        return jdbc.update("UPDATE platform_admin.outbox_event SET published_at = COALESCE(published_at, ?) "
                + "WHERE id = ?", Timestamp.from(now), id) == 1;
    }

    @Override
    public boolean markFailed(UUID id, String reason, Instant now) {
        return jdbc.update("UPDATE platform_admin.outbox_event SET failed_at = COALESCE(failed_at, ?), last_error = ? "
                + "WHERE id = ?", Timestamp.from(now), reason, id) == 1;
    }

    private void insert(OutboxEvent event) {
        String correlationId = Optional.ofNullable(MDC.get("correlationId")).orElse("none");
        jdbc.update("INSERT INTO platform_admin.outbox_event (id, aggregate_type, aggregate_id, event_type, payload, "
                        + "correlation_id, occurred_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)",
                event.id(), OutboxEvent.AGGREGATE_TYPE, event.barbershopId(), event.type(), toJson(event.payload()),
                correlationId, Timestamp.from(event.occurredAt()));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> fromJson(String payload) {
        try {
            return json.readValue(payload, new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
