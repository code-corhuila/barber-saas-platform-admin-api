package co.edu.corhuila.barbersaas.platformadmin.application.port.in;

import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.util.UUID;

/** Subscription plans (FR-024): SUPER_ADMIN manages them; anyone reads the active ones (FR-004). */
public interface PlanUseCases {

    /** The fields of CreateSubscriptionPlanRequest, already validated in shape by the HTTP adapter. */
    record PlanData(String name, long priceCents, int maxBarbers, String featuresJson) { }

    /** The result of a creation: {@code created} is false when the same Idempotency-Key was retried (200, not 201). */
    record Created<T>(T value, boolean created) { }

    /** GET /api/v1/plans: active plans, cheapest first; no token needed. */
    Page<SubscriptionPlan> listActive(Page.Request page);

    /** GET /api/v1/platform/plans: every plan, active or not. */
    Page<SubscriptionPlan> list(Caller caller, Page.Request page);

    SubscriptionPlan get(Caller caller, UUID id);

    Created<SubscriptionPlan> create(Caller caller, PlanData data, String idempotencyKey);

    SubscriptionPlan update(Caller caller, UUID id, PlanData data);

    /** DELETE: sets isActive = false; 422 while barbershops are still assigned to it (DEC-PLAT-02). */
    void deactivate(Caller caller, UUID id);
}
