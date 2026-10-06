package co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PlanRepository;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** platform_admin.subscription_plan, as platform_admin_app. Every value is bound, never concatenated. */
public class JdbcPlanRepository implements PlanRepository {

    private static final String COLUMNS =
            "id, name, price_cents, max_barbers, features_json::text AS features_json, is_active, created_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcPlanRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Page<SubscriptionPlan> page(boolean onlyActive, Page.Request page) {
        String from = "FROM platform_admin.subscription_plan" + (onlyActive ? " WHERE is_active" : "");
        return JdbcPages.page(jdbc, COLUMNS, new JdbcPages.Query(from, List.of(), "ORDER BY price_cents, name"),
                (rs, n) -> map(rs), page);
    }

    @Override
    public Optional<SubscriptionPlan> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_admin.subscription_plan WHERE id = ?",
                (rs, n) -> map(rs), id).stream().findFirst();
    }

    @Override
    public Optional<Idempotency.Stored> findKey(String key, String operation) {
        return JdbcIdempotency.find(jdbc, key, operation);
    }

    @Override
    public void saveNew(SubscriptionPlan p, Idempotency.Key key) {
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO platform_admin.subscription_plan (id, name, price_cents, max_barbers, "
                                + "features_json, is_active, created_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)",
                        p.id(), p.name(), p.priceCents(), p.maxBarbers(), p.featuresJson(), p.active(),
                        Timestamp.from(p.createdAt()));
                JdbcIdempotency.insert(jdbc, key, p.id());
            });
        } catch (DuplicateKeyException e) {
            throw nameTakenOr(e);
        }
    }

    @Override
    public void update(SubscriptionPlan p) {
        try {
            jdbc.update("UPDATE platform_admin.subscription_plan SET name = ?, price_cents = ?, max_barbers = ?, "
                            + "features_json = ?::jsonb, is_active = ? WHERE id = ?",
                    p.name(), p.priceCents(), p.maxBarbers(), p.featuresJson(), p.active(), p.id());
        } catch (DuplicateKeyException e) {
            throw nameTakenOr(e);
        }
    }

    /** Only the unique name is a NameTaken; any other duplicate (an idempotency key raced) stays an error. */
    private static RuntimeException nameTakenOr(DuplicateKeyException e) {
        return String.valueOf(e.getMessage()).contains("uq_subscription_plan_name") ? new NameTaken() : e;
    }

    private static SubscriptionPlan map(ResultSet rs) throws SQLException {
        return new SubscriptionPlan(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("price_cents"),
                rs.getInt("max_barbers"), rs.getString("features_json"), rs.getBoolean("is_active"),
                rs.getTimestamp("created_at").toInstant());
    }
}
