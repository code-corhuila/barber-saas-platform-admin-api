package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.BusinessRuleViolation;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.InvalidStatusTransition;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.NotFound;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.BarbershopUseCases.Onboarded;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.BarbershopUseCases.Trial;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PlanUseCases.PlanData;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.NewBarbershop;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManageBarbershopsTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private final FakeBarbershops shops = new FakeBarbershops();
    private final ManagePlansTest.FakePlans planRows = new ManagePlansTest.FakePlans();
    private final ManagePlans plans = new ManagePlans(planRows, shops, UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ManageBarbershops barbershops = new ManageBarbershops(shops, planRows, Clock.fixed(NOW, ZoneOffset.UTC));
    private final Caller admin = new Caller(UUID.randomUUID().toString(), Role.SUPER_ADMIN, null);

    private SubscriptionPlan plan(String name) {
        return plans.create(admin, new PlanData(name, 100, 2, null), "key-" + UUID.randomUUID()).value();
    }

    @Test
    void the_trial_counts_the_days_left_and_says_when_it_expired() {
        Barbershop recent = shops.add("TRIAL", null, NOW.minusSeconds(86_400 * 10));
        Barbershop old = shops.add("TRIAL", null, NOW.minusSeconds(86_400 * 61));
        Trial left = barbershops.trial(admin, recent.id());
        Trial expired = barbershops.trial(admin, old.id());
        assertEquals(50, left.daysRemaining());
        assertFalse(left.expired());
        assertEquals(0, expired.daysRemaining());
        assertTrue(expired.expired());
    }

    @Test
    void only_an_active_plan_can_be_assigned() {
        Barbershop b = shops.add("ACTIVE", null, NOW);
        SubscriptionPlan pro = plan("Pro");
        assertEquals(pro.id(), barbershops.assignPlan(admin, b.id(), pro.id()).planId());
        planRows.update(pro.deactivate());
        assertThrows(BusinessRuleViolation.class, () -> barbershops.assignPlan(admin, b.id(), pro.id()));
        assertThrows(NotFound.class, () -> barbershops.assignPlan(admin, b.id(), UUID.randomUUID()));
    }

    @Test
    void the_lifecycle_of_barbershop_api_is_kept() {
        Barbershop b = shops.add("TRIAL", null, NOW);
        assertEquals("SUSPENDED", barbershops.changeStatus(admin, b.id(), "SUSPENDED").status());
        assertEquals("CANCELLED", barbershops.changeStatus(admin, b.id(), "CANCELLED").status());
        assertThrows(InvalidStatusTransition.class, () -> barbershops.changeStatus(admin, b.id(), "ACTIVE"));
    }

    @Test
    void onboarding_creates_in_trial_with_its_plan_and_a_retry_returns_the_same_barbershop() {
        SubscriptionPlan basico = plan("Basico");
        NewBarbershop data = new NewBarbershop("La Navaja", "Neiva", null, "3001234567", null, null);
        Onboarded first = barbershops.onboard(admin, data, basico.id(), "key-00000001");
        Onboarded again = barbershops.onboard(admin, data, basico.id(), "key-00000001");
        assertTrue(first.created());
        assertEquals("TRIAL", first.barbershop().status());
        assertEquals(basico.id(), first.barbershop().planId());
        assertFalse(again.created());
        assertEquals(first.barbershop().id(), again.barbershop().id());
    }

    @Test
    void a_plan_in_use_cannot_be_deactivated() {
        SubscriptionPlan pro = plan("Pro");
        SubscriptionPlan unused = plan("Unused");
        shops.add("ACTIVE", pro.id(), NOW);
        assertThrows(BusinessRuleViolation.class, () -> plans.deactivate(admin, pro.id()));
        plans.deactivate(admin, unused.id());
        assertFalse(plans.get(admin, unused.id()).active());
    }

    @Test
    void only_super_admin_sees_the_barbershops_of_the_platform() {
        Caller owner = new Caller(UUID.randomUUID().toString(), Role.ADMIN_BARBERSHOP, UUID.randomUUID());
        assertThrows(Forbidden.class, () -> barbershops.list(owner, null, new Page.Request(1, 20)));
        assertThrows(NotFound.class, () -> barbershops.get(admin, UUID.randomUUID()));
    }
}
