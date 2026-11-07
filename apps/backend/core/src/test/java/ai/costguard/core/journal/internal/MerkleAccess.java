package ai.costguard.core.journal.internal;

import java.util.List;

/** Lets tests outside the package build the same root the notary would. */
public final class MerkleAccess {

    private MerkleAccess() {
    }

    public static String root(List<String> hashes) {
        return Merkle.root(hashes);
    }
}
