package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller.Role;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.MetricsUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Payments;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Payments.Revenue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * FR-023 (DEC-PLAT-09): what the operator reads first. The counts come from barbershop-api's list
 * (its totals, never its schema, ADR-004); the money from this service's own payments.
 */
public class ReadMetrics implements MetricsUseCases {

    /** Colombia has no daylight saving time: the month starts at 00:00 UTC-5. */
    static final ZoneOffset COLOMBIA = ZoneOffset.ofHours(-5);
    private static final int READ_PAGE = 100;

    private final Barbershops barbershops;
    private final Payments payments;
    private final Clock clock;

    public ReadMetrics(Barbershops barbershops, Payments payments, Clock clock) {
        this.barbershops = barbershops;
        this.payments = payments;
        this.clock = clock;
    }

    @Override
    public Metrics metrics(Caller caller) {
        caller.require(Role.SUPER_ADMIN);
        Instant now = clock.instant();
        Instant monthStart = LocalDate.ofInstant(now, COLOMBIA).withDayOfMonth(1).atStartOfDay(COLOMBIA).toInstant();
        long trial = count("TRIAL", null);
        long active = count("ACTIVE", null);
        long suspended = count("SUSPENDED", null);
        long cancelled = count("CANCELLED", null);
        Revenue revenue = payments.revenue(monthStart);
        return new Metrics(trial + active + suspended + cancelled, trial, active, suspended, cancelled,
                createdSince(monthStart), count("TRIAL", now.plus(Duration.ofDays(7))), revenue.sinceCents(),
                revenue.sinceCount(), revenue.totalCents());
    }

    /** The total of one status, read from a one-row page. */
    private long count(String status, Instant trialEndsBefore) {
        return barbershops.list(status, null, trialEndsBefore, new Page.Request(1, 1)).total();
    }

    /** The list is most recent first, so it stops at the first page that reaches an older barbershop. */
    private long createdSince(Instant since) {
        long found = 0;
        for (int page = 1; ; page++) {
            Page<Barbershop> p = barbershops.list(null, null, null, new Page.Request(page, READ_PAGE));
            for (Barbershop b : p.items()) {
                if (b.createdAt().isBefore(since)) {
                    return found;
                }
                found++;
            }
            if (p.items().isEmpty() || page >= p.totalPages()) {
                return found;
            }
        }
    }
}
