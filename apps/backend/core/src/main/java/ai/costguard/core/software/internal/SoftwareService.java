package ai.costguard.core.software.internal;

import ai.costguard.platform.tenant.TenantSession;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SoftwareService {

    private final SoftwareRepository software;
    private final TenantSession session;

    SoftwareService(SoftwareRepository software, TenantSession session) {
        this.software = software;
        this.session = session;
    }

    @Transactional(readOnly = true)
    List<Vendor> vendors(UUID tenantId) {
        session.bind(tenantId);
        return software.vendors();
    }

    @Transactional
    Vendor createVendor(UUID tenantId, VendorDetails details) {
        session.bind(tenantId);
        UUID id = UUID.randomUUID();
        try {
            software.insertVendor(tenantId, id, details);
        } catch (DuplicateKeyException e) {
            throw SoftwareProblem.VENDOR_NAME_TAKEN.failure();
        }
        return software.vendor(id).orElseThrow();
    }

    @Transactional
    Vendor updateVendor(UUID tenantId, UUID id, VendorDetails details) {
        session.bind(tenantId);
        try {
            if (!software.updateVendor(id, details)) {
                throw SoftwareProblem.VENDOR_NOT_FOUND.failure();
            }
        } catch (DuplicateKeyException e) {
            throw SoftwareProblem.VENDOR_NAME_TAKEN.failure();
        }
        return software.vendor(id).orElseThrow();
    }

    private void requireVendor(UUID id) {
        software.vendor(id).orElseThrow(SoftwareProblem.VENDOR_NOT_FOUND::failure);
    }

    @Transactional(readOnly = true)
    List<Contract> contracts(UUID tenantId, UUID vendorId) {
        session.bind(tenantId);
        return software.contracts(vendorId);
    }

    @Transactional
    Contract createContract(UUID tenantId, ContractDetails details) {
        checkDates(details);
        session.bind(tenantId);
        requireVendor(details.vendorId());
        UUID id = UUID.randomUUID();
        try {
            software.insertContract(tenantId, id, details);
        } catch (DuplicateKeyException e) {
            throw SoftwareProblem.CONTRACT_NUMBER_TAKEN.failure();
        }
        return software.contract(id).orElseThrow();
    }

    @Transactional
    Contract updateContract(UUID tenantId, UUID id, ContractDetails details) {
        checkDates(details);
        session.bind(tenantId);
        requireVendor(details.vendorId());
        try {
            if (!software.updateContract(id, details)) {
                throw SoftwareProblem.CONTRACT_NOT_FOUND.failure();
            }
        } catch (DuplicateKeyException e) {
            throw SoftwareProblem.CONTRACT_NUMBER_TAKEN.failure();
        }
        return software.contract(id).orElseThrow();
    }

    private static void checkDates(ContractDetails details) {
        if (details.endDate().isBefore(details.startDate())) {
            throw SoftwareProblem.DATES_REVERSED.failure();
        }
    }

    @Transactional(readOnly = true)
    List<Subscription> subscriptions(UUID tenantId) {
        session.bind(tenantId);
        return software.subscriptions();
    }

    @Transactional(readOnly = true)
    Subscription subscription(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return software.subscription(id).orElseThrow(SoftwareProblem.SUBSCRIPTION_NOT_FOUND::failure);
    }

    private void checkSubscription(SubscriptionDetails details) {
        requireVendor(details.vendorId());
        if (details.contractId() != null) {
            Contract contract = software.contract(details.contractId())
                    .orElseThrow(SoftwareProblem.CONTRACT_NOT_FOUND::failure);
            if (!contract.vendorId().equals(details.vendorId())) {
                throw SoftwareProblem.CONTRACT_OF_ANOTHER_VENDOR.failure();
            }
        }
    }

    @Transactional
    Subscription createSubscription(UUID tenantId, SubscriptionDetails details) {
        session.bind(tenantId);
        checkSubscription(details);
        UUID id = UUID.randomUUID();
        software.insertSubscription(tenantId, id, details);
        return software.subscription(id).orElseThrow();
    }

    @Transactional
    Subscription updateSubscription(UUID tenantId, UUID id, SubscriptionDetails details) {
        session.bind(tenantId);
        Subscription current = software.subscriptionForUpdate(id)
                .orElseThrow(SoftwareProblem.SUBSCRIPTION_NOT_FOUND::failure);
        checkSubscription(details);
        if (details.seats() < current.assigned()) {
            throw SoftwareProblem.SEATS_IN_USE.failure();
        }
        software.updateSubscription(id, details);
        return software.subscription(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    List<License> licenses(UUID tenantId, UUID subscriptionId) {
        session.bind(tenantId);
        software.subscription(subscriptionId).orElseThrow(SoftwareProblem.SUBSCRIPTION_NOT_FOUND::failure);
        return software.licenses(subscriptionId);
    }

    /** Giving a license to someone who already has it changes nothing. */
    @Transactional
    License assign(UUID tenantId, UUID subscriptionId, UUID employeeId) {
        session.bind(tenantId);
        Subscription subscription = software.subscriptionForUpdate(subscriptionId)
                .orElseThrow(SoftwareProblem.SUBSCRIPTION_NOT_FOUND::failure);
        var existing = software.licenseOf(subscriptionId, employeeId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (subscription.assigned() >= subscription.seats()) {
            throw SoftwareProblem.SEATS_EXHAUSTED.failure();
        }
        UUID id = UUID.randomUUID();
        software.insertLicense(tenantId, id, subscriptionId, employeeId);
        return software.license(id).orElseThrow();
    }

    @Transactional
    void revoke(UUID tenantId, UUID licenseId) {
        session.bind(tenantId);
        if (!software.deleteLicense(licenseId)) {
            throw SoftwareProblem.LICENSE_NOT_FOUND.failure();
        }
    }

    /** Activity only moves forward, so a late report from a connector never hides a newer login. */
    @Transactional
    License recordActivity(UUID tenantId, UUID licenseId, Activity activity) {
        session.bind(tenantId);
        if (!software.setActivity(licenseId, activity.lastActive())) {
            throw SoftwareProblem.LICENSE_NOT_FOUND.failure();
        }
        return software.license(licenseId).orElseThrow();
    }
}
