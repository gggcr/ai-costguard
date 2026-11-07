package ai.costguard.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@io.swagger.v3.oas.annotations.OpenAPIDefinition(
        info = @io.swagger.v3.oas.annotations.info.Info(title = "CostGuard Agent Gateway API", version = "v1"),
        servers = @io.swagger.v3.oas.annotations.servers.Server(url = "/"),
        security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "virtualKey"))
@io.swagger.v3.oas.annotations.security.SecurityScheme(name = "virtualKey",
        type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP, scheme = "bearer",
        description = "The agent's virtual key, cg_live_...")
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
