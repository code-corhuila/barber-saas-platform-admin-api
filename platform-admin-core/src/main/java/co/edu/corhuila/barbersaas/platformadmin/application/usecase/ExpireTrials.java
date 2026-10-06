package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.ApplicationException.InvalidStatusTransition;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import java.time.Clock;

/**
 * FR-026: a barbershop whose 60-day trial ended without conversion goes from TRIAL to SUSPENDED.
 * The worker calls it once a day (POST /internal/v1/trials/expire); bounded by {@code limit} and
 * idempotent: a barbershop that is no longer TRIAL is not listed again.
 */
public class ExpireTrials {

    public static final String WORKER = "barber-saas-worker";

    /** ExpiredTrials of platform-admin-service.yaml. */
    public record Result(int suspended, boolean remaining) { }

    private final Barbershops barbershops;
    private final Clock clock;

    public ExpireTrials(Barbershops barbershops, Clock clock) {
        this.barbershops = barbershops;
        this.clock = clock;
    }

    public Result expire(Caller caller, int limit) {
        caller.requireService(WORKER);
        Page<Barbershop> due = barbershops.list("TRIAL", null, clock.instant(), new Page.Request(1, limit));
        int suspended = 0;
        for (Barbershop b : due.items()) {
            try {
                barbershops.changeStatus(b.id(), "SUSPENDED");
                suspended++;
            } catch (InvalidStatusTransition e) {
                // Converted or cancelled in between: nothing to suspend.
            }
        }
        return new Result(suspended, due.total() > due.items().size());
    }
}
