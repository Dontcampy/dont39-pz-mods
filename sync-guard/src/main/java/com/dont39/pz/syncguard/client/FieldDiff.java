package com.dont39.pz.syncguard.client;

import com.dont39.pz.syncguard.game.ItemDigests;
import com.dont39.pz.syncguard.protocol.WireKey;
import zombie.debug.DebugType;
import zombie.inventory.InventoryItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.dont39.pz.syncguard.Mod.PREFIX;

/**
 * {@code -Dsyncguard.debugFieldDiff}: which digest fields differ per item, for the in-game run that settles
 * {@code ItemDigests.Checked}. The client records its fields at a mismatch; when the snapshot comes, its item list is
 * the server's copy (as the game saves and loads an item on this client, so fields the save leaves out or the load
 * resets show up on both lines), and the record and the state after applying are each compared with it. The
 * snapshot's own item digests tell which items really still differ after applying. Main thread only.
 */
final class FieldDiff {
    /** One item's per-field hashes and its whole digest. */
    record Fields(String type, Map<String, Long> hashes, long digest) {
        static Fields of(InventoryItem item) {
            return new Fields(item.getFullType(), ItemDigests.fields(item), ItemDigests.item(item));
        }
    }

    private final Map<WireKey, Map<Integer, Fields>> atMismatch = new HashMap<>();

    static Map<Integer, Fields> of(Collection<InventoryItem> items) {
        Map<Integer, Fields> out = new LinkedHashMap<>();
        for (InventoryItem item : items) {
            out.put(item.getID(), Fields.of(item));
        }
        return out;
    }

    void mismatch(WireKey key, String name, Collection<InventoryItem> items, long mine, long server) {
        atMismatch.put(key, of(items));
        DebugType.Mod.println(PREFIX + "field diff: %s differs (client %016x, server %016x); %d items recorded,"
                + " compared when the snapshot comes", name, mine, server, items.size());
    }

    /**
     * After a snapshot was applied: {@code before} is the client's state when it was applied, used when no mismatch
     * was recorded (an open check); {@code digests}: the server's item digests from the snapshot.
     */
    void applied(WireKey key, String name, Map<Integer, Fields> before, Map<Integer, Fields> after,
            Map<Integer, Fields> server, Map<Integer, Long> digests) {
        Map<Integer, Fields> recorded = atMismatch.remove(key);
        log(name, recorded != null ? "at the mismatch" : "before the snapshot", recorded != null ? recorded : before,
                server, Map.of());
        log(name, "after the snapshot", after, server, digests);
    }

    // with digests: an item marked "equal" matches the server's own digest, so its listed fields differ only from
    // the saved copy
    private static void log(String name, String when, Map<Integer, Fields> client, Map<Integer, Fields> server,
            Map<Integer, Long> digests) {
        List<String> lines = new ArrayList<>();
        server.forEach((id, s) -> {
            Fields c = client.get(id);
            if (c == null) {
                lines.add(id + " (" + s.type() + "): only on the server");
                return;
            }
            List<String> fields = new ArrayList<>();
            s.hashes().forEach((field, hash) -> {
                if (!Objects.equals(hash, c.hashes().get(field))) {
                    fields.add(field);
                }
            });
            c.hashes().keySet().stream().filter(f -> !s.hashes().containsKey(f)).forEach(fields::add);
            Long digest = digests.get(id);
            String verdict = digest == null ? "" : digest == c.digest() ? " [equal]" : " [differs]";
            if (!fields.isEmpty() || verdict.equals(" [differs]")) {
                lines.add(id + " (" + s.type() + "): " + String.join(", ", fields) + verdict);
            }
        });
        client.forEach((id, c) -> {
            if (!server.containsKey(id)) {
                lines.add(id + " (" + c.type() + "): only on the client");
            }
        });
        DebugType.Mod.println(PREFIX + "field diff: %s %s, against the server's copy: %s", name, when,
                lines.isEmpty() ? "no item differs" : String.join("; ", lines));
    }
}
