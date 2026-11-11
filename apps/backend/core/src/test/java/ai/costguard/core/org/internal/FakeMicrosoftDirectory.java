package ai.costguard.core.org.internal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Microsoft Graph in memory. */
public class FakeMicrosoftDirectory implements MicrosoftDirectory {

    public final Map<String, NewMailbox> users = new ConcurrentHashMap<>();
    public final Map<String, String> ids = new ConcurrentHashMap<>();
    public volatile App lastApp;
    public volatile boolean down;

    @Override
    public Mailbox create(App app, NewMailbox m) {
        if (down) {
            throw new Unavailable("down");
        }
        lastApp = app;
        for (int i = 1; ; i++) {
            String address = m.nickname() + (i == 1 ? "" : String.valueOf(i)) + "@" + m.domain();
            if (!users.containsKey(address)) {
                String id = UUID.randomUUID().toString();
                users.put(address, m);
                ids.put(id, address);
                return new Mailbox(id, address);
            }
        }
    }

    @Override
    public void delete(App app, String id) {
        String address = ids.remove(id);
        if (address != null) {
            users.remove(address);
        }
    }
}
