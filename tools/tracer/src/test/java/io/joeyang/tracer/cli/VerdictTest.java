package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VerdictTest {
    static final Commit COMMIT = new Commit("0123456789abcdef0123456789abcdef01234567", "x");

    @Test void bazelDisagreesOnlyForTheTwoDisagreeingPairs() {
        for (Status status : Status.values()) {
            for (BazelStatus bazel : BazelStatus.values()) {
                Verdict v = new Verdict(COMMIT, status, Optional.empty(), bazel, List.of(), Optional.empty());
                boolean expected = (status == Status.CHANGED && bazel == BazelStatus.CLEAN)
                        || (status == Status.CLEAN && bazel == BazelStatus.IMPACTED);
                assertEquals(expected, v.bazelDisagrees(), status + " vs " + bazel);
            }
        }
    }

    @Test void culpritsListIsACopy() {
        List<String> source = new ArrayList<>(List.of("core.jar"));
        Verdict v = new Verdict(COMMIT, Status.CHANGED, Optional.empty(), BazelStatus.NONE, source, Optional.empty());

        source.add("lib.jar");

        assertEquals(List.of("core.jar"), v.culprits());
        assertThrows(UnsupportedOperationException.class, () -> v.culprits().add("x"));
    }
}
