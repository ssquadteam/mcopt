package mcopt.metal.mixin;

import mcopt.metal.shaderpack.render.PackRuntime;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(CardinalLighting.class)
abstract class CardinalLightingPackMixin {
	@ModifyVariable(method = "byFace", at = @At("HEAD"), argsOnly = true)
	private Direction mcopt$flatShading(Direction face) {
		return PackRuntime.flatShading() ? Direction.UP : face;
	}
}
