package net.grug.minecraft.forge125.control;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.Packet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Registers a packet of grug's own in the game's packet table, which every loader of this version
 * needs on both ends: a packet id the receiver does not know closes the connection.
 */
public final class GrugPacketRegister {

    @GrugGenerated("utility class: never instantiated")
    private GrugPacketRegister() {}

    /**
     * Registers {@code type} under {@code id}, found by its signature (a static method of {@code
     * Packet} taking an int, two booleans and a class) because the game's names are obfuscated at
     * run time and the method is not public.
     */
    public static void register(int id, Class<? extends Packet> type) {
        for (Method method : Packet.class.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers())
                    && types.length == 4
                    && types[0] == int.class
                    && types[1] == boolean.class
                    && types[2] == boolean.class
                    && types[3] == Class.class) {
                try {
                    method.setAccessible(true);
                    method.invoke(null, id, true, true, type);
                    return;
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("cannot register a grug packet", e);
                }
            }
        }
        throw new IllegalStateException("Packet has no method to register a packet with");
    }
}
