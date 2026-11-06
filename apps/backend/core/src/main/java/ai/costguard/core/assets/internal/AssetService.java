package ai.costguard.core.assets.internal;

import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AssetService {

    private final AssetRepository assets;
    private final TenantSession session;
    private final Clock clock;

    AssetService(AssetRepository assets, TenantSession session, Clock clock) {
        this.assets = assets;
        this.session = session;
        this.clock = clock;
    }

    @Transactional
    Asset create(UUID tenantId, NewAsset asset) {
        session.bind(tenantId);
        UUID id = UUID.randomUUID();
        try {
            assets.insert(tenantId, id, asset);
        } catch (DuplicateKeyException e) {
            throw AssetProblem.INVENTORY_NUMBER_TAKEN.failure();
        }
        return found(id);
    }

    @Transactional(readOnly = true)
    Asset get(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return found(id);
    }

    private Asset found(UUID id) {
        return assets.find(id).orElseThrow(AssetProblem.ASSET_NOT_FOUND::failure);
    }

    @Transactional(readOnly = true)
    AssetPage list(UUID tenantId, AssetStatus status, int page, int size) {
        session.bind(tenantId);
        return new AssetPage(assets.list(status, page, size), page, size);
    }

    @Transactional(readOnly = true)
    List<Assignment> history(UUID tenantId, UUID assetId) {
        session.bind(tenantId);
        found(assetId);
        return assets.history(assetId);
    }

    @Transactional(readOnly = true)
    EmployeeAssets heldBy(UUID tenantId, UUID employeeId) {
        session.bind(tenantId);
        List<Asset> items = assets.heldBy(employeeId);
        BigDecimal total = items.stream().map(Asset::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new EmployeeAssets(employeeId, items, total);
    }

    /** Sets the holder. Repeating the call, or moving the return date of the same holder, is safe. */
    @Transactional
    Assignment assign(UUID tenantId, UUID assetId, NewAssignment request) {
        session.bind(tenantId);
        Asset asset = locked(assetId);
        if (asset.status() == AssetStatus.RETIRED) {
            throw AssetProblem.ASSET_RETIRED.failure();
        }
        Assignment open = asset.currentAssignment();
        if (open == null) {
            return issue(tenantId, assetId, request);
        }
        if (!open.employeeId().equals(request.employeeId())) {
            throw AssetProblem.ASSET_ALREADY_ASSIGNED.failure();
        }
        return withReturnDate(open, request.returnDue());
    }

    private Assignment issue(UUID tenantId, UUID assetId, NewAssignment request) {
        Assignment issued = new Assignment(UUID.randomUUID(), assetId, request.employeeId(), LocalDate.now(clock),
                request.returnDue(), null);
        assets.insertAssignment(tenantId, issued);
        assets.setStatus(assetId, AssetStatus.ASSIGNED);
        return issued;
    }

    private Assignment withReturnDate(Assignment open, LocalDate returnDue) {
        if (Objects.equals(open.returnDue(), returnDue)) {
            return open;
        }
        assets.moveReturnDate(open.id(), returnDue);
        return new Assignment(open.id(), open.assetId(), open.employeeId(), open.issuedAt(), returnDue, null);
    }

    /** Takes the asset back. Returning an asset nobody holds changes nothing. */
    @Transactional
    void takeBack(UUID tenantId, UUID assetId) {
        session.bind(tenantId);
        Assignment open = locked(assetId).currentAssignment();
        if (open != null) {
            assets.closeAssignment(open.id(), LocalDate.now(clock));
            assets.setStatus(assetId, AssetStatus.IN_STOCK);
        }
    }

    @Transactional
    Asset retire(UUID tenantId, UUID assetId) {
        session.bind(tenantId);
        if (locked(assetId).status() == AssetStatus.ASSIGNED) {
            throw AssetProblem.ASSET_ALREADY_ASSIGNED.failure();
        }
        assets.setStatus(assetId, AssetStatus.RETIRED);
        return found(assetId);
    }

    private Asset locked(UUID assetId) {
        return assets.findForUpdate(assetId).orElseThrow(AssetProblem.ASSET_NOT_FOUND::failure);
    }
}
