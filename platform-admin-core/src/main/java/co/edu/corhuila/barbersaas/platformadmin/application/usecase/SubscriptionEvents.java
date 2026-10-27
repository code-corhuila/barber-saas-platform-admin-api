package co.edu.corhuila.barbersaas.platformadmin.application.usecase;

import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops.Barbershop;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.IdGenerator;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.OutboxStore;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Owners;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PlanRepository;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two subscription events of 02-domain/domain-events.md (DEC-PLAT-07): PlanExpiringSoon and
 * BarbershopSuspended. Both carry the owners' id, name and e-mail, so notifications can reach them;
 * PlanExpiringSoon also carries the plan's name (null when the barbershop has none).
 */
public class SubscriptionEvents {

    static final String EXPIRING = "PlanExpiringSoon";
    static final String SUSPENDED = "BarbershopSuspended";

    private final OutboxStore outbox;
    private final Owners owners;
    private final PlanRepository plans;
    private final IdGenerator ids;
    private final Clock clock;

    public SubscriptionEvents(OutboxStore outbox, Owners owners, PlanRepository plans, IdGenerator ids, Clock clock) {
        this.outbox = outbox;
        this.owners = owners;
        this.plans = plans;
        this.ids = ids;
        this.clock = clock;
    }

    /** TRIAL_EXPIRED or PAYMENT_EXPIRED, written once the suspension happened in barbershop-api. */
    public void suspended(Barbershop b, String reason) {
        Map<String, Object> payload = base(b);
        payload.put("reason", reason);
        outbox.add(new OutboxEvent(ids.next(), b.id(), SUSPENDED, payload, clock.instant()));
    }

    /** False when that notice was already given for that end date. */
    public boolean expiring(Barbershop b, String kind, Instant endsAt, String threshold) {
        Map<String, Object> payload = base(b);
        payload.put("planName", b.planId() == null ? null
                : plans.findById(b.planId()).map(SubscriptionPlan::name).orElse(null));
        payload.put("kind", kind);
        payload.put("endsAt", endsAt.toString());
        payload.put("threshold", threshold);
        return outbox.addNotice(b.id(), endsAt, threshold,
                new OutboxEvent(ids.next(), b.id(), EXPIRING, payload, clock.instant()));
    }

    private Map<String, Object> base(Barbershop b) {
        List<Map<String, Object>> contacts = owners.of(b.id()).stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", o.userId().toString());
            m.put("fullName", o.fullName());
            m.put("email", o.email());
            return m;
        }).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("barbershopId", b.id().toString());
        payload.put("barbershopName", b.name());
        payload.put("owners", contacts);
        return payload;
    }
}
