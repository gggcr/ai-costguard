package ai.costguard.core.assets.internal;

import ai.costguard.core.assets.AssetChanged;
import ai.costguard.core.assets.AssetLinks;
import ai.costguard.core.org.EmployeeDirectory;
import ai.costguard.core.org.EmployeeDirectory.EmployeeCard;
import ai.costguard.core.shared.activity.ActionTaken;
import ai.costguard.platform.tenant.TenantSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AssetService {

    /** {@code issued_by} of an issue a connector reported; the act leaves the issuer's name for a pen. */
    static final String CONNECTOR = "connector:";

    private final AssetRepository assets;
    private final HandoverActs acts;
    private final EmployeeDirectory people;
    private final TenantSession session;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final ReturnedEquipment returned;

    AssetService(AssetRepository assets, HandoverActs acts, EmployeeDirectory people, TenantSession session,
            Clock clock, ApplicationEventPublisher events, ReturnedEquipment returned) {
        this.returned = returned;
        this.assets = assets;
        this.acts = acts;
        this.people = people;
        this.session = session;
        this.clock = clock;
        this.events = events;
    }

    private void announce(UUID tenantId, UUID assetId) {
        Asset a = found(assetId);
        Assignment open = a.currentAssignment();
        events.publishEvent(new AssetChanged(tenantId, a.id(), a.inventoryNumber(), a.model(), a.type().name(),
                a.cost(), a.status().name(), open == null ? null : open.employeeId(),
                open == null ? null : open.issuedAt(), open == null ? null : open.returnDue(), clock.instant()));
    }

    @Transactional
    Asset create(UUID tenantId, NewAsset asset) {
        session.bind(tenantId);
        UUID id = UUID.randomUUID();
        try {
            assets.insert(tenantId, id, asset);
        } catch (DuplicateKeyException e) {
            throw String.valueOf(e.getMessage()).contains("asset_serial_number")
                    ? AssetProblem.SERIAL_NUMBER_TAKEN.failure()
                    : AssetProblem.INVENTORY_NUMBER_TAKEN.failure();
        }
        announce(tenantId, id);
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

    /**
     * Sets the holder and makes the handover act of the issue. Repeating the call, or moving the return date of
     * the same holder, is safe and makes no new act.
     */
    @Transactional
    Assignment assign(UUID tenantId, UUID assetId, NewAssignment request, String issuedBy) {
        session.bind(tenantId);
        Asset asset = locked(assetId);
        if (asset.status() == AssetStatus.RETIRED) {
            throw AssetProblem.ASSET_RETIRED.failure();
        }
        Assignment open = asset.currentAssignment();
        if (open == null) {
            EmployeeCard employee = people.card(request.employeeId())
                    .orElseThrow(AssetProblem.EMPLOYEE_NOT_FOUND::failure);
            Assignment issued = issue(tenantId, asset, request, employee, issuedBy);
            announce(tenantId, assetId);
            return issued;
        }
        if (!open.employeeId().equals(request.employeeId())) {
            throw AssetProblem.ASSET_ALREADY_ASSIGNED.failure();
        }
        Assignment moved = withReturnDate(open, request.returnDue());
        if (moved != open) {
            announce(tenantId, assetId);
        }
        return moved;
    }

    private Assignment issue(UUID tenantId, Asset asset, NewAssignment request, EmployeeCard employee,
            String issuedBy) {
        Assignment issued = new Assignment(UUID.randomUUID(), asset.id(), request.employeeId(), LocalDate.now(clock),
                request.returnDue(), null, null);
        assets.insertAssignment(tenantId, issued);
        assets.setStatus(asset.id(), AssetStatus.ASSIGNED);
        String number = acts.make(tenantId, asset, issued, employee, issuedBy);
        String system = issuedBy != null && issuedBy.startsWith(CONNECTOR) ? "connector" : null;
        events.publishEvent(new ActionTaken(tenantId, "asset.issued", "asset", asset.id(), ActionTaken.details(
                "employeeId", request.employeeId(), "assignmentId", issued.id(), "inventoryNumber",
                asset.inventoryNumber(), "act", number, "returnDue", request.returnDue() == null ? null
                        : request.returnDue().toString()), system));
        return new Assignment(issued.id(), issued.assetId(), issued.employeeId(), issued.issuedAt(),
                issued.returnDue(), null, number);
    }

    private Assignment withReturnDate(Assignment open, LocalDate returnDue) {
        if (Objects.equals(open.returnDue(), returnDue)) {
            return open;
        }
        assets.moveReturnDate(open.id(), returnDue);
        return new Assignment(open.id(), open.assetId(), open.employeeId(), open.issuedAt(), returnDue, null,
                open.actNumber());
    }

    /** Takes the asset back. Returning an asset nobody holds changes nothing. */
    @Transactional
    void takeBack(UUID tenantId, UUID assetId) {
        session.bind(tenantId);
        Asset asset = locked(assetId);
        Assignment open = asset.currentAssignment();
        if (open != null) {
            assets.closeAssignment(open.id(), LocalDate.now(clock));
            returned.returned(tenantId, asset, open);
            assets.setStatus(assetId, AssetStatus.IN_STOCK);
            events.publishEvent(ActionTaken.of(tenantId, "asset.returned", "asset", assetId,
                    ActionTaken.details("employeeId", open.employeeId(), "assignmentId", open.id())));
            announce(tenantId, assetId);
        }
    }

    @Transactional
    Asset retire(UUID tenantId, UUID assetId) {
        session.bind(tenantId);
        if (locked(assetId).status() == AssetStatus.ASSIGNED) {
            throw AssetProblem.ASSET_ALREADY_ASSIGNED.failure();
        }
        if (locked(assetId).status() != AssetStatus.RETIRED) {
            assets.setStatus(assetId, AssetStatus.RETIRED);
            events.publishEvent(ActionTaken.of(tenantId, "asset.retired", "asset", assetId, java.util.Map.of()));
            announce(tenantId, assetId);
        }
        return found(assetId);
    }

    @Transactional(readOnly = true)
    HandoverActs.StoredAct act(UUID tenantId, UUID assetId, UUID assignmentId) {
        session.bind(tenantId);
        found(assetId);
        assets.assignment(assetId, assignmentId).orElseThrow(AssetProblem.ACT_NOT_FOUND::failure);
        return acts.of(assignmentId).orElseThrow(AssetProblem.ACT_NOT_FOUND::failure);
    }

    /**
     * What an accounting or MDM system reports about a device. The source stays the master: its model, type and
     * cost win, a missing value keeps ours. A holder the source names is issued the device with an act, and the
     * previous holder, if any, returns it today. A device the source reports without a holder keeps its holder:
     * MDM systems often do not know who has a device.
     *
     * @return the asset, or null when the report names neither a serial nor an inventory number
     */
    @Transactional
    UUID reported(UUID tenantId, AssetLinks.Reported report) {
        session.bind(tenantId);
        String serial = text(report.serialNumber());
        String inventory = Optional.ofNullable(text(report.inventoryNumber())).orElse(serial);
        if (inventory == null) {
            return null;
        }
        String model = text(report.model());
        AssetType type = type(report.type());
        BigDecimal cost = money(report.cost());
        boolean changed = false;

        Optional<UUID> known = assets.findByNumbers(serial, inventory);
        UUID id;
        if (known.isEmpty()) {
            id = UUID.randomUUID();
            try {
                assets.insert(tenantId, id, new NewAsset(inventory, model == null ? "Не указано" : model,
                        type == null ? AssetType.OTHER : type, cost == null ? BigDecimal.ZERO : cost, serial));
            } catch (DuplicateKeyException e) {
                // Another report registered the device meanwhile. This one is skipped; the next pull repeats it.
                throw AssetProblem.SERIAL_NUMBER_TAKEN.failure();
            }
            changed = true;
        } else {
            id = known.get();
            Asset a = locked(id);
            String newModel = model == null ? a.model() : model;
            AssetType newType = type == null ? a.type() : type;
            BigDecimal newCost = cost == null ? a.cost() : cost;
            if (!newModel.equals(a.model()) || newType != a.type() || newCost.compareTo(a.cost()) != 0
                    || (serial != null && a.serialNumber() == null)) {
                assets.updateReported(id, newModel, newType, newCost, serial);
                changed = true;
            }
        }

        Asset asset = locked(id);
        Assignment open = asset.currentAssignment();
        UUID holder = report.holderId();
        if (holder != null && asset.status() != AssetStatus.RETIRED
                && (open == null || !open.employeeId().equals(holder))) {
            Optional<EmployeeCard> employee = people.card(holder);
            if (employee.isPresent()) {
                if (open != null) {
                    assets.closeAssignment(open.id(), LocalDate.now(clock));
                    returned.returned(tenantId, asset, open);
                    events.publishEvent(ActionTaken.bySystem("connector", tenantId, "asset.returned", "asset", id,
                            ActionTaken.details("employeeId", open.employeeId(), "assignmentId", open.id())));
                }
                issue(tenantId, asset, new NewAssignment(holder, null), employee.get(), CONNECTOR + report.source());
                changed = true;
            }
        }
        if (changed) {
            announce(tenantId, id);
        }
        return id;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static AssetType type(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AssetType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AssetType.OTHER;
        }
    }

    /** A cost the table can hold, or null: a negative or absurd cost from a source is not a cost. */
    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return null;
        }
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        return rounded.precision() - rounded.scale() > 12 ? null : rounded;
    }

    private Asset locked(UUID assetId) {
        return assets.findForUpdate(assetId).orElseThrow(AssetProblem.ASSET_NOT_FOUND::failure);
    }
}
