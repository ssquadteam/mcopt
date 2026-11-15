package mcopt.metal.iris;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import mcopt.metal.MetalApi;
import net.irisshaders.iris.backend.PackResources;
import net.irisshaders.iris.gl.sampler.GlSampler;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

final class MetalPackResources implements PackResources {
	private final Map<GlSampler.Description, Long> samplers = new HashMap<>();
	private @Nullable GpuBufferSlice frame;

	@Override
	public void beginFrame(GpuBufferSlice frameUniforms) {
		if (frameUniforms != null) this.frame = frameUniforms;
	}

	@Nullable GpuBufferSlice frameUniforms() {
		return this.frame;
	}

	long sampler(GlSampler.Description d) {
		return this.samplers.computeIfAbsent(d, MetalPackResources::create);
	}

	static long create(GlSampler.Description d) {
		int filter = d.linear() ? 1 : 0;
		int address = d.clamp() ? 0 : 2;
		int mip = !d.mipmapped() ? 0 : d.linearMips() ? 2 : 1;
		return MetalApi.newSampler(address, address, address, filter, filter, mip, 1, d.mipmapped() ? 1000 : 0, d.hardwareShadow() ? 3 : -1);
	}

	@Override
	public void close() {
		this.samplers.values().forEach(MetalApi::releaseLater);
		this.samplers.clear();
		this.frame = null;
	}
}
