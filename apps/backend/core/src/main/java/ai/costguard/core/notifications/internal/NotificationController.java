package ai.costguard.core.notifications.internal;

import ai.costguard.core.shared.security.TenantClaim;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "The company feed, its live stream, e-mail and Telegram")
class NotificationController {

    private final NotificationService service;
    private final NotificationStream stream;
    private final ChannelSettings settings;

    NotificationController(NotificationService service, NotificationStream stream, ChannelSettings settings) {
        this.service = service;
        this.stream = stream;
        this.settings = settings;
    }

    @GetMapping("/settings")
    @Operation(summary = "My e-mail and Telegram notifications",
            description = "Levels: off, info, warning, critical. Without saved settings: e-mail for critical ones.")
    SettingsView settings(@AuthenticationPrincipal Jwt jwt) {
        return settings.get(TenantClaim.of(jwt), jwt.getClaimAsString("email"));
    }

    @PutMapping("/settings")
    @Operation(summary = "Change my notification levels")
    SettingsView saveSettings(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SettingsChange change) {
        return settings.save(TenantClaim.of(jwt), jwt.getClaimAsString("email"), change);
    }

    @PostMapping("/telegram-link")
    @Operation(summary = "A one-time link that connects my Telegram",
            description = "Open it in Telegram and press Start. 409 telegram_unavailable when no bot is configured.")
    TelegramLink telegramLink(@AuthenticationPrincipal Jwt jwt) {
        return settings.link(TenantClaim.of(jwt), jwt.getClaimAsString("email"));
    }

    @DeleteMapping("/telegram")
    @Operation(summary = "Stop sending me notifications to Telegram")
    SettingsView unlinkTelegram(@AuthenticationPrincipal Jwt jwt) {
        return settings.unlink(TenantClaim.of(jwt), jwt.getClaimAsString("email"));
    }

    @GetMapping
    @Operation(summary = "Latest 200 notifications and the unread count")
    NotificationPage page(@AuthenticationPrincipal Jwt jwt) {
        return service.page(TenantClaim.of(jwt));
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "New notifications as Server-Sent Events")
    SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        return stream.open(TenantClaim.of(jwt));
    }

    @PutMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark one as read")
    void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.markRead(TenantClaim.of(jwt), id);
    }

    @PutMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark all as read")
    void readAll(@AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(TenantClaim.of(jwt));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Clear the feed")
    void clear(@AuthenticationPrincipal Jwt jwt) {
        service.clear(TenantClaim.of(jwt));
    }
}
