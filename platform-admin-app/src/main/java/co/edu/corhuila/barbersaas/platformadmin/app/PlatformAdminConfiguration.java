package co.edu.corhuila.barbersaas.platformadmin.app;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.AuthFilter;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.PaymentSimulatorController.PaymentMode;
import co.edu.corhuila.barbersaas.platformadmin.adapter.out.wompi.NoCheckout;
import co.edu.corhuila.barbersaas.platformadmin.adapter.out.wompi.SimulatedCheckout;
import co.edu.corhuila.barbersaas.platformadmin.adapter.out.http.HttpOwners;
import co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence.InMemoryOutbox;
import co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence.JdbcOutbox;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.OutboxStore;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.RelayOutbox;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.SubscriptionEvents;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.SubscriptionJobs;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.WompiSignature;
import co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence.InMemoryPayments;
import co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence.JdbcPayments;
import co.edu.corhuila.barbersaas.platformadmin.adapter.out.wompi.WompiCheckout;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PaymentUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PaymentProvider;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Payments;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.ManagePayments;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.Rs256Verifier;
import co.edu.corhuila.barbersaas.platformadmin.adapter.out.http.HttpBarbershops;
import co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence.InMemoryPlanRepository;
import co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence.JdbcPlanRepository;
import co.edu.corhuila.barbersaas.platformadmin.adapter.out.persistence.UuidGenerator;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.BarbershopUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.port.in.PlanUseCases;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.Barbershops;
import co.edu.corhuila.barbersaas.platformadmin.application.port.out.PlanRepository;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.AssignOnboardingPlan;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.ExpireTrials;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.ManageBarbershops;
import co.edu.corhuila.barbersaas.platformadmin.application.usecase.ManagePlans;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Composition root: the only place that knows every concrete type. The pool and its limits are
 * built here explicitly (norm 5.3.10), so they are read in code review instead of hidden in defaults.
 */
@Configuration
public class PlatformAdminConfiguration {

    /** The JDBC access, or none when DATABASE_URL is empty (in-memory repositories, no database needed). */
    record Database(JdbcTemplate jdbc, TransactionTemplate tx) {
        Optional<Database> present() {
            return jdbc == null ? Optional.empty() : Optional.of(this);
        }
    }

    @Bean
    Database database(@Value("${platform-admin.database.url:}") String url,
                      @Value("${platform-admin.database.user:}") String user,
                      @Value("${platform-admin.database.password:}") String password,
                      @Value("${platform-admin.database.pool-max:10}") int poolMax,
                      @Value("${platform-admin.database.statement-timeout-ms:5000}") int statementTimeoutMs) {
        if (url.isBlank()) {
            return new Database(null, null);
        }
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url);
        pool.setUsername(user);                                       // platform_admin_app, never the administrator
        pool.setPassword(password);
        pool.setMaximumPoolSize(poolMax);
        pool.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
        pool.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        pool.setConnectionInitSql("SET statement_timeout = " + statementTimeoutMs);
        HikariDataSource dataSource = new HikariDataSource(pool);
        return new Database(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Bean
    PlanRepository planRepository(Database database) {
        return database.present().<PlanRepository>map(d -> new JdbcPlanRepository(d.jdbc(), d.tx()))
                .orElseGet(InMemoryPlanRepository::new);
    }

    @Bean
    PlanUseCases planUseCases(PlanRepository plans, Barbershops barbershops) {
        return new ManagePlans(plans, barbershops, new UuidGenerator(), Clock.systemUTC());
    }

    /** barbershop-api's internal operations with this service's own token; unset, those screens answer 503. */
    @Bean
    Barbershops barbershops(@Value("${platform-admin.barbershop-api-url:}") String url,
                            @Value("${platform-admin.service-token:}") String serviceToken) {
        return new HttpBarbershops(url, serviceToken);
    }

    @Bean
    BarbershopUseCases barbershopUseCases(Barbershops barbershops, PlanRepository plans) {
        return new ManageBarbershops(barbershops, plans, Clock.systemUTC());
    }

    @Bean
    Payments payments(Database database) {
        return database.present().<Payments>map(d -> new JdbcPayments(d.jdbc(), d.tx()))
                .orElseGet(InMemoryPayments::new);
    }

    /**
     * DEC-PLAT-08: without a Wompi public key payments go to the simulated checkout only when the host
     * asks for it (PAYMENTS_SIMULATOR=true); with neither, no payment can be started.
     */
    @Bean
    PaymentMode paymentMode(@Value("${platform-admin.wompi.public-key:}") String publicKey,
                            @Value("${platform-admin.payments.simulator:false}") boolean simulator) {
        return new PaymentMode(publicKey.isBlank() && simulator);
    }

    /** Wompi in sandbox (ADR-017): keys and secrets from the environment, never versioned. */
    @Bean
    PaymentUseCases paymentUseCases(Payments payments, PlanRepository plans, Barbershops barbershops, PaymentMode mode,
                                    @Value("${platform-admin.wompi.checkout-url:}") String checkoutUrl,
                                    @Value("${platform-admin.wompi.public-key:}") String publicKey,
                                    @Value("${platform-admin.wompi.integrity-secret:}") String integritySecret,
                                    @Value("${platform-admin.wompi.redirect-url:}") String redirectUrl) {
        PaymentProvider provider = !publicKey.isBlank()
                ? new WompiCheckout(checkoutUrl, publicKey, integritySecret, redirectUrl)
                : mode.simulated() ? new SimulatedCheckout() : new NoCheckout();
        return new ManagePayments(payments, plans, barbershops, provider, new UuidGenerator(), Clock.systemUTC());
    }

    @Bean
    WompiSignature wompiSignature(@Value("${platform-admin.wompi.events-secret:}") String eventsSecret) {
        return new WompiSignature(eventsSecret);
    }

    @Bean
    OutboxStore outboxStore(Database database, ObjectMapper json) {
        return database.present().<OutboxStore>map(d -> new JdbcOutbox(d.jdbc(), d.tx(), json))
                .orElseGet(InMemoryOutbox::new);
    }

    /** identity-auth's owners read (DEC-AUTH-10), with this service's own token. */
    @Bean
    SubscriptionEvents subscriptionEvents(OutboxStore outbox, PlanRepository plans,
                                          @Value("${platform-admin.identity-auth-api-url:}") String identityAuthUrl,
                                          @Value("${platform-admin.service-token:}") String serviceToken) {
        return new SubscriptionEvents(outbox, new HttpOwners(identityAuthUrl, serviceToken), plans,
                new UuidGenerator(), Clock.systemUTC());
    }

    @Bean
    SubscriptionJobs subscriptionJobs(Barbershops barbershops, Payments payments, SubscriptionEvents events) {
        return new SubscriptionJobs(barbershops, payments, events, Clock.systemUTC());
    }

    @Bean
    RelayOutbox relayOutbox(OutboxStore outbox) {
        return new RelayOutbox(outbox, Clock.systemUTC());
    }

    @Bean
    ExpireTrials expireTrials(Barbershops barbershops, SubscriptionEvents events) {
        return new ExpireTrials(barbershops, Clock.systemUTC(), events);
    }

    @Bean
    AssignOnboardingPlan assignOnboardingPlan(Barbershops barbershops, PlanRepository plans) {
        return new AssignOnboardingPlan(barbershops, plans);
    }

    /** JWT_PUBLIC_KEY: the PEM itself; a one-line value with literal \n escapes, as an env file holds it, is accepted. */
    @Bean
    Rs256Verifier tokenVerifier(@Value("${JWT_PUBLIC_KEY:}") String pem) {
        return new Rs256Verifier(pem.replace("\n", "\n"));
    }

    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> bean = new FilterRegistrationBean<>(new CorrelationFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(Rs256Verifier verifier, ObjectMapper json) {
        FilterRegistrationBean<AuthFilter> bean = new FilterRegistrationBean<>(new AuthFilter(verifier, json));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
