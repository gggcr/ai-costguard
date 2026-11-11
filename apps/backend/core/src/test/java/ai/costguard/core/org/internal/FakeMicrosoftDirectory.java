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

    public final java.util.Set<String> disabled = ConcurrentHashMap.newKeySet();
    public final java.util.List<SignIn> log = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public java.util.List<SignIn> signIns(App app, java.time.Instant from) {
        if (down) {
            throw new Unavailable("down");
        }
        return log.stream().filter(s -> !s.at().isBefore(from)).toList();
    }

    @Override
    public void disable(App app, String address) {
        if (down) {
            throw new Unavailable("down");
        }
        disabled.add(address);
    }

    @Override
    public void delete(App app, String id) {
        String address = ids.remove(id);
        if (address != null) {
            users.remove(address);
        }
    }
}
