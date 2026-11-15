package mcopt.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.DeviceType;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.HintsAndWorkarounds;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;
import mcopt.metal.render.RenderFlags;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class MetalDevice implements GpuDeviceBackend {
	private final long ctx;
	private final MetalEncoder encoder;
	private final DeviceInfo info;
	private final Allocations allocations;
	private final ShaderSource shaderSource;
	private final Map<RenderPipeline, MetalPipeline> pipelines = new IdentityHashMap<>();
	/** -Dmcopt.render.asyncPipelines: a reload's static pipelines compile in parallel on the background workers, all finished before the reload ends (as 26.3 does). */
	private final Map<RenderPipeline, FutureTask<MetalPipeline>> pending = new IdentityHashMap<>();
	private boolean batchQueued;

	MetalDevice(ShaderSource shaderSource) throws BackendCreationException {
		this.shaderSource = shaderSource;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long name = stack.nmalloc(1, 256), err = stack.nmalloc(1, 1024);
			this.ctx = Native.create(name, 256, err, 1024);
			if (this.ctx == 0) throw new BackendCreationException("Metal unavailable: " + MemoryUtil.memUTF8(err), BackendCreationException.Reason.OTHER);
			this.info = new DeviceInfo(
				MemoryUtil.memUTF8(name), "Apple", "Metal 3 (mcopt)", true, "Metal", 1.0F,
				new DeviceLimits(16, 256, 16384, Native.maxBufferLength(this.ctx), Integer.MAX_VALUE, 8),
				new DeviceFeatures(true, true, true, true, true, true, true),
				Set.of(),
				new HintsAndWorkarounds(false, false),
				DeviceType.INTEGRATED);
		}
		this.encoder = new MetalEncoder(this.ctx);
		this.allocations = new Allocations(this.ctx, this.encoder);
		String diag = System.getProperty("mcopt.metal.cbdiag");
		if (diag != null) Native.diagEnable(Double.parseDouble(diag));
		if (mcopt.metal.cpu.Cpu.PASS || mcopt.metal.cpu.Cpu.CMD_AHEAD) Native.cpuFlags(mcopt.metal.cpu.Cpu.nativeFlags(mcopt.metal.cpu.Cpu.PASS));
	}

	MetalPipeline pipeline(RenderPipeline pipeline) {
		MetalPipeline compiled = this.pipelines.get(pipeline);
		if (compiled != null) return compiled;
		FutureTask<MetalPipeline> task = this.pending.remove(pipeline);
		compiled = task != null ? finish(task) : MetalPipeline.compile(this.encoder, pipeline, this.shaderSource);
		this.pipelines.put(pipeline, compiled);
		return compiled;
	}

	/** Runs the task here if no worker has started it, otherwise waits for the worker. */
	private static MetalPipeline finish(FutureTask<MetalPipeline> task) {
		task.run();
		try {
			return task.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		} catch (ExecutionException e) {
			throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
		}
	}

	private void queue(List<RenderPipeline> list, ShaderSource source) {
		Executor executor = Util.backgroundExecutor();
		for (RenderPipeline p : list) {
			if (this.pipelines.containsKey(p) || this.pending.containsKey(p)) continue;
			FutureTask<MetalPipeline> task = new FutureTask<>(() -> MetalPipeline.compile(this.encoder, p, source));
			this.pending.put(p, task);
			executor.execute(task);
		}
	}

	private void dropPending() {
		for (FutureTask<MetalPipeline> task : this.pending.values()) {
			if (task.cancel(false)) continue;
			try {
				MetalPipeline compiled = task.get();
				if (compiled != null) compiled.close();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (ExecutionException ignored) {
				// it failed, so nothing was made
			}
		}
		this.pending.clear();
	}

	@Override
	public GpuSurfaceBackend createSurface(long windowHandle) {
		return new MetalSurface(this.ctx, this.encoder, windowHandle);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return this.encoder;
	}

	@Override
	public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
		return MetalSampler.create(this.ctx, u, v, min, mag, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(@Nullable Supplier<String> label, @GpuTexture.Usage int usage, GpuFormat format, int width, int height, int layers, int mips) {
		return this.createTexture(label == null ? null : label.get(), usage, format, width, height, layers, mips);
	}

	@Override
	public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, GpuFormat format, int width, int height, int layers, int mips) {
		return this.allocations.texture(label, usage, format, width, height, layers, mips);
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture) {
		return this.createTextureView(texture, 0, texture.getMipLevels());
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture, int baseMip, int mips) {
		return new MetalTexture.View(this.encoder, (MetalTexture) texture, baseMip, mips);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
		return this.allocations.buffer(label, usage, size);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
		MetalBuffer buffer = new MetalBuffer(this.encoder, Native.bufferNew(this.ctx, data.remaining()), usage, data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), buffer.address, data.remaining());
		return buffer;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return false;
	}

	@Override
	public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, @Nullable ShaderSource source) {
		ShaderSource s = source == null ? this.shaderSource : source;
		MetalPipeline compiled = this.pipelines.get(pipeline);
		if (compiled != null) return compiled;
		if (RenderFlags.ASYNC_PIPELINES && source != null && !this.batchQueued) {
			this.batchQueued = true;
			this.queue(RenderPipelines.getStaticPipelines(), s);
		}
		FutureTask<MetalPipeline> task = this.pending.remove(pipeline);
		compiled = task != null ? finish(task) : MetalPipeline.compile(this.encoder, pipeline, s);
		this.pipelines.put(pipeline, compiled);
		return compiled;
	}

	@Override
	public void clearPipelineCache() {
		this.encoder.waitIdle();
		this.dropPending();
		this.batchQueued = false;
		this.pipelines.values().forEach(MetalPipeline::close);
		this.pipelines.clear();
	}

	@Override
	public void close() {
		this.encoder.waitIdle();
		this.dropPending();
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new GpuQueryPool() {
			@Override
			public int size() {
				return size;
			}

			@Override
			public OptionalLong getValue(int index) {
				return OptionalLong.empty();
			}

			@Override
			public OptionalLong[] getValues(int index, int count) {
				OptionalLong[] values = new OptionalLong[count];
				java.util.Arrays.fill(values, OptionalLong.empty());
				return values;
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public long getTimestampNow() {
		return 0;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.info;
	}
}
