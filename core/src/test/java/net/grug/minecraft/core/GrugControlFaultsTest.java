package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GrugControlFaultsTest {
    @Test
    void anUnsetOrBlankValueIsTheFallbackAndANumberIsParsed() {
        assertEquals(-1, GrugControlFaults.parseInt(null, -1));
        assertEquals(-1, GrugControlFaults.parseInt("  ", -1));
        assertEquals(7, GrugControlFaults.parseInt(" 7 ", -1));
        assertEquals(3, GrugControlFaults.intFromEnv("GRUG_NO_SUCH_VARIABLE_FOR_THE_TEST", 3));
    }

    @Test
    void aVariableIsHonouredOnlyInACiRun() {
        java.util.Map<String, String> plain = java.util.Map.of("GRUG_X", "7");
        assertEquals(-1, GrugControlFaults.intFrom(plain::get, "GRUG_X", -1));
        java.util.Map<String, String> server = java.util.Map.of("GRUG_X", "7", "GRUG_CI", "true");
        assertEquals(7, GrugControlFaults.intFrom(server::get, "GRUG_X", -1));
        java.util.Map<String, String> client =
                java.util.Map.of("GRUG_X", "8", "GRUG_CI_CONNECT", "127.0.0.1:1");
        assertEquals(8, GrugControlFaults.intFrom(client::get, "GRUG_X", -1));
        java.util.Map<String, String> notTrue = java.util.Map.of("GRUG_X", "7", "GRUG_CI", "false");
        assertEquals(-1, GrugControlFaults.intFrom(notTrue::get, "GRUG_X", -1));
    }
}
