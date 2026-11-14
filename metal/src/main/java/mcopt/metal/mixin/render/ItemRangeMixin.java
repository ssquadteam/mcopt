package mcopt.metal.mixin.render;

import mcopt.metal.render.GuiItems;
import net.minecraft.client.renderer.item.RangeSelectItemModel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RangeSelectItemModel.class)
abstract class ItemRangeMixin {
	@Shadow @Final private RangeSelectItemModelProperty property;

	@Inject(method = "update", at = @At("HEAD"))
	private void mcopt$observe(CallbackInfo ci) {
		GuiItems.observe(this.property);
	}
}
