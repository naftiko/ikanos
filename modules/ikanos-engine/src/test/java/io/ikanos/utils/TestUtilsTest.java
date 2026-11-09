package io.ikanos.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TestUtilsTest {

    @Test
    void freePortsShouldNeverRepeatAPort() throws Exception {
        // Separate "open port 0, read it, close it" calls returned the same port about once in
        // 10,000 pairs on Linux, enough to fail a CI run now and then.
        for (int round = 0; round < 2_000; round++) {
            int[] ports = TestUtils.freePorts(3);

            assertEquals(3, Arrays.stream(ports).distinct().count(), Arrays.toString(ports));
        }
    }
}
