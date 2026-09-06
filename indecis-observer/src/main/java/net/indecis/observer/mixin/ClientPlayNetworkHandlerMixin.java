package net.indecis.observer.mixin;

import net.indecis.observer.IndecisObserverClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onExplosion", at = @At("TAIL"))
    private void indecisObserver$afterExplosion(ExplosionS2CPacket packet, CallbackInfo ci) {
        IndecisObserverClient.onExplosion(packet);
    }
}
