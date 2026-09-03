package net.grug.minecraft.forge.gui;

import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class GrugMenu extends AbstractContainerMenu {
    private final Container blockContainer;
    public final GrugGuiBuilder layout;

    public GrugMenu(MenuType<?> menuType, int containerId, Inventory playerInventory, Container blockContainer,
            GrugGuiBuilder layout) {
        super(menuType, containerId);
        this.blockContainer = blockContainer;
        this.layout = layout;

        // Standard Slots
        for (GrugGuiBuilder.SlotDef def : layout.blockSlots) {
            this.addSlot(new Slot(blockContainer, def.index(), def.x(), def.y()));
        }

        // Crafting Grids
        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    this.addSlot(new Slot(blockContainer, grid.startSlot() + col + row * 3,
                            grid.x() + col * 18, grid.y() + row * 18));
                }
            }
        }

        // Crafting Results
        for (GrugGuiBuilder.CraftingResultDef res : layout.craftingResults) {
            this.addSlot(new Slot(blockContainer, res.slot(), res.x(), res.y()) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public void onTake(Player player, ItemStack stack) {
                    super.onTake(player, stack);
                    if (blockContainer instanceof GrugBlockEntity gbe) {
                        gbe.notifyOutputTaken(res.slot(), stack.getCount());
                    }
                }
            });
        }

        // Player Inventory
        if (layout.hasPlayerInventory) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                            layout.playerInvX + col * 18, layout.playerInvY + row * 18));
                }
            }
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col,
                        layout.hotbarX + col * 18, layout.hotbarY));
            }
        }
    }

    public static void writeMenuData(FriendlyByteBuf buf, BlockPos pos, GrugGuiBuilder builder) {
        buf.writeBlockPos(pos);
        buf.writeUtf(builder.texturePath != null ? builder.texturePath : "");
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

    public static GrugGuiBuilder readBuilder(FriendlyByteBuf buf) {
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

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack originalStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (slot != null && slot.hasItem()) {
            ItemStack stackInSlot = slot.getItem();
            originalStack = stackInSlot.copy();

            int customSlotCount = this.slots.size() - 36;

            if (index >= customSlotCount) {
                // The shift-click originated from the player's own inventory
                if (index >= customSlotCount && index < customSlotCount + 27) {
                    // Main Inventory -> Hotbar
                    if (!this.moveItemStackTo(stackInSlot, customSlotCount + 27, customSlotCount + 36, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (index >= customSlotCount + 27 && index < customSlotCount + 36) {
                    // Hotbar -> Main Inventory
                    if (!this.moveItemStackTo(stackInSlot, customSlotCount, customSlotCount + 27, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            } else {
                // The shift-click originated from the custom GUI
                if (!this.moveItemStackTo(stackInSlot, customSlotCount, customSlotCount + 36, true)) {
                    return ItemStack.EMPTY;
                }
            }

            if (stackInSlot.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (stackInSlot.getCount() == originalStack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, stackInSlot);
        }

        return originalStack;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.blockContainer.stillValid(player);
    }
}
