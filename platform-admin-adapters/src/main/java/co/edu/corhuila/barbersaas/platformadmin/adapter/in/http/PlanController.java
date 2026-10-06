package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.Views.PageView;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.Views.PlanView;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PlanUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PlanUseCases.Created;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PlanUseCases.PlanData;
import co.edu.corhuila.barbersaas.platformadmin.domain.model.SubscriptionPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP to use case for the plans of SUPER_ADMIN (tag Plans); the public list is PublicPlanController. */
@RestController
@RequestMapping("/api/v1/platform/plans")
public class PlanController {

    private static final Set<String> FIELDS = Set.of("name", "priceCents", "maxBarbers", "featuresJson");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanUseCases plans;

    public PlanController(PlanUseCases plans) {
        this.plans = plans;
    }

    @GetMapping
    public PageView<PlanView> list(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer limit) {
        return PageView.of(plans.list(caller, Requests.page(page, limit)), PlanView::of);
    }

    @PostMapping
    public ResponseEntity<PlanView> create(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody(required = false) JsonNode json) {
        String idempotencyKey = Requests.idempotencyKey(key);
        Created<SubscriptionPlan> result = plans.create(caller, data(json), idempotencyKey);
        if (!result.created()) {
            return ResponseEntity.ok(PlanView.of(result.value()));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/platform/plans/" + result.value().id()))
                .body(PlanView.of(result.value()));
    }

    @GetMapping("/{id}")
    public PlanView get(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id) {
        return PlanView.of(plans.get(caller, id));
    }

    /** The same body as the creation; isActive does not change here (DELETE deactivates). */
    @PutMapping("/{id}")
    public PlanView update(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id,
                           @RequestBody(required = false) JsonNode json) {
        return PlanView.of(plans.update(caller, id, data(json)));
    }

    /** Deactivates, never deletes: 204, or 422 while barbershops use it (DEC-PLAT-02). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                           @PathVariable UUID id) {
        plans.deactivate(caller, id);
        return ResponseEntity.noContent().build();
    }

    /** CreateSubscriptionPlanRequest: additionalProperties false, every limit of the contract. */
    private static PlanData data(JsonNode json) {
        JsonBody body = JsonBody.of(json, FIELDS);
        String name = body.requiredText("name", SubscriptionPlan.NAME_MAX);
        Long price = body.longValue("priceCents", 0);
        Integer maxBarbers = body.integer("maxBarbers", true, 1);
        Optional<String> features = body.optionalText("featuresJson", 10_000);
        body.validate();
        String featuresJson = features == null ? null : features.orElse(null);
        if (featuresJson != null) {
            try {
                JSON.readTree(featuresJson);
            } catch (Exception e) {
                throw new ValidationException("the request is not valid",
                        List.of(new FieldError("featuresJson", "must be a JSON text")));
            }
        }
        return new PlanData(name, price == null ? 0 : price, maxBarbers == null ? 1 : maxBarbers, featuresJson);
    }
}
