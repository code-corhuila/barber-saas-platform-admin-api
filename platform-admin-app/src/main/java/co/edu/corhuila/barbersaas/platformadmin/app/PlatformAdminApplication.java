package co.edu.corhuila.barbersaas.platformadmin.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "co.edu.corhuila.barbersaas.platformadmin")
public class PlatformAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformAdminApplication.class, args);
    }
}
