package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PlanUseCases.PlanData;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssignOnboardingPlanTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private final FakeBarbershops shops = new FakeBarbershops();
    private final ManagePlansTest.FakePlans planRows = new ManagePlansTest.FakePlans();
    private final ManagePlans plans = new ManagePlans(planRows, shops, UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC));
    private final AssignOnboardingPlan assign = new AssignOnboardingPlan(shops, planRows);
    private final Caller workflow = new Caller(AssignOnboardingPlan.WORKFLOW, Role.SERVICE, null);
    private final Caller admin = new Caller(UUID.randomUUID().toString(), Role.SUPER_ADMIN, null);

    private SubscriptionPlan plan(String name) {
        return plans.create(admin, new PlanData(name, 100, 2, null), "key-" + UUID.randomUUID()).value();
    }

    @Test
    void the_workflow_assigns_an_active_plan_to_the_new_barbershop_and_may_repeat_it() {
        Barbershop b = shops.add("TRIAL", null, NOW);
        SubscriptionPlan pro = plan("Pro");
        assign.assign(workflow, b.id(), pro.id());
        assign.assign(workflow, b.id(), pro.id());
        assertEquals(pro.id(), shops.get(b.id()).planId());
        assertEquals("TRIAL", shops.get(b.id()).status());
    }

    @Test
    void an_unknown_or_inactive_plan_is_the_same_rule_violation() {
        Barbershop b = shops.add("TRIAL", null, NOW);
        SubscriptionPlan old = plan("Old");
        planRows.update(old.deactivate());
        assertThrows(BusinessRuleViolation.class, () -> assign.assign(workflow, b.id(), old.id()));
        assertThrows(BusinessRuleViolation.class, () -> assign.assign(workflow, b.id(), UUID.randomUUID()));
        assertEquals(null, shops.get(b.id()).planId());
    }

    @Test
    void an_unknown_barbershop_is_not_found() {
        SubscriptionPlan pro = plan("Pro");
        assertThrows(NotFound.class, () -> assign.assign(workflow, UUID.randomUUID(), pro.id()));
    }

    @Test
    void only_the_workflow_calls_it() {
        Barbershop b = shops.add("TRIAL", null, NOW);
        SubscriptionPlan pro = plan("Pro");
        assertThrows(Forbidden.class, () -> assign.assign(admin, b.id(), pro.id()));
        assertThrows(Forbidden.class, () -> assign.assign(new Caller("barber-saas-worker", Role.SERVICE, null), b.id(),
                pro.id()));
    }
}
