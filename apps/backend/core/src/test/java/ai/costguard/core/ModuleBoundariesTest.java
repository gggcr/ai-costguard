package ai.costguard.core;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundariesTest {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(CoreApplication.class).verify();
    }
}
