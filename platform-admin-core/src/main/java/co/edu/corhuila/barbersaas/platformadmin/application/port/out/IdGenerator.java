package co.edu.corhuila.barbersaas.platformadmin.application.port.out;

import java.util.UUID;

public interface IdGenerator {

    UUID next();
}
