package mcopt.metal.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import mcopt.metal.shaderpack.render.PackRuntime;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererPackMixin {
	@Shadow @Final private RenderTarget mainRenderTarget;

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V", ordinal = 0))
	private void mcopt$packBegin(CallbackInfo ci, @Local Matrix4f projectionMatrix, @Local CameraRenderState cameraState) {
		PackRuntime.beginLevel(projectionMatrix, cameraState.viewRotationMatrix, cameraState.pos, this.mainRenderTarget);
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void mcopt$packEnd(CallbackInfo ci) {
		PackRuntime.endLevel();
	}

	@Inject(method = "renderItemInHand", at = @At("HEAD"))
	private void mcopt$handBegin(CallbackInfo ci) {
		PackRuntime.hand(true);
	}

	@Inject(method = "renderItemInHand", at = @At("RETURN"))
	private void mcopt$handEnd(CallbackInfo ci) {
		PackRuntime.hand(false);
	}
}
