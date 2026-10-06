package co.edu.corhuila.barbersaas.platformadmin.adapter.in.http;

import co.edu.corhuila.barbersaas.platformadmin.application.port.in.Caller;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.ExpireTrials;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** POST /internal/v1/trials/expire (FR-026): the worker's daily call, never routed by the api-gateway. */
@RestController
public class InternalTrialController {

    private final ExpireTrials expireTrials;

    public InternalTrialController(ExpireTrials expireTrials) {
        this.expireTrials = expireTrials;
    }

    @PostMapping("/internal/v1/trials/expire")
    public ExpireTrials.Result expire(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                      @RequestParam(required = false) Integer limit) {
        return expireTrials.expire(caller, Requests.page(1, limit).limit());
    }
}
