package co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Payments;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.Payment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** platform_admin.payment, as platform_admin_app. Every value is bound, never concatenated. */
public class JdbcPayments implements Payments {

    private static final String COLUMNS = "id, barbershop_id, plan_id, plan_name, reference, amount_cents, currency, "
            + "status, provider_transaction_id, period_end, created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcPayments(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_admin.payment WHERE id = ?", (rs, n) -> map(rs), id)
                .stream().findFirst();
    }

    @Override
    public Optional<Payment> findByReference(String reference) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_admin.payment WHERE reference = ?",
                (rs, n) -> map(rs), reference).stream().findFirst();
    }

    @Override
    public Page<Payment> page(UUID barbershopId, Page.Request page) {
        List<Object> args = new ArrayList<>();
        String from = "FROM platform_admin.payment";
        if (barbershopId != null) {
            from += " WHERE barbershop_id = ?";
            args.add(barbershopId);
        }
        return JdbcPages.page(jdbc, COLUMNS, new JdbcPages.Query(from, args, "ORDER BY created_at DESC, id"),
                (rs, n) -> map(rs), page);
    }

    @Override
    public Optional<Instant> paidUntil(UUID barbershopId) {
        Timestamp at = jdbc.queryForObject("SELECT max(period_end) FROM platform_admin.payment "
                + "WHERE barbershop_id = ? AND status = 'APPROVED'", Timestamp.class, barbershopId);
        return Optional.ofNullable(at).map(Timestamp::toInstant);
    }

    @Override
    public Optional<Idempotency.Stored> findKey(String key, String operation) {
        return JdbcIdempotency.find(jdbc, key, operation);
    }

    @Override
    public void saveNew(Payment p, Idempotency.Key key) {
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO platform_admin.payment (id, barbershop_id, plan_id, plan_name, reference, "
                            + "amount_cents, currency, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    p.id(), p.barbershopId(), p.planId(), p.planName(), p.reference(), p.amountCents(), p.currency(),
                    p.status(), Timestamp.from(p.createdAt()), Timestamp.from(p.updatedAt()));
            JdbcIdempotency.insert(jdbc, key, p.id());
        });
    }

    /** WHERE status = 'PENDING': two deliveries of the same event cannot both finish it. */
    @Override
    public boolean finish(UUID id, String status, String providerTransactionId, Instant periodEnd, Instant now) {
        try {
            return jdbc.update("UPDATE platform_admin.payment SET status = ?, provider_transaction_id = ?, "
                            + "period_end = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'",
                    status, providerTransactionId, periodEnd == null ? null : Timestamp.from(periodEnd),
                    Timestamp.from(now), id) == 1;
        } catch (DuplicateKeyException e) {
            return false;                       // that transaction id already finished another payment
        }
    }

    private static Payment map(ResultSet rs) throws SQLException {
        Timestamp periodEnd = rs.getTimestamp("period_end");
        return new Payment(rs.getObject("id", UUID.class), rs.getObject("barbershop_id", UUID.class),
                rs.getObject("plan_id", UUID.class), rs.getString("plan_name"), rs.getString("reference"),
                rs.getLong("amount_cents"), rs.getString("currency"), rs.getString("status"),
                rs.getString("provider_transaction_id"), periodEnd == null ? null : periodEnd.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
