package ai.costguard.core.assets.internal;

import ai.costguard.core.assets.AssetLinks;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ServiceAssetLinks implements AssetLinks {

    private final AssetService service;

    ServiceAssetLinks(AssetService service) {
        this.service = service;
    }

    @Override
    public UUID reported(UUID tenantId, Reported report) {
        return service.reported(tenantId, report);
    }
}
