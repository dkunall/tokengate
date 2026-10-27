package com.tokengate.metering;

import com.tokengate.arch.HexagonalRules;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    @Test
    void followsHexagonalRules() {
        HexagonalRules.check("com.tokengate.metering");
    }
}
