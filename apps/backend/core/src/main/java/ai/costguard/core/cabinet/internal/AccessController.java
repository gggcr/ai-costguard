package ai.costguard.core.cabinet.internal;

import ai.costguard.core.shared.security.TenantClaim;
import ai.costguard.core.software.LicensePool;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.support.TransactionTemplate;
import ai.costguard.platform.tenant.TenantSession;

/** Доступ у поставщика (ADR 0065): how each subscription gives and takes back seats in the program itself. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cabinet", description = "The employee's own cabinet: only the person of the token")
class AccessController {

    private final VendorAccess access;
    private final LicensePool pool;
    private final TenantSession session;
    private final TransactionTemplate tx;
    private final int idleDays;

    AccessController(VendorAccess access, LicensePool pool, TenantSession session, TransactionTemplate tx,
            @Value("${costguard.audit.unused-days:30}") int idleDays) {
        this.access = access;
        this.pool = pool;
        this.session = session;
        this.tx = tx;
        this.idleDays = idleDays;
    }

    private boolean exists(UUID tenantId, UUID subscriptionId) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            session.bind(tenantId);
            return pool.offer(subscriptionId, idleDays).isPresent();
        }));
    }

    @GetMapping("/license-pool/{subscriptionId}/access")
    @PreAuthorize("hasAnyRole('owner', 'it_admin')")
    @Operation(summary = "How seats of this subscription are given and taken back at the vendor, and the last attempts")
    SubscriptionAccess get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID subscriptionId) {
        UUID tenant = TenantClaim.of(jwt);
        if (!exists(tenant, subscriptionId)) {
            throw CabinetProblem.SUBSCRIPTION_NOT_FOUND.failure();
        }
        return access.get(tenant, subscriptionId);
    }

    @PutMapping("/license-pool/{subscriptionId}/access")
    @PreAuthorize("hasAnyRole('owner', 'it_admin')")
    @Operation(summary = "Give and take back seats through Microsoft Entra or the vendor's SCIM API, or by hand")
    SubscriptionAccess save(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID subscriptionId,
            @Valid @RequestBody AccessSettings settings) {
        UUID tenant = TenantClaim.of(jwt);
        return access.save(tenant, subscriptionId, settings, exists(tenant, subscriptionId));
    }

    @GetMapping("/microsoft/apps")
    @PreAuthorize("hasAnyRole('owner', 'it_admin')")
    @Operation(summary = "The enterprise applications of the company's Microsoft Entra ID")
    List<EnterpriseApp> apps(@AuthenticationPrincipal Jwt jwt) {
        return access.apps(TenantClaim.of(jwt));
    }
}
