package mcopt.metal.iris;

import it.unimi.dsi.fastutil.ints.Int2IntMaps;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.pipeline.transform.UniformPlan;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanShaderPatcher;
import net.irisshaders.iris.samplers.ProgramSamplers;
import net.irisshaders.iris.targets.ColorAttachmentPlan;
import net.irisshaders.iris.uniforms.buffer.DrawUniforms;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MetalPatching {
	private record Key(Map<PatchShaderType, String> sources, @Nullable List<Integer> outputs, int attachments, Set<String> registered, UniformPlan plan) {
	}

	private static final Map<Key, PatchedShader> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, PatchedShader> eldest) {
			return this.size() > 512;
		}
	};

	private MetalPatching() {
	}

	static PatchedShader patch(Map<PatchShaderType, String> sources, ProgramSamplers resources, DrawUniforms uniforms,
		ColorAttachmentPlan.@Nullable Program outputs) {
		var inputs = new EnumMap<PatchShaderType, String>(PatchShaderType.class);
		sources.forEach((stage, source) -> {
			if (source != null) inputs.put(stage, source);
		});
		var key = new Key(Map.copyOf(inputs), outputs == null ? null : outputs.outputLocations(), outputs == null ? 0 : outputs.attachmentCount(),
			uniforms.registeredUniforms(), uniforms.plan());
		synchronized (CACHE) {
			PatchedShader cached = CACHE.get(key);
			if (cached != null) return cached;
		}
		PatchedShader patched = VulkanShaderPatcher.patch(key.sources(), null, outputs, key.registered()::contains, key.plan(), true, Int2IntMaps.EMPTY_MAP);
		synchronized (CACHE) {
			CACHE.put(key, patched);
		}
		return patched;
	}
}
