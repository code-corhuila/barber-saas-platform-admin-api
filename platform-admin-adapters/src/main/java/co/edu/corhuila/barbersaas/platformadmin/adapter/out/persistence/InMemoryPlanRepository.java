package co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PlanRepository;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Plans in memory, for running the HTTP contract without a database; same rules as the SQL table. */
public class InMemoryPlanRepository implements PlanRepository {

    private final Map<UUID, SubscriptionPlan> rows = new ConcurrentHashMap<>();
    private final Map<String, Idempotency.Stored> keys = new ConcurrentHashMap<>();

    @Override
    public Page<SubscriptionPlan> page(boolean onlyActive, Page.Request page) {
        return Page.of(rows.values().stream()
                .filter(p -> !onlyActive || p.active())
                .sorted(Comparator.comparingLong(SubscriptionPlan::priceCents).thenComparing(SubscriptionPlan::name))
                .toList(), page);
    }

    @Override
    public Optional<SubscriptionPlan> findById(UUID id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<Idempotency.Stored> findKey(String key, String operation) {
        return Optional.ofNullable(keys.get(operation + " " + key));
    }

    @Override
    public synchronized void saveNew(SubscriptionPlan plan, Idempotency.Key key) {
        requireFreeName(plan);
        rows.put(plan.id(), plan);
        keys.put(key.operation() + " " + key.key(), new Idempotency.Stored(plan.id(), key.requestHash()));
    }

    @Override
    public synchronized void update(SubscriptionPlan plan) {
        requireFreeName(plan);
        rows.put(plan.id(), plan);
    }

    /** uq_subscription_plan_name. */
    private void requireFreeName(SubscriptionPlan plan) {
        boolean taken = rows.values().stream().anyMatch(p -> !p.id().equals(plan.id()) && p.name().equals(plan.name()));
        if (taken) {
            throw new NameTaken();
        }
    }
}
