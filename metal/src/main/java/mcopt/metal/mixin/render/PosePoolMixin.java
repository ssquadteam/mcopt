package mcopt.metal.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import mcopt.metal.render.PosePool;
import net.minecraft.client.renderer.SubmitNodeCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SubmitNodeCollection.class)
abstract class PosePoolMixin implements PosePool.Owner {
	@Unique private final PosePool mcopt$poses = new PosePool();

	@Override
	public PosePool mcopt$poses() {
		return this.mcopt$poses;
	}

	@WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;copy()Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"))
	private PoseStack.Pose mcopt$pooled(PoseStack.Pose pose, Operation<PoseStack.Pose> original) {
		return this.mcopt$poses.copy(pose);
	}
}
