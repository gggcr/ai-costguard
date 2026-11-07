package ai.costguard.core.assets.internal;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Error responses of the API contract, in one place. */
final class AssetErrorDocs {

    private AssetErrorDocs() {
    }

    /** The error body as clients see it: {@code code} is at the top level. */
    record ProblemBody(String type, String title, int status, String detail, String instance, String code) {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(responseCode = "404", description = "code: asset_not_found",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemBody.class)))
    @interface NotFound {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(responseCode = "409", description = "code: asset_already_assigned or asset_retired",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemBody.class)))
    @interface AssetStateConflict {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(responseCode = "409", description = "code: inventory_number_taken or serial_number_taken",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemBody.class)))
    @interface NumberConflict {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(responseCode = "404", description = "code: asset_not_found or employee_not_found",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemBody.class)))
    @interface AssetOrEmployeeNotFound {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(responseCode = "404", description = "code: asset_not_found or act_not_found",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemBody.class)))
    @interface ActNotFound {
    }
}
