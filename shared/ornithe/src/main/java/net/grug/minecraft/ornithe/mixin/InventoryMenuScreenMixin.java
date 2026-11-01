package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.GrugOverlay;
import net.grug.minecraft.grug.GrugScreenBox;
import net.grug.minecraft.ornithe.client.GrugOverlayHost;
import net.grug.minecraft.ornithe.client.GrugScreenGeometry;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;

import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Draws the overlay on the container screens and lets it take a press before the vanilla slot sees
 * it.
 *
 * <p>Alpha and Beta have no screen event bus, so this is the shared hook both loaders use: {@code
 * render}'s return is after the screen has drawn itself, and {@code mouseClicked}'s head is before
 * the slot under the mouse is clicked. The overlay's own logic lives in each loader's {@link
 * GrugOverlayHost}; this mixin only forwards the screen's numbers, because a mixin class is not
 * measured by the coverage gate.
 */
@Mixin(InventoryMenuScreen.class)
public abstract class InventoryMenuScreenMixin implements GrugScreenGeometry {

    @Shadow protected int backgroundWidth;

    @Shadow protected int backgroundHeight;

    @Shadow
    protected abstract void mouseClicked(int mouseX, int mouseY, int button);

    @Inject(method = "render", at = @At("TAIL"))
    private void grug$drawOverlay(int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        List<GrugScreenBox> boxes = GrugOverlayHost.render(screen, mouseX, mouseY);
        for (GrugScreenBox box : boxes) {
            ((GrugFillInvoker) (Object) this)
                    .grug$fill(
                            (int) box.x1(),
                            (int) box.y1(),
                            (int) box.x2(),
                            (int) box.y2(),
                            0x80FFFFFF);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void grug$takePress(int mouseX, int mouseY, int button, CallbackInfo ci) {
        boolean shift =
                Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        if (GrugOverlayHost.click((Screen) (Object) this, mouseX, mouseY, button, shift)) {
            ci.cancel();
        }
    }

    @Override
    public boolean grug$click(int mouseX, int mouseY, int button) {
        GrugOverlay.beginTestPress();
        this.mouseClicked(mouseX, mouseY, button);
        return GrugOverlay.testPressTaken();
    }

    @Shadow
    protected abstract void keyPressed(char character, int key);

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void grug$takeKey(char character, int key, CallbackInfo ci) {
        if (GrugOverlayHost.key((Screen) (Object) this, key)) {
            ci.cancel();
        }
    }

    @Override
    public boolean grug$pressKey(int key) {
        GrugOverlay.beginTestPress();
        this.keyPressed((char) 0, key);
        return GrugOverlay.testPressTaken();
    }

    @Override
    public int grug$width() {
        return ((Screen) (Object) this).width;
    }

    @Override
    public int grug$height() {
        return ((Screen) (Object) this).height;
    }

    @Override
    public int grug$guiLeft() {
        return (((Screen) (Object) this).width - this.backgroundWidth) / 2;
    }

    @Override
    public int grug$guiTop() {
        return (((Screen) (Object) this).height - this.backgroundHeight) / 2;
    }

    @Override
    public int grug$guiWidth() {
        return this.backgroundWidth;
    }

    @Override
    public int grug$guiHeight() {
        return this.backgroundHeight;
    }
}
