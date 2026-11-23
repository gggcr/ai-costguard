package ai.costguard.core.plans.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stripe subscriptions (ADR 0077) through Stripe's REST API, without its SDK: Checkout in subscription mode, the
 * customer portal for changes, cancellation, cards and invoices, and webhooks verified with the endpoint's signing
 * secret. Neither the secret key nor anything about the card leaves the core or reaches the log: Stripe's pages
 * take the card, the core sees only ids, statuses, periods and amounts.
 *
 * <p>Both shapes of Stripe's API are read where they differ (before and after the 2025 versions, which moved the
 * period to subscription items and the invoice's subscription under {@code parent}), so the webhook endpoint's
 * API version does not matter.
 */
@Component
class StripePaymentProvider implements PaymentProvider {

    static final String NAME = "stripe";

    private static final Logger log = LoggerFactory.getLogger(StripePaymentProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlansProperties.Stripe config;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    StripePaymentProvider(PlansProperties properties, Clock clock) {
        this.config = properties.payments().stripe();
        this.clock = clock;
        if (NAME.equals(properties.payments().provider().strip())) {
            List<String> missing = new ArrayList<>();
            if (config.secretKey().isBlank()) {
                missing.add("STRIPE_SECRET_KEY");
            }
            if (config.webhookSecret().isBlank()) {
                missing.add("STRIPE_WEBHOOK_SECRET");
            }
            if (config.prices().isEmpty()) {
                missing.add("a price id (STRIPE_PRICE_START_USD and the others)");
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("PAYMENT_PROVIDER=stripe needs " + String.join(", ", missing));
            }
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Checkout start(PaymentRequest request) {
        String price = config.price(request.plan(), request.currency()).orElseThrow(() -> new IllegalStateException(
                "no Stripe price for " + request.plan() + " in " + request.currency()));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", "subscription");
        form.put("line_items[0][price]", price);
        form.put("line_items[0][quantity]", "1");
        form.put("client_reference_id", request.tenantId().toString());
        form.put("metadata[payment_id]", request.paymentId().toString());
        form.put("metadata[tenant_id]", request.tenantId().toString());
        form.put("subscription_data[metadata][tenant_id]", request.tenantId().toString());
        form.put("subscription_data[metadata][plan]", request.plan());
        form.put("success_url", request.returnUrl() + "?checkout=success");
        form.put("cancel_url", request.returnUrl() + "?checkout=canceled");
        if (request.customerId() != null) {
            form.put("customer", request.customerId());
        }
        if (config.automaticTax()) {
            form.put("automatic_tax[enabled]", "true");
            form.put("billing_address_collection", "required");
            if (request.customerId() != null) {
                form.put("customer_update[address]", "auto");
            }
        }
        // the core's payment id: a retried call returns the same session instead of a second one
        JsonNode session = call("/v1/checkout/sessions", form, request.paymentId().toString());
        return new Checkout(text(session, "id"), Status.PENDING, text(session, "url"));
    }

    @Override
    public Optional<String> portal(String customerId, String returnUrl) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("customer", customerId);
        form.put("return_url", returnUrl);
        return Optional.ofNullable(text(call("/v1/billing_portal/sessions", form, null), "url"));
    }

    private JsonNode call(String path, Map<String, String> form, String idempotencyKey) {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.apiUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + config.secretKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // the status and Stripe's error code only: the body may echo what was sent
                String code = Optional.ofNullable(JSON.readTree(response.body()).path("error").path("code").asText(null))
                        .orElse("-");
                log.warn("Stripe {} answered {} ({})", path, response.statusCode(), code);
                throw new IllegalStateException("Stripe answered " + response.statusCode());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Stripe is unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    // --- Webhooks

    @Override
    public Optional<Notice> notice(Map<String, String> headers, String body) {
        if (!verified(headers.get("stripe-signature"), body)) {
            return Optional.empty();
        }
        JsonNode event;
        try {
            event = JSON.readTree(body);
        } catch (IOException e) {
            return Optional.empty();
        }
        String id = text(event, "id");
        if (id == null) {
            return Optional.empty();
        }
        Instant at = Instant.ofEpochSecond(event.path("created").asLong(0));
        JsonNode o = event.path("data").path("object");
        String type = event.path("type").asText("");
        return Optional.of(switch (type) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> {
                boolean paid = switch (o.path("payment_status").asText("")) {
                    case "paid", "no_payment_required" -> true;
                    default -> false;
                };
                String subscription = text(o, "subscription");
                SubscriptionChange started = subscription == null ? null : new SubscriptionChange(
                        SubscriptionChange.Kind.STARTED, subscription, text(o, "customer"), null, "active", false,
                        null, null, null, null, true);
                yield new Notice(text(o, "id"), paid ? Status.SUCCEEDED : Status.PENDING, id, at,
                        paid ? started : null);
            }
            case "checkout.session.expired", "checkout.session.async_payment_failed" ->
                    new Notice(text(o, "id"), Status.CANCELED, id, at, null);
            case "invoice.paid", "invoice.payment_failed" -> invoice(id, at, o, "invoice.paid".equals(type));
            case "customer.subscription.created", "customer.subscription.updated",
                    "customer.subscription.deleted" -> subscription(id, at, o,
                    "customer.subscription.deleted".equals(type));
            default -> Notice.ignored(id);
        });
    }

    private Notice invoice(String eventId, Instant at, JsonNode o, boolean paid) {
        String subscription = Optional.ofNullable(text(o, "subscription"))
                .orElse(text(o.path("parent").path("subscription_details"), "subscription"));
        if (subscription == null) {
            return Notice.ignored(eventId);
        }
        Instant end = null;
        String price = null;
        for (JsonNode line : o.path("lines").path("data")) {
            long e = line.path("period").path("end").asLong(0);
            if (e > 0 && (end == null || Instant.ofEpochSecond(e).isAfter(end))) {
                end = Instant.ofEpochSecond(e);
            }
            if (price == null) {
                price = Optional.ofNullable(text(line.path("price"), "id"))
                        .orElse(text(line.path("pricing").path("price_details"), "price"));
            }
        }
        String currency = Optional.ofNullable(text(o, "currency")).map(c -> c.toUpperCase(Locale.ROOT)).orElse(null);
        BigDecimal amount = currency == null ? null : minor(o.path(paid ? "amount_paid" : "amount_due").asLong(0),
                currency);
        return new Notice(null, null, eventId, at, new SubscriptionChange(
                paid ? SubscriptionChange.Kind.PAID : SubscriptionChange.Kind.PAYMENT_FAILED, subscription,
                text(o, "customer"), price == null ? null : config.planOf(price).orElse(null),
                paid ? "active" : "past_due", false, end, amount, currency, text(o, "id"),
                "subscription_create".equals(o.path("billing_reason").asText(""))));
    }

    private Notice subscription(String eventId, Instant at, JsonNode o, boolean deleted) {
        JsonNode item = o.path("items").path("data").path(0);
        String price = text(item.path("price"), "id");
        long end = o.path("current_period_end").asLong(item.path("current_period_end").asLong(0));
        return new Notice(null, null, eventId, at, new SubscriptionChange(
                deleted ? SubscriptionChange.Kind.ENDED : SubscriptionChange.Kind.UPDATED, text(o, "id"),
                text(o, "customer"), price == null ? null : config.planOf(price).orElse(null),
                deleted ? "canceled" : o.path("status").asText("active"), o.path("cancel_at_period_end").asBoolean(),
                end > 0 ? Instant.ofEpochSecond(end) : null, null, null, null, false));
    }

    /**
     * Stripe's scheme: {@code Stripe-Signature: t=<seconds>,v1=<hex HMAC-SHA256 of "t.body">[,v1=...]}, with the
     * endpoint's signing secret. A signature older or newer than the tolerance is refused, so a captured delivery
     * cannot be replayed later.
     */
    boolean verified(String header, String body) {
        if (header == null || config.webhookSecret().isBlank()) {
            return false;
        }
        long timestamp = -1;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if ("v1".equals(kv[0])) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp < 0 || signatures.isEmpty()
                || Math.abs(clock.instant().getEpochSecond() - timestamp) > config.tolerance().toSeconds()) {
            return false;
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        for (String signature : signatures) {
            try {
                if (MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // not hex: not this one
            }
        }
        return false;
    }

    private static BigDecimal minor(long amount, String currency) {
        int digits;
        try {
            digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException e) {
            digits = 2;
        }
        return BigDecimal.valueOf(amount, digits);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
