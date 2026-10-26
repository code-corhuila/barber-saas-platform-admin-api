package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.Views.PlanView;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PlanUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.AssignOnboardingPlan;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUT /internal/v1/barbershops/{id}/plan (DEC-PLAT-04): step assign-plan of the owner-onboarding
 * saga, and GET /internal/v1/plans/{id} for barbershop-api's barber limit (DEC-SHOP-07). Never routed
 * by the api-gateway; each use case admits only its caller's token.
 */
@RestController
public class InternalPlanController {

    private final AssignOnboardingPlan assign;
    private final PlanUseCases plans;

    public InternalPlanController(AssignOnboardingPlan assign, PlanUseCases plans) {
        this.assign = assign;
        this.plans = plans;
    }

    @GetMapping("/internal/v1/plans/{id}")
    public PlanView getPlan(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id) {
        return PlanView.of(plans.getForBarbershopApi(caller, id));
    }

    @PutMapping("/internal/v1/barbershops/{id}/plan")
    public ResponseEntity<Void> assignPlan(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                           @PathVariable UUID id, @RequestBody(required = false) JsonNode json) {
        JsonBody body = JsonBody.of(json, Set.of("planId"));
        UUID planId = body.uuid("planId");
        body.validate();
        assign.assign(caller, id, planId);
        return ResponseEntity.noContent().build();
    }
}
