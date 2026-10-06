package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.BarbershopUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.NewBarbershop;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PlanRepository;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** platform-admin operates barbershops through barbershop-api (DEC-PLAT-01, DEC-SHOP-06). */
public class ManageBarbershops implements BarbershopUseCases {

    private final Barbershops barbershops;
    private final PlanRepository plans;
    private final Clock clock;

    public ManageBarbershops(Barbershops barbershops, PlanRepository plans, Clock clock) {
        this.barbershops = barbershops;
        this.plans = plans;
        this.clock = clock;
    }

    @Override
    public Page<Barbershop> list(Caller caller, String status, Page.Request page) {
        caller.require(Role.SUPER_ADMIN);
        return barbershops.list(status, null, null, page);
    }

    @Override
    public Barbershop get(Caller caller, UUID id) {
        caller.require(Role.SUPER_ADMIN);
        return barbershops.get(id);
    }

    @Override
    public Trial trial(Caller caller, UUID id) {
        caller.require(Role.SUPER_ADMIN);
        return trialOf(barbershops.get(id), clock.instant());
    }

    /** daysRemaining counts started days, 0 once it has expired, even before FR-026 suspends it. */
    static Trial trialOf(Barbershop b, Instant now) {
        boolean expired = now.isAfter(b.trialEndsAt());
        long hours = expired ? 0 : Duration.between(now, b.trialEndsAt()).toHours();
        int days = (int) ((hours + 23) / 24);
        return new Trial(b.id(), b.status(), b.createdAt(), b.trialEndsAt(), days, expired);
    }

    @Override
    public Barbershop changeStatus(Caller caller, UUID id, String status) {
        caller.require(Role.SUPER_ADMIN);
        return barbershops.changeStatus(id, status);
    }

    @Override
    public Barbershop assignPlan(Caller caller, UUID id, UUID planId) {
        caller.require(Role.SUPER_ADMIN);
        requireActivePlan(planId);
        return barbershops.assignPlan(id, planId);
    }

    @Override
    public Onboarded onboard(Caller caller, NewBarbershop barbershop, UUID planId, String idempotencyKey) {
        caller.require(Role.SUPER_ADMIN);
        requireActivePlan(planId);
        // barbershop-api keeps the key: a retry gets the same barbershop, and the plan is set again (idempotent).
        Barbershops.Created created = barbershops.create(barbershop, idempotencyKey);
        Barbershop withPlan = barbershops.assignPlan(created.barbershop().id(), planId);
        return new Onboarded(withPlan, created.created());
    }

    private void requireActivePlan(UUID planId) {
        SubscriptionPlan plan = plans.findById(planId).orElseThrow(() -> new NotFound("Plan"));
        if (!plan.active()) {
            throw new BusinessRuleViolation("The selected plan is not active");
        }
    }
}
