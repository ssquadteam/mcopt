package mcopt.metal.iris.mixin;

import mcopt.metal.iris.IrisMetal;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.samplers.ProgramSamplers;
import net.irisshaders.iris.targets.ColorAttachmentPlan;
import net.irisshaders.iris.uniforms.buffer.DrawUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(value = TransformPatcher.class, remap = false)
public abstract class TransformPatcherMixin {
	@Inject(method = "patchProgram", at = @At("HEAD"), cancellable = true)
	private static void mcopt$metal(String name, Map<PatchShaderType, String> sources, ProgramSamplers resources, DrawUniforms uniforms,
		ColorAttachmentPlan.Program outputs, CallbackInfoReturnable<PatchedShader> cir) {
		if (IrisMetal.active) cir.setReturnValue(IrisMetal.patch(sources, resources, uniforms, outputs));
	}
}
