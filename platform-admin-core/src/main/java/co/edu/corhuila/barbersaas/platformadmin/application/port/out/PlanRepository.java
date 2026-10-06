package co.edu.corhuila.barbersaas.platformadmin.application.port.out;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.util.Optional;
import java.util.UUID;

/** platform_admin.subscription_plan. */
public interface PlanRepository {

    /** Thrown when another plan already has that name (uq_subscription_plan_name). */
    class NameTaken extends RuntimeException {
        public NameTaken() {
            super("another plan already has that name");
        }
    }

    /** Cheapest first, then by name; {@code onlyActive} for the public list. */
    Page<SubscriptionPlan> page(boolean onlyActive, Page.Request page);

    Optional<SubscriptionPlan> findById(UUID id);

    Optional<Idempotency.Stored> findKey(String key, String operation);

    /** The plan and its idempotency key in ONE transaction (norm 5.3.8). */
    void saveNew(SubscriptionPlan plan, Idempotency.Key key);

    void update(SubscriptionPlan plan);
}
