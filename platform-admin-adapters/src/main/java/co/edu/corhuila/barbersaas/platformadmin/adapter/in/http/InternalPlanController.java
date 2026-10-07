package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.AssignOnboardingPlan;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUT /internal/v1/barbershops/{id}/plan (DEC-PLAT-04): step assign-plan of the owner-onboarding
 * saga, never routed by the api-gateway. The use case admits only the workflow's token.
 */
@RestController
public class InternalPlanController {

    private final AssignOnboardingPlan assign;

    public InternalPlanController(AssignOnboardingPlan assign) {
        this.assign = assign;
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
