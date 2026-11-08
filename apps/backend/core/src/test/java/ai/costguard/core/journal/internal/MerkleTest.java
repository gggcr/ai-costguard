package ai.costguard.core.journal.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MerkleTest {

    private static List<String> leaves(int n) {
        return IntStream.range(0, n).mapToObj(i -> Hashes.link(Hashes.GENESIS, "leaf " + i)).toList();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 8, 13})
    void everyLeafProvesItsPlace(int count) {
        List<String> leaves = leaves(count);
        String root = Merkle.root(leaves);

        for (int i = 0; i < count; i++) {
            assertThat(Merkle.verify(leaves.get(i), Merkle.path(leaves, i), root)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 5})
    void aForeignLeafDoesNotProve(int count) {
        List<String> leaves = leaves(count);
        String outsider = Hashes.link(Hashes.GENESIS, "outsider");

        assertThat(Merkle.verify(outsider, Merkle.path(leaves, 0), Merkle.root(leaves))).isFalse();
    }

    /** The same value is pinned in apps/python/notary/tests: the notary must build this tree. */
    @Test
    void rootMatchesTheNotary() {
        assertThat(Merkle.root(leaves(5))).isEqualTo("fda95846c507b71d6a40de553d7855aec27b3ada0afa61c177732c7f90afa3e9");
    }
}
