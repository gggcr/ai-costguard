package ai.costguard.core.notifications.internal;

/** A way out of the web client. Off while it is not configured; then no deliveries are planned for it. */
interface Channel {

    String name();

    boolean enabled();

    /** Throws {@link DeliveryFailure} when the message did not go out. */
    void send(Message message, String address);

    /** What goes out: the text of the notification and an absolute link into the web client. */
    /** {@code language}: the company's, for what the channel adds around the text (ADR 0074). */
    record Message(String text, String url, Level level, ai.costguard.core.shared.names.Language language) {
    }

    /** {@code permanent}: retrying will not help (bad address, the user blocked the bot). */
    class DeliveryFailure extends RuntimeException {

        private final boolean permanent;

        DeliveryFailure(String message, boolean permanent) {
            super(message);
            this.permanent = permanent;
        }

        boolean permanent() {
            return permanent;
        }
    }
}
