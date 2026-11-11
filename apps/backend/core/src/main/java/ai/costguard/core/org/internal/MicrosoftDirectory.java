package ai.costguard.core.org.internal;

/**
 * The company's Microsoft Entra ID through Microsoft Graph (ADR 0059): makes the account (and with a license the
 * Outlook mailbox) of a new employee. The app needs the application permission User.ReadWrite.All with admin consent.
 */
interface MicrosoftDirectory {

    class Unavailable extends RuntimeException {
        Unavailable(String what) {
            super(what);
        }
    }

    record App(String directoryId, String clientId, String clientSecret) {
    }

    record NewMailbox(String nickname, String domain, String firstName, String lastName, String password,
            String licenseSku, String usageLocation) {
    }

    record Mailbox(String id, String address) {
    }

    /** The first free address of nickname, nickname2, nickname3… in the domain. */
    Mailbox create(App app, NewMailbox mailbox);

    void delete(App app, String id);
}
