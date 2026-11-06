package ai.costguard.core.assets.internal;

import org.springframework.http.HttpStatus;

/** A rule of the module was broken; {@code code} is the stable value clients branch on. */
enum AssetProblem {
    TENANT_MISSING(HttpStatus.FORBIDDEN, "Token has no valid tenant_id claim"),
    ASSET_NOT_FOUND(HttpStatus.NOT_FOUND, "Asset not found"),
    INVENTORY_NUMBER_TAKEN(HttpStatus.CONFLICT, "Inventory number is already registered"),
    ASSET_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "Asset is held by an employee"),
    ASSET_RETIRED(HttpStatus.CONFLICT, "Asset is retired");

    final HttpStatus status;
    final String detail;

    AssetProblem(HttpStatus status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    String code() {
        return name().toLowerCase();
    }

    Failure failure() {
        return new Failure(this);
    }

    static final class Failure extends RuntimeException {

        final AssetProblem problem;

        private Failure(AssetProblem problem) {
            super(problem.detail);
            this.problem = problem;
        }
    }
}
