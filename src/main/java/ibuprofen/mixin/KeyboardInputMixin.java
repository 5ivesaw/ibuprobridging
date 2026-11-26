package ibuprofen.mixin;

import ibuprofen.IbuprofenMod;
import ibuprofen.motor.BodyInput;
import ibuprofen.motor.InputArbiter;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets InputArbiter override individual body channels (MOVEMENT / LMB / RMB) cleanly
 * while leaving unowned channels 100% under human keyboard/mouse control.
 */
@Mixin(KeyMapping.class)
public final class KeyboardInputMixin {

    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void ibuprofen$overrideIsDown(CallbackInfoReturnable<Boolean> cir) {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod == null) {
            return;
        }
        InputArbiter arbiter = mod.inputArbiter();
        if (!arbiter.isAnyActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return;
        }
        BodyInput mapped = mapKey((KeyMapping) (Object) this, mc.options);
        if (mapped == null) {
            return;
        }
        Boolean override = arbiter.getKeyOverride(mapped);
        if (override != null) {
            cir.setReturnValue(override);
        }
    }

    private static BodyInput mapKey(KeyMapping self, Options opts) {
        if (self == opts.keyUp) return BodyInput.MOVE_FORWARD;
        if (self == opts.keyDown) return BodyInput.MOVE_BACK;
        if (self == opts.keyLeft) return BodyInput.MOVE_LEFT;
        if (self == opts.keyRight) return BodyInput.MOVE_RIGHT;
        if (self == opts.keyJump) return BodyInput.JUMP;
        if (self == opts.keyShift) return BodyInput.SNEAK;
        if (self == opts.keySprint) return BodyInput.SPRINT;
        if (self == opts.keyAttack) return BodyInput.CLICK_LEFT;
        if (self == opts.keyUse) return BodyInput.CLICK_RIGHT;
        return null;
    }
}
