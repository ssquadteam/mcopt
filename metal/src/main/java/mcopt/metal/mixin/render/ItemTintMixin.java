package mcopt.metal.mixin.render;

import java.util.List;
import mcopt.metal.render.GuiItems;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CuboidItemModelWrapper.class)
abstract class ItemTintMixin {
	@Shadow @Final private List<ItemTintSource> tints;

	@Inject(method = "update", at = @At("HEAD"))
	private void mcopt$observe(CallbackInfo ci) {
		GuiItems.observeTints(this.tints);
	}
}
