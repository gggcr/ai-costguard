package ai.costguard.core.assets.internal;

import ai.costguard.platform.errors.ProblemCode;
import org.springframework.http.HttpStatus;

/** A rule of the module was broken. */
enum AssetProblem implements ProblemCode {
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Changing assets needs the owner, finance or it_admin role"),
    ASSET_NOT_FOUND(HttpStatus.NOT_FOUND, "Asset not found"),
    INVENTORY_NUMBER_TAKEN(HttpStatus.CONFLICT, "Inventory number is already registered"),
    ASSET_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "Asset is held by an employee"),
    ASSET_RETIRED(HttpStatus.CONFLICT, "Asset is retired");

    private final HttpStatus status;
    private final String detail;

    AssetProblem(HttpStatus status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String detail() {
        return detail;
    }
}
