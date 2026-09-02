package net.grug.minecraft.forge.network;

import net.grug.minecraft.forge.GrugModLoader;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GrugGuiPayload(BlockPos pos, GrugGuiBuilder builder) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<GrugGuiPayload> TYPE = new CustomPacketPayload.Type<>(
            new ResourceLocation(GrugModLoader.MODID, "open_gui"));

    public static final StreamCodec<FriendlyByteBuf, GrugGuiPayload> CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            GrugGuiPayload::new);

    public GrugGuiPayload(FriendlyByteBuf buf) {
        this(buf.readBlockPos(), readBuilder(buf));
    }

    private static GrugGuiBuilder readBuilder(FriendlyByteBuf buf) {
        GrugGuiBuilder b = new GrugGuiBuilder(buf.readUtf());
        b.hasPlayerInventory = buf.readBoolean();
        b.playerInvX = buf.readInt();
        b.playerInvY = buf.readInt();
        b.hotbarX = buf.readInt();
        b.hotbarY = buf.readInt();

        int slots = buf.readInt();
        for (int i = 0; i < slots; i++) {
            b.blockSlots
                    .add(new GrugGuiBuilder.SlotDef(buf.readInt(), buf.readInt(), buf.readInt(), buf.readBoolean()));
        }

        int grids = buf.readInt();
        for (int i = 0; i < grids; i++) {
            b.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(buf.readInt(), buf.readInt(), buf.readInt()));
        }

        int results = buf.readInt();
        for (int i = 0; i < results; i++) {
            b.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(buf.readInt(), buf.readInt(), buf.readInt()));
        }

        int texts = buf.readInt();
        for (int i = 0; i < texts; i++) {
            b.texts.add(new GrugGuiBuilder.TextDef(buf.readUtf(), buf.readInt(), buf.readInt(), buf.readInt()));
        }
        return b;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(builder.texturePath);
        buf.writeBoolean(builder.hasPlayerInventory);
        buf.writeInt(builder.playerInvX);
        buf.writeInt(builder.playerInvY);
        buf.writeInt(builder.hotbarX);
        buf.writeInt(builder.hotbarY);

        buf.writeInt(builder.blockSlots.size());
        for (GrugGuiBuilder.SlotDef def : builder.blockSlots) {
            buf.writeInt(def.index());
            buf.writeInt(def.x());
            buf.writeInt(def.y());
            buf.writeBoolean(def.isOutput());
        }

        buf.writeInt(builder.craftingGrids.size());
        for (GrugGuiBuilder.CraftingGridDef def : builder.craftingGrids) {
            buf.writeInt(def.startSlot());
            buf.writeInt(def.x());
            buf.writeInt(def.y());
        }

        buf.writeInt(builder.craftingResults.size());
        for (GrugGuiBuilder.CraftingResultDef def : builder.craftingResults) {
            buf.writeInt(def.slot());
            buf.writeInt(def.x());
            buf.writeInt(def.y());
        }

        buf.writeInt(builder.texts.size());
        for (GrugGuiBuilder.TextDef def : builder.texts) {
            buf.writeUtf(def.text());
            buf.writeInt(def.x());
            buf.writeInt(def.y());
            buf.writeInt(def.color());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
