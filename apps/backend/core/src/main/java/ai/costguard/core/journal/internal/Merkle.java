package ai.costguard.core.journal.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * SHA-256 Merkle tree over receipt hashes in batch order. A node is sha256(left || right) over the raw 32-byte
 * values; a level with an odd count carries its last node up unchanged. The NEAR notary must build the same tree.
 */
final class Merkle {

    private Merkle() {
    }

    static String root(List<String> leaves) {
        List<String> level = leaves;
        while (level.size() > 1) {
            level = up(level);
        }
        return level.getFirst();
    }

    /** Siblings from the leaf up, each prefixed with L or R for the side it sits on. */
    static List<String> path(List<String> leaves, int index) {
        List<String> path = new ArrayList<>();
        List<String> level = leaves;
        int i = index;
        while (level.size() > 1) {
            int sibling = i % 2 == 0 ? i + 1 : i - 1;
            if (sibling < level.size()) {
                path.add((i % 2 == 0 ? "R:" : "L:") + level.get(sibling));
            }
            level = up(level);
            i /= 2;
        }
        return path;
    }

    static boolean verify(String leaf, List<String> path, String root) {
        String node = leaf;
        for (String step : path) {
            String sibling = step.substring(2);
            node = step.startsWith("R:") ? Hashes.pair(node, sibling) : Hashes.pair(sibling, node);
        }
        return node.equals(root);
    }

    private static List<String> up(List<String> level) {
        List<String> next = new ArrayList<>();
        for (int i = 0; i < level.size(); i += 2) {
            next.add(i + 1 < level.size() ? Hashes.pair(level.get(i), level.get(i + 1)) : level.get(i));
        }
        return next;
    }
}
