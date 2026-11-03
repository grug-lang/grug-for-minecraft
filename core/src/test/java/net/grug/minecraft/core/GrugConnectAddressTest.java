package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrugConnectAddressTest {
    @Test
    void readsTheHostAndThePort() {
        GrugConnectAddress address = GrugConnectAddress.parse("127.0.0.1:25565");

        assertEquals("127.0.0.1", address.host());
        assertEquals(25565, address.port());
    }

    @Test
    void refusesAValueWithoutAHostAndAPort() {
        for (String value : new String[] {"localhost", ":25565", "localhost:", "host:port"}) {
            IllegalStateException e =
                    assertThrows(
                            IllegalStateException.class, () -> GrugConnectAddress.parse(value));
            assertTrue(e.getMessage().contains("GRUG_CI_CONNECT must be host:port"), value);
            assertTrue(e.getMessage().contains("'" + value + "'"), e.getMessage());
        }
    }
}
