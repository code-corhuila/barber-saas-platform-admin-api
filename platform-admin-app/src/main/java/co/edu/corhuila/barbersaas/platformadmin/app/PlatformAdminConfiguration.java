package co.edu.corhuila.barbersaas.platformadmin.app;

import co.edu.corhuila.barbersaas.platformadmin.adapter.in.http.AuthFilter;
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
    ExpireTrials expireTrials(Barbershops barbershops) {
        return new ExpireTrials(barbershops, Clock.systemUTC());
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
