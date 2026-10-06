package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.Forbidden;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExpireTrialsTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private final FakeBarbershops shops = new FakeBarbershops();
    private final ExpireTrials expire = new ExpireTrials(shops, Clock.fixed(NOW, ZoneOffset.UTC));
    private final Caller worker = new Caller(ExpireTrials.WORKER, Role.SERVICE, null);

    @Test
    void an_expired_trial_is_suspended_and_a_running_one_is_not() {
        Barbershop expired = shops.add("TRIAL", null, NOW.minusSeconds(86_400 * 61));
        Barbershop running = shops.add("TRIAL", null, NOW.minusSeconds(86_400 * 5));
        Barbershop active = shops.add("ACTIVE", null, NOW.minusSeconds(86_400 * 90));
        ExpireTrials.Result result = expire.expire(worker, 50);
        assertEquals(1, result.suspended());
        assertFalse(result.remaining());
        assertEquals("SUSPENDED", shops.get(expired.id()).status());
        assertEquals("TRIAL", shops.get(running.id()).status());
        assertEquals("ACTIVE", shops.get(active.id()).status());
    }

    @Test
    void a_call_is_bounded_and_a_repeated_call_suspends_nothing_new() {
        for (int i = 0; i < 3; i++) {
            shops.add("TRIAL", null, NOW.minusSeconds(86_400 * (61 + i)));
        }
        ExpireTrials.Result first = expire.expire(worker, 2);
        assertEquals(2, first.suspended());
        assertTrue(first.remaining());
        assertEquals(1, expire.expire(worker, 2).suspended());
        assertEquals(0, expire.expire(worker, 2).suspended());
    }

    @Test
    void only_the_worker_runs_it() {
        Caller admin = new Caller(UUID.randomUUID().toString(), Role.SUPER_ADMIN, null);
        Caller other = new Caller("barber-saas-workflow", Role.SERVICE, null);
        assertThrows(Forbidden.class, () -> expire.expire(admin, 10));
        assertThrows(Forbidden.class, () -> expire.expire(other, 10));
    }
}
