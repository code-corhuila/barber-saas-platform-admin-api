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
 * idempotent: a barbershop that is no longer TRIAL is not listed again. Each suspension writes
 * BarbershopSuspended (DEC-PLAT-07); one barbershop that fails does not stop the others (#111).
 */
public class ExpireTrials {

    public static final String WORKER = "barber-saas-worker";
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(ExpireTrials.class.getName());

    /** ExpiredTrials of platform-admin-service.yaml. */
    public record Result(int suspended, boolean remaining) { }

    private final Barbershops barbershops;
    private final Clock clock;
    private final SubscriptionEvents events;

    public ExpireTrials(Barbershops barbershops, Clock clock) {
        this(barbershops, clock, null);
    }

    /** {@code events} null: no BarbershopSuspended is written (tests of the rule alone). */
    public ExpireTrials(Barbershops barbershops, Clock clock, SubscriptionEvents events) {
        this.barbershops = barbershops;
        this.clock = clock;
        this.events = events;
    }

    public Result expire(Caller caller, int limit) {
        caller.requireService(WORKER);
        Page<Barbershop> due = barbershops.list("TRIAL", null, clock.instant(), new Page.Request(1, limit));
        int suspended = 0;
        for (Barbershop b : due.items()) {
            try {
                Barbershop now = barbershops.changeStatus(b.id(), "SUSPENDED");
                suspended++;
                if (events != null) {
                    events.suspended(now, "TRIAL_EXPIRED");
                }
            } catch (InvalidStatusTransition e) {
                // Converted or cancelled in between: nothing to suspend.
            } catch (RuntimeException e) {
                LOG.warning("trial of barbershop " + b.id() + " not expired: " + e.getMessage());
            }
        }
        return new Result(suspended, due.total() > due.items().size());
    }
}
