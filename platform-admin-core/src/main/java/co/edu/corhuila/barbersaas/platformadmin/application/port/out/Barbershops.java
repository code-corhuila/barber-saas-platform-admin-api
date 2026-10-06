package co.edu.corhuila.barbersaas.platformadmin.application.port.out;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Page;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * barbershop-api's internal operations for platform-admin (DEC-SHOP-06, barbershop-service.yaml 1.4.0),
 * called with this service's own token. barbershop keeps the row and the lifecycle; this side never
 * reads or writes its schema (ADR-004, ADR-011). An adapter throws ApplicationException.NotFound (404),
 * InvalidStatusTransition (409) or Unavailable (no answer, or any other status).
 */
public interface Barbershops {

    /** The Barbershop schema of barbershop-service.yaml, as platform-admin shows it. */
    record Barbershop(UUID id, String name, String address, String city, BigDecimal latitude, BigDecimal longitude,
                      String phone, String whatsappNumber, String logoUrl, String status, UUID planId,
                      String timezone, int cancellationPolicyHours, Instant trialEndsAt, Instant createdAt,
                      Instant updatedAt) { }

    /** CreateBarbershopRequest of barbershop-service.yaml: what the internal create accepts. */
    record NewBarbershop(String name, String city, String address, String phone, BigDecimal latitude,
                         BigDecimal longitude) { }

    /** The barbershop and whether barbershop created it now (201) or answered a retried key (200). */
    record Created(Barbershop barbershop, boolean created) { }

    /** Any status (null = all), plan or trial end; most recent first. */
    Page<Barbershop> list(String status, UUID planId, Instant trialEndsBefore, Page.Request page);

    Barbershop get(UUID id);

    Barbershop changeStatus(UUID id, String status);

    Barbershop assignPlan(UUID id, UUID planId);

    /** In TRIAL, trialEndsAt = createdAt + 60 days (INV-SHOP-001); idempotent with the same key. */
    Created create(NewBarbershop barbershop, String idempotencyKey);
}
