package co.edu.corhuila.barbersaas.platformadmin.application.port.in;

import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.NewBarbershop;
import java.time.Instant;
import java.util.UUID;

/** The barbershops of the platform, for SUPER_ADMIN only (FR-023, FR-025, DEC-PLAT-01). */
public interface BarbershopUseCases {

    /** TrialStatus of platform-admin-service.yaml, read from the stored trialEndsAt (DEC-PLAT-03). */
    record Trial(UUID barbershopId, String status, Instant trialStartedAt, Instant trialEndsAt, int daysRemaining,
                 boolean expired) { }

    /** The barbershop and whether it was created now (201) or the same key was retried (200). */
    record Onboarded(Barbershop barbershop, boolean created) { }

    Page<Barbershop> list(Caller caller, String status, Page.Request page);

    Barbershop get(Caller caller, UUID id);

    Trial trial(Caller caller, UUID id);

    /** ACTIVE, SUSPENDED or CANCELLED; barbershop-api enforces the lifecycle (409). */
    Barbershop changeStatus(Caller caller, UUID id, String status);

    /** The plan must exist (404) and be active (422, INV-SHOP-003). */
    Barbershop assignPlan(Caller caller, UUID id, UUID planId);

    /** Created in TRIAL by barbershop-api, then given its plan; the same key returns the same barbershop. */
    Onboarded onboard(Caller caller, NewBarbershop barbershop, UUID planId, String idempotencyKey);
}
