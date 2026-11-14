package mcopt.metal.mixin.render;

import mcopt.metal.render.GuiItems;
import net.minecraft.client.renderer.item.SelectItemModel;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperty;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectItemModel.class)
abstract class ItemSelectMixin {
	@Shadow @Final private SelectItemModelProperty<?> property;

	@Inject(method = "update", at = @At("HEAD"))
	private void mcopt$observe(CallbackInfo ci) {
		GuiItems.observe(this.property);
	}
}
