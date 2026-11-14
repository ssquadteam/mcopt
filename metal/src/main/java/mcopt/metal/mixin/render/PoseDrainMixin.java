package mcopt.metal.mixin.render;

import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import mcopt.metal.render.PosePool;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SubmitNodeStorage.class)
abstract class PoseDrainMixin {
	@Shadow @Final private Int2ObjectAVLTreeMap<SubmitNodeCollection> submitsPerOrder;

	@Inject(method = "drainPhases", at = @At("HEAD"))
	private void mcopt$drained(CallbackInfo ci) {
		for (SubmitNodeCollection collection : this.submitsPerOrder.values()) ((PosePool.Owner) collection).mcopt$poses().drained();
	}
}
