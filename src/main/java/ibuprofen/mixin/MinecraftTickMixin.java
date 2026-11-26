package ibuprofen.mixin;

import ibuprofen.IbuprofenMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the Minecraft client tick and world transitions for Ibuprofen Layer 1.
 */
@Mixin(Minecraft.class)
public final class MinecraftTickMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void ibuprofen$onTickHead(CallbackInfo ci) {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null) {
            mod.onClientTick((Minecraft) (Object) this);
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void ibuprofen$onSetLevel(ClientLevel newLevel, CallbackInfo ci) {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null) {
            mod.onWorldChange(newLevel == null ? "disconnect" : "level_change");
        }
    }
}
