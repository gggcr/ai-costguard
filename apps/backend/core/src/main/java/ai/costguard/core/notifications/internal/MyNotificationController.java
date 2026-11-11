package ai.costguard.core.notifications.internal;

import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.shared.security.TenantClaim;
import ai.costguard.platform.errors.ApiException;
import ai.costguard.platform.tenant.TenantSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The employee's own feed in «Мой кабинет» (ADR 0052): only notifications addressed to them. */
@RestController
@RequestMapping("/api/v1/me/notifications")
@Tag(name = "Cabinet", description = "The employee's own cabinet: only the person of the token")
class MyNotificationController {

    private final NotificationService service;
    private final EmployeeDirectory employees;
    private final TenantSession session;
    private final TransactionTemplate tx;

    MyNotificationController(NotificationService service, EmployeeDirectory employees, TenantSession session,
            TransactionTemplate tx) {
        this.service = service;
        this.employees = employees;
        this.session = session;
        this.tx = tx;
    }

    private UUID employee(UUID tenantId, Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        UUID id = tx.execute(s -> {
            session.bind(tenantId);
            return employees.employeeOfUser(email).or(() -> employees.employeeByEmail(email)).orElse(null);
        });
        if (id == null) {
            throw new ApiException(NotificationProblem.NOT_AN_EMPLOYEE);
        }
        return id;
    }

    @GetMapping
    @Operation(summary = "My latest 200 notifications and the unread count")
    NotificationPage page(@AuthenticationPrincipal Jwt jwt) {
        UUID tenant = TenantClaim.of(jwt);
        return service.personalPage(tenant, employee(tenant, jwt));
    }

    @PutMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark one of my notifications as read")
    void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID tenant = TenantClaim.of(jwt);
        service.markPersonalRead(tenant, employee(tenant, jwt), id);
    }

    @PutMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark all my notifications as read")
    void readAll(@AuthenticationPrincipal Jwt jwt) {
        UUID tenant = TenantClaim.of(jwt);
        service.markAllPersonalRead(tenant, employee(tenant, jwt));
    }
}
