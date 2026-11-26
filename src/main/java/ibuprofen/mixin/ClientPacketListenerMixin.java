package ibuprofen.mixin;

import ibuprofen.IbuprofenMod;
import ibuprofen.world.WorldState;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures client chat commands (#ibu ...), BedWars server messages/titles,
 * and chunk/block updates for WorldState without touching Netty internals.
 */
@Mixin(ClientPacketListener.class)
public final class ClientPacketListenerMixin {

    @Inject(method = "sendChat(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void ibuprofen$onSendChat(String message, CallbackInfo ci) {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && mod.handleLocalChatCommand(message)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void ibuprofen$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod == null || packet == null || packet.content() == null) {
            return;
        }
        String clean = WorldState.stripColors(packet.content().getString());
        if (packet.overlay()) {
            mod.worldState().enqueueActionBar(clean);
        } else {
            mod.worldState().enqueueChat(clean);
        }
    }

    @Inject(method = "handleSetTitleText", at = @At("HEAD"))
    private void ibuprofen$onTitle(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null && packet.text() != null) {
            mod.worldState().enqueueTitle(WorldState.stripColors(packet.text().getString()));
        }
    }

    @Inject(method = "handleSetActionBarText", at = @At("HEAD"))
    private void ibuprofen$onActionBar(ClientboundSetActionBarTextPacket packet, CallbackInfo ci) {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null && packet.text() != null) {
            mod.worldState().enqueueActionBar(WorldState.stripColors(packet.text().getString()));
        }
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void ibuprofen$onChunkLoaded(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null) {
            mod.worldState().onChunkLoaded(packet.x(), packet.z());
        }
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("RETURN"))
    private void ibuprofen$onChunkUnloaded(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null) {
            mod.worldState().onChunkUnloaded(packet.pos().x(), packet.pos().z());
        }
    }

    @Inject(method = "handleBlockUpdate", at = @At("RETURN"))
    private void ibuprofen$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null) {
            mod.worldState().enqueueBlockChange(packet.getPos(), packet.getBlockState());
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"))
    private void ibuprofen$onMultiBlockUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod == null || packet == null) {
            return;
        }
        packet.runUpdates((pos, state) -> mod.worldState().enqueueBlockChange(pos, state));
    }
}
