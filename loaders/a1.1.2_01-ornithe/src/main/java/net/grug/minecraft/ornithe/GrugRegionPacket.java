package net.grug.minecraft.ornithe;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.network.packet.WorldChunkPacket;

import java.lang.reflect.Field;

/**
 * The Alpha region packet, assembled from a region's cells.
 *
 * <p>The packet has no constructor that takes a world, so its payload is packed by {@link
 * GrugChunkData} from the cells, deflated by {@link GrugDeflate}, and its private compressed size
 * is set by reflection. Keeping the assembly in its own class lets a unit test build a packet
 * without a game, which is where a payload that compresses larger than it holds is seen (#1522),
 * and keeps the server adapter's class initializer out of a client unit-test JVM, where the
 * coverage combiner would otherwise count the adapter's dedicated-server body as uncovered.
 */
final class GrugRegionPacket {

    private GrugRegionPacket() {}

    /**
     * Builds the packet for the given region, whose height is rounded out to whole nibble bytes so
     * the client unpacks the values the server sends.
     */
    static WorldChunkPacket build(int[] region, GrugChunkData.Source cells) {
        int x = region[0];
        int y = region[1];
        int z = region[2];
        int sizeX = region[3];
        int sizeY = region[4];
        int sizeZ = region[5];

        // The nibble sections are packed two y layers per byte, aligned to even y.
        int adjustedY = y & ~1;
        int adjustedSizeY = ((y + sizeY + 1) & ~1) - adjustedY;

        WorldChunkPacket packet = new WorldChunkPacket();
        packet.x = x;
        packet.y = adjustedY;
        packet.z = z;
        packet.sizeX = sizeX;
        packet.sizeY = adjustedSizeY;
        packet.sizeZ = sizeZ;
        packet.shouldDelay = true;

        byte[] raw = GrugChunkData.pack(x, adjustedY, z, sizeX, adjustedSizeY, sizeZ, cells);
        packet.data = GrugDeflate.deflate(raw);
        setCompressedSize(packet, packet.data.length);
        return packet;
    }

    /**
     * The Alpha packet keeps its compressed size private, so it has to be set by reflection. The
     * field is {@code compressedSize} in the a1.1.2_01 development client this layer is compiled
     * against, and {@code f_62093482} in the Ornithe intermediary the a0.2.1 dedicated server runs
     * (its obfuscated class is {@code cz}, whose {@code h} is that intermediary), which both the
     * server jar and a player's client jar carry, so both names are tried (#1486).
     */
    private static final Field COMPRESSED_SIZE_FIELD = compressedSizeField();

    @GrugGenerated(
            "the failure arm needs a field name the pinned game does not have, which no run can"
                    + " make")
    private static Field compressedSizeField() {
        try {
            return GrugFieldAccess.field(WorldChunkPacket.class, "compressedSize", "f_62093482");
        } catch (NoSuchFieldException e) {
            throw Grug.fatal(
                    "neither compressedSize, the a1.1.2_01 development name, nor f_62093482, the"
                            + " Ornithe intermediary name of the a0.2.1 dedicated server's"
                            + " ornithe-server/server.jar, is a field of WorldChunkPacket, so the"
                            + " region packet has no compressed size to send",
                    e);
        }
    }

    @GrugGenerated(
            "Field.setInt cannot fail with IllegalAccessException on a field made accessible when"
                    + " it was found")
    private static void setCompressedSize(WorldChunkPacket packet, int size) {
        try {
            COMPRESSED_SIZE_FIELD.setInt(packet, size);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not set WorldChunkPacket.compressedSize", e);
        }
    }
}
