package ibuprofen.mixin;

import ibuprofen.IbuprofenMod;
import ibuprofen.world.WorldState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures client chat commands (#ibu ...), BedWars server messages/titles/subtitles/tablist,
 * and chunk/block updates for WorldState using verified MC 26.3 ClientPacketListener targets.
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
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
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

    @Inject(method = "handlePlayerChat", at = @At("HEAD"))
    private void ibuprofen$onPlayerChat(ClientboundPlayerChatPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod == null || packet == null) {
            return;
        }
        Component unsigned = packet.unsignedContent().orElse(null);
        String raw = unsigned != null ? unsigned.getString() : packet.body().content();
        mod.worldState().enqueueChat(WorldState.stripColors(raw));
    }

    @Inject(method = "handleDisguisedChat", at = @At("HEAD"))
    private void ibuprofen$onDisguisedChat(ClientboundDisguisedChatPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null && packet.message() != null) {
            mod.worldState().enqueueChat(WorldState.stripColors(packet.message().getString()));
        }
    }

    @Inject(method = "setTitleText", at = @At("HEAD"))
    private void ibuprofen$onTitle(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null && packet.text() != null) {
            mod.worldState().enqueueTitle(WorldState.stripColors(packet.text().getString()));
        }
    }

    @Inject(method = "setSubtitleText", at = @At("HEAD"))
    private void ibuprofen$onSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null && packet.text() != null) {
            mod.worldState().enqueueSubtitle(WorldState.stripColors(packet.text().getString()));
        }
    }

    @Inject(method = "setActionBarText", at = @At("HEAD"))
    private void ibuprofen$onActionBar(ClientboundSetActionBarTextPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null && packet.text() != null) {
            mod.worldState().enqueueActionBar(WorldState.stripColors(packet.text().getString()));
        }
    }

    @Inject(method = "handleTabListCustomisation", at = @At("HEAD"))
    private void ibuprofen$onTabList(ClientboundTabListPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null) {
            String h = packet.header() != null ? WorldState.stripColors(packet.header().getString()) : "";
            String f = packet.footer() != null ? WorldState.stripColors(packet.footer().getString()) : "";
            mod.worldState().enqueueTabList(h, f);
        }
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void ibuprofen$onChunkLoaded(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null) {
            mod.worldState().onChunkLoaded(packet.x(), packet.z());
        }
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("RETURN"))
    private void ibuprofen$onChunkUnloaded(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null) {
            mod.worldState().onChunkUnloaded(packet.pos().x(), packet.pos().z());
        }
    }

    @Inject(method = "handleBlockUpdate", at = @At("RETURN"))
    private void ibuprofen$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null && packet != null) {
            mod.worldState().enqueueBlockChange(packet.getPos(), packet.getBlockState());
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"))
    private void ibuprofen$onMultiBlockUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod == null || packet == null) {
            return;
        }
        packet.runUpdates((pos, state) -> mod.worldState().enqueueBlockChange(pos, state));
    }
}
