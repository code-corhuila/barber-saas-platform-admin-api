package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.RelayOutbox;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.RelayOutbox.EventEnvelope;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.SubscriptionJobs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The worker's internal operations of MVP 3 (DEC-PLAT-07, ADR-016): the subscription jobs and the
 * outbox relay. Never routed by the api-gateway; the use cases admit only barber-saas-worker.
 */
@RestController
public class InternalSubscriptionController {

    /** JobResult of platform-admin-service.yaml. */
    record JobResult(int processed, boolean remaining) {
        static JobResult of(SubscriptionJobs.Result r) {
            return new JobResult(r.processed(), r.remaining());
        }
    }

    /** ExpiredTrials of the contract, also used by the paid-month expiry. */
    record Suspended(int suspended, boolean remaining) { }

    record PendingView(List<EventEnvelope> data) { }

    private final SubscriptionJobs jobs;
    private final RelayOutbox relay;

    public InternalSubscriptionController(SubscriptionJobs jobs, RelayOutbox relay) {
        this.jobs = jobs;
        this.relay = relay;
    }

    @PostMapping("/internal/v1/subscriptions/expire")
    public Suspended expire(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                            @RequestParam(required = false) Integer limit) {
        SubscriptionJobs.Result r = jobs.expire(caller, Requests.page(1, limit).limit());
        return new Suspended(r.processed(), r.remaining());
    }

    @PostMapping("/internal/v1/subscriptions/notices")
    public JobResult notices(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                             @RequestParam(required = false) Integer limit) {
        return JobResult.of(jobs.notices(caller, Requests.page(1, limit).limit()));
    }

    @GetMapping("/internal/v1/outbox-events")
    public PendingView pending(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                               @RequestParam(required = false) Integer limit) {
        return new PendingView(relay.pending(caller, Requests.page(1, limit).limit()));
    }

    @PostMapping("/internal/v1/outbox-events/{id}/published")
    public ResponseEntity<Void> published(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                          @PathVariable UUID id) {
        relay.published(caller, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/v1/outbox-events/{id}/failed")
    public ResponseEntity<Void> failed(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                       @PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        if (body == null || body.size() != 1 || !(reason instanceof String text) || text.isBlank()
                || text.length() > 500) {
            throw new ValidationException("the request is not valid",
                    List.of(new FieldError("reason", "only reason, between 1 and 500 characters")));
        }
        relay.failed(caller, id, text);
        return ResponseEntity.noContent().build();
    }
}
