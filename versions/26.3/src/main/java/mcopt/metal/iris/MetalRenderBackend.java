package mcopt.metal.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass.RenderArea;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import mcopt.metal.MetalApi;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.ComputePipeline;
import net.irisshaders.iris.backend.GraphicsShader;
import net.irisshaders.iris.backend.IrisImage;
import net.irisshaders.iris.backend.PackResources;
import net.irisshaders.iris.backend.RenderBackend;
import net.irisshaders.iris.backend.WorldPassCommands;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.samplers.PackSamplers;
import net.irisshaders.iris.samplers.ProgramSamplers;
import net.irisshaders.iris.uniforms.buffer.DrawUniforms;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class MetalRenderBackend implements RenderBackend {
	private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
	private boolean depthMipsWarned;
	private boolean closed;

	public MetalRenderBackend() {
		IrisMetal.prepare();
	}

	<T> CompletableFuture<T> track(CompletableFuture<T> future) {
		this.pending.add(future);
		future.whenComplete((result, failure) -> {
			if (failure == null) this.pending.remove(future);
		});
		return future;
	}

	@Override
	public WorldPassCommands createRenderPass(RenderPassDescriptor descriptor) {
		return new MetalWorldCommands(descriptor);
	}

	@Override
	public WorldPassCommands adaptRenderPass(RenderPassBackend pass, RenderArea area) {
		return new MetalWorldCommands(pass);
	}

	@Override
	public void generateMipmaps(GpuTexture texture, boolean linear) {
		if (texture.getMipLevels() > 1) MetalApi.generateMipmaps(texture);
	}

	@Override
	public void generateDepthMips(GpuTexture texture) {
		if (texture.getMipLevels() > 1 && !this.depthMipsWarned) {
			this.depthMipsWarned = true;
			Iris.logger.warn("mcopt: depth mipmaps aren't generated on Metal yet; {} keeps only its first level", texture.getLabel());
		}
	}

	@Override
	public void clearColors(List<GpuTextureView> textures, Vector4f color) {
		for (var view : textures) {
			if (view.mipLevels() == 1) {
				clear(view, color);
				continue;
			}
			for (int level = view.baseMipLevel(); level < view.baseMipLevel() + view.mipLevels(); level++) {
				try (var mip = RenderSystem.getDevice().createTextureView(view.texture(), level, 1)) {
					clear(mip, color);
				}
			}
		}
	}

	private static void clear(GpuTextureView view, Vector4f color) {
		var encoder = MetalWorldCommands.encoder();
		encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Iris color clear")
			.withColorAttachment(view, Optional.<Vector4fc>of(new Vector4f(color)))
			.withRenderArea(new RenderArea(0, 0, view.getWidth(0), view.getHeight(0))).build());
		encoder.submitRenderPass();
	}

	@Override
	public PackResources createPackResources(PackSamplers pack) {
		return new MetalPackResources();
	}

	@Override
	public GraphicsShader createGraphicsShader(String name, PatchedShader source, ProgramSamplers resources, DrawUniforms uniforms) {
		if (this.closed) throw new IllegalStateException("render backend is closed");
		return new MetalGraphicsShader(this, name, source, resources, uniforms);
	}

	@Override
	public ComputePipeline createComputePipeline(String name, PatchedShader source, ProgramSamplers resources, DrawUniforms uniforms) {
		if (this.closed) throw new IllegalStateException("render backend is closed");
		return new MetalComputePipeline(this, name, source, resources, uniforms);
	}

	@Override
	public IrisImage createImage(String name, TextureType type, GpuFormat format, int width, int height, int depth, boolean storage) {
		return new MetalIrisImage(name, type, format, width, height, depth, storage);
	}

	@Override
	public GpuBuffer createStorageBuffer(String name, long size, byte[] content) {
		if (size <= 0 || content != null && content.length > size || size > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("invalid storage buffer size: " + name);
		}
		var buffer = RenderSystem.getDevice().createBuffer(() -> name,
			ShaderStorageBuffer.USAGE_STORAGE | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_INDIRECT_PARAMETERS, size);
		ByteBuffer data = MemoryUtil.memCalloc((int) size);
		try {
			if (content != null) data.put(0, content);
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), data);
		} finally {
			MemoryUtil.memFree(data);
		}
		return buffer;
	}

	@Override
	public void retire(Runnable destructor) {
		MetalApi.afterGpu(destructor);
	}

	@Override
	public void finishCompilation() {
		RuntimeException failure = null;
		for (var future : List.copyOf(this.pending)) {
			if (future.isCancelled()) {
				this.pending.remove(future);
				continue;
			}
			try {
				IrisMetal.await(future);
			} catch (RuntimeException e) {
				if (failure == null) failure = e;
			} finally {
				this.pending.remove(future);
			}
		}
		if (failure != null) throw failure;
	}

	@Override
	public void close() {
		this.closed = true;
		this.pending.clear();
	}
}
