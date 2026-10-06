package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.IdempotencyKeyReused;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.InvalidField;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PlanUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PlanRepository;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/** Plans: only SUPER_ADMIN changes them (FR-024, FR-025); the active ones are public (FR-004). */
public class ManagePlans implements PlanUseCases {

    static final String CREATE_OPERATION = "POST /api/v1/platform/plans";

    private final PlanRepository plans;
    private final IdGenerator ids;
    private final Clock clock;

    public ManagePlans(PlanRepository plans, IdGenerator ids, Clock clock) {
        this.plans = plans;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public Page<SubscriptionPlan> listActive(Page.Request page) {
        return plans.page(true, page);
    }

    @Override
    public Page<SubscriptionPlan> list(Caller caller, Page.Request page) {
        caller.require(Role.SUPER_ADMIN);
        return plans.page(false, page);
    }

    @Override
    public SubscriptionPlan get(Caller caller, UUID id) {
        caller.require(Role.SUPER_ADMIN);
        return plans.findById(id).orElseThrow(() -> new NotFound("Plan"));
    }

    @Override
    public Created<SubscriptionPlan> create(Caller caller, PlanData data, String idempotencyKey) {
        caller.require(Role.SUPER_ADMIN);
        String hash = RequestHash.of(data.name(), data.priceCents(), data.maxBarbers(), data.featuresJson());

        // A retry with the same key returns the first plan; it never creates a second one.
        Optional<Idempotency.Stored> stored = plans.findKey(idempotencyKey, CREATE_OPERATION);
        if (stored.isPresent()) {
            if (!stored.get().requestHash().equals(hash)) {
                throw new IdempotencyKeyReused();
            }
            return new Created<>(plans.findById(stored.get().resourceId()).orElseThrow(IdempotencyKeyReused::new), false);
        }
        SubscriptionPlan plan = SubscriptionPlan.create(ids.next(), data.name(), data.priceCents(), data.maxBarbers(),
                data.featuresJson(), clock.instant());
        try {
            plans.saveNew(plan, new Idempotency.Key(idempotencyKey, CREATE_OPERATION, hash));
        } catch (PlanRepository.NameTaken e) {
            throw new InvalidField("name", "another plan already has that name");
        }
        return new Created<>(plan, true);
    }

    @Override
    public SubscriptionPlan update(Caller caller, UUID id, PlanData data) {
        caller.require(Role.SUPER_ADMIN);
        SubscriptionPlan edited = plans.findById(id).orElseThrow(() -> new NotFound("Plan"))
                .edit(data.name(), data.priceCents(), data.maxBarbers(), data.featuresJson());
        try {
            plans.update(edited);
        } catch (PlanRepository.NameTaken e) {
            throw new InvalidField("name", "another plan already has that name");
        }
        return edited;
    }
}
