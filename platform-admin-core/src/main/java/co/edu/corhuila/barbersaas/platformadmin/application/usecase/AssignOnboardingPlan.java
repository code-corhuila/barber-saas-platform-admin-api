package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PlanRepository;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.util.UUID;

/**
 * Step assign-plan of the owner-onboarding saga (FR-004, DEC-PLAT-04): the plan the owner picked
 * must exist and be active (INV-SHOP-003, DEC-PLAT-02), and is then assigned through barbershop-api.
 * Idempotent: assigning the same plan again changes nothing.
 */
public class AssignOnboardingPlan {

    public static final String WORKFLOW = "barber-saas-workflow";

    private final Barbershops barbershops;
    private final PlanRepository plans;

    public AssignOnboardingPlan(Barbershops barbershops, PlanRepository plans) {
        this.barbershops = barbershops;
        this.plans = plans;
    }

    /** An unknown plan is the same 422 as an inactive one: for the saga both mean "pick another plan". */
    public void assign(Caller caller, UUID barbershopId, UUID planId) {
        caller.requireService(WORKFLOW);
        plans.findById(planId).filter(SubscriptionPlan::active)
                .orElseThrow(() -> new BusinessRuleViolation("The selected plan is not active"));
        barbershops.assignPlan(barbershopId, planId);
    }
}
