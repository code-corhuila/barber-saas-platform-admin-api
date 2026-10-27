package co.edu.corhuila.barbersaas.platformadmin.application.port.in;

/** The platform-wide metrics of FR-023 (DEC-PLAT-09), for SUPER_ADMIN only. */
public interface MetricsUseCases {

    /**
     * PlatformMetrics of platform-admin-service.yaml. "This month" starts on day 1 at 00:00 in Colombia
     * (UTC-5); the revenue is the APPROVED plan payments, counted when they were approved.
     */
    record Metrics(long barbershops, long trial, long active, long suspended, long cancelled, long newThisMonth,
                   long trialsEndingIn7Days, long revenueThisMonthCents, long paymentsThisMonth,
                   long revenueTotalCents) { }

    Metrics metrics(Caller caller);
}
