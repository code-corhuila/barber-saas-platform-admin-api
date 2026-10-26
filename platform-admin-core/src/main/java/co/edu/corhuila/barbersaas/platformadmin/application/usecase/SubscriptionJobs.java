package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Payments;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * The worker's daily calls of DEC-PLAT-07 (HU-SADMIN-004): a paid month that ended suspends the
 * barbershop, and the trial or the paid month that ends in 7 days or 1 day is announced once. One
 * barbershop that fails is logged and skipped; the batch goes on (finding of #111).
 */
public class SubscriptionJobs {

    /** How many barbershops a call handles at most, and whether more are left for the next call. */
    public record Result(int processed, boolean remaining) { }

    static final int READ_PAGE = 100;
    private static final Logger LOG = Logger.getLogger(SubscriptionJobs.class.getName());

    private final Barbershops barbershops;
    private final Payments payments;
    private final SubscriptionEvents events;
    private final Clock clock;

    public SubscriptionJobs(Barbershops barbershops, Payments payments, SubscriptionEvents events, Clock clock) {
        this.barbershops = barbershops;
        this.payments = payments;
        this.events = events;
        this.clock = clock;
    }

    /** ACTIVE with a paidUntil in the past: SUSPENDED. ACTIVE without any payment was activated by hand: kept. */
    public Result expire(Caller caller, int limit) {
        caller.requireService(ExpireTrials.WORKER);
        Instant now = clock.instant();
        int suspended = 0;
        for (Barbershop b : all("ACTIVE", null)) {
            Optional<Instant> paidUntil = payments.paidUntil(b.id());
            if (paidUntil.isEmpty() || paidUntil.get().isAfter(now)) {
                continue;
            }
            if (suspended == limit) {
                return new Result(suspended, true);
            }
            try {
                events.suspended(barbershops.changeStatus(b.id(), "SUSPENDED"), "PAYMENT_EXPIRED");
                suspended++;
            } catch (RuntimeException e) {
                LOG.warning("barbershop " + b.id() + " was not suspended: " + e.getMessage());
            }
        }
        return new Result(suspended, false);
    }

    /** PlanExpiringSoon at 7 days and at 1 day before trialEndsAt (TRIAL) or paidUntil (ACTIVE). */
    public Result notices(Caller caller, int limit) {
        caller.requireService(ExpireTrials.WORKER);
        Instant now = clock.instant();
        int written = 0;
        List<Barbershop> candidates = new ArrayList<>(all("TRIAL", now.plus(Duration.ofDays(7))));
        candidates.addAll(all("ACTIVE", null));
        for (Barbershop b : candidates) {
            String kind = "TRIAL".equals(b.status()) ? "TRIAL" : "PAID";
            Instant endsAt = "TRIAL".equals(kind) ? b.trialEndsAt() : payments.paidUntil(b.id()).orElse(null);
            String threshold = endsAt == null ? null : threshold(now, endsAt);
            if (threshold == null) {
                continue;
            }
            if (written == limit) {
                return new Result(written, true);
            }
            try {
                if (events.expiring(b, kind, endsAt, threshold)) {
                    written++;
                }
            } catch (RuntimeException e) {
                LOG.warning("no notice for barbershop " + b.id() + ": " + e.getMessage());
            }
        }
        return new Result(written, false);
    }

    /** DAYS_1 within a day of the end, DAYS_7 within a week; nothing once it ended or further away. */
    static String threshold(Instant now, Instant endsAt) {
        if (!endsAt.isAfter(now)) {
            return null;
        }
        Duration left = Duration.between(now, endsAt);
        if (left.compareTo(Duration.ofDays(1)) <= 0) {
            return "DAYS_1";
        }
        return left.compareTo(Duration.ofDays(7)) <= 0 ? "DAYS_7" : null;
    }

    private List<Barbershop> all(String status, Instant trialEndsBefore) {
        List<Barbershop> found = new ArrayList<>();
        for (int page = 1; ; page++) {
            Page<Barbershop> p = barbershops.list(status, null, trialEndsBefore, new Page.Request(page, READ_PAGE));
            found.addAll(p.items());
            if (p.items().isEmpty() || page >= p.totalPages()) {
                return found;
            }
        }
    }
}
