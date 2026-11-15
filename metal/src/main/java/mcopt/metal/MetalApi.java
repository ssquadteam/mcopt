package mcopt.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MetalApi {
	public static final int STAGE_VERTEX = 1;
	public static final int STAGE_FRAGMENT = 2;
	public static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

	public static final int USAGE_SHADER_READ = 1;
	public static final int USAGE_SHADER_WRITE = 2;
	public static final int USAGE_RENDER_TARGET = 4;

	public static final int TEXTURE_1D = 0;
	public static final int TEXTURE_2D = 1;
	public static final int TEXTURE_3D = 2;
	public static final int TEXTURE_2D_ARRAY = 3;

	public static final int PUSH_CONSTANTS_INDEX = MetalConst.PUSH_CONSTANTS_INDEX;
	public static final int VERTEX_BUFFER_BASE = MetalConst.VERTEX_BUFFER_BASE;

	public static final int STEP_CONSTANT = -1;

	private static final Map<String, Integer> FORMATS = new ConcurrentHashMap<>();
	private static volatile int storageUsage;

	private MetalApi() {
	}

	private static MetalEncoder encoder() {
		MetalEncoder e = MetalEncoder.current;
		if (e == null) throw new IllegalStateException("mcopt's Metal backend isn't running");
		return e;
	}

	public static boolean active() {
		return MetalEncoder.current != null;
	}

	public static long device() {
		return ApiNative.device(encoder().ctx);
	}

	public static long commandQueue() {
		return ApiNative.queue(encoder().ctx);
	}

	public static void addStorageUsage(int gpuTextureUsageBits) {
		storageUsage |= gpuTextureUsageBits;
	}

	static int storageUsage() {
		return storageUsage;
	}

	public static void registerFormat(Enum<?> format, int mtlPixelFormat) {
		FORMATS.put(format.name(), mtlPixelFormat);
	}

	static @Nullable Integer registeredFormat(String name) {
		return FORMATS.get(name);
	}

	public static int pixelFormat(Enum<?> format) {
		return MetalConst.pixelFormat(format);
	}

	public static int vertexFormat(Enum<?> format) {
		return MetalConst.vertexFormat(format);
	}

	public static int compareFunction(Enum<?> compareOp) {
		return MetalConst.compare(compareOp);
	}

	public static int primitive(Enum<?> topology) {
		return MetalConst.primitive(topology);
	}

	public static int topologyClass(Enum<?> topology) {
		return MetalConst.topologyClass(topology);
	}

	public static long texture(GpuTexture texture) {
		return ((MetalTexture) texture).handle;
	}

	public static long texture(GpuTextureView view) {
		return ((MetalTexture.View) view).handle;
	}

	public static long buffer(GpuBuffer buffer) {
		return encoder().use(buffer).handle;
	}

	public static long sampler(GpuSampler sampler) {
		return ((MetalSampler) sampler).handle();
	}

	public static boolean isMetalPass(RenderPassBackend pass) {
		return pass instanceof MetalRenderPass;
	}

	public static long library(String msl) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, 4096, 1);
			ByteBuffer src = MemoryUtil.memUTF8(msl);
			try {
				long lib = Native.libraryNew(encoder().ctx, MemoryUtil.memAddress(src), err, 4096);
				if (lib == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
				return lib;
			} finally {
				MemoryUtil.memFree(src);
			}
		}
	}

	public static long renderPipeline(long vertexLibrary, String vertexFunction, long fragmentLibrary, String fragmentFunction, int[] desc) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, 4096, 1);
			IntBuffer d = stack.mallocInt(desc.length).put(desc).flip();
			long pso = Native.pipelineNew(encoder().ctx, vertexLibrary, MemoryUtil.memAddress(stack.UTF8(vertexFunction)), fragmentLibrary,
				MemoryUtil.memAddress(stack.UTF8(fragmentFunction)), MemoryUtil.memAddress(d), err, 4096);
			if (pso == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			return pso;
		}
	}

	public static long depthState(int compare, boolean write) {
		return Native.depthStateNew(encoder().ctx, compare, write ? 1 : 0);
	}

	public static long computePipeline(long library, String function) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, 4096, 1);
			long pso = ApiNative.computeNew(encoder().ctx, library, MemoryUtil.memAddress(stack.UTF8(function)), err, 4096);
			if (pso == 0) throw new IllegalStateException(function + ": " + MemoryUtil.memUTF8(err));
			return pso;
		}
	}

	public static long newTexture(int type, int mtlPixelFormat, int width, int height, int depth, int mips, int usage) {
		long t = ApiNative.textureNew(encoder().ctx, type, mtlPixelFormat, width, Math.max(1, height), Math.max(1, depth), Math.max(1, mips), Math.max(1, usage));
		if (t == 0) throw new IllegalStateException("Couldn't create a " + width + "x" + height + "x" + depth + " Metal texture (format " + mtlPixelFormat + ")");
		return t;
	}

	public static long newSampler(int addressU, int addressV, int addressW, int minFilter, int magFilter, int mipFilter, int anisotropy, float maxLod, int compare) {
		return ApiNative.samplerNew(encoder().ctx, addressU, addressV, addressW, minFilter, magFilter, mipFilter, Math.max(1, anisotropy), maxLod, compare);
	}

	public static long textureBuffer(GpuBufferSlice slice, GpuFormat format) {
		MetalEncoder e = encoder();
		long view = Native.textureBuffer(e.ctx, e.use(slice.buffer()).handle, MetalConst.pixelFormat(format), slice.offset(), slice.length(), format.blockSize());
		if (view == 0) throw new IllegalStateException("Couldn't make a " + format + " texture buffer");
		return view;
	}

	public static void release(long object) {
		if (object != 0) Native.release(object);
	}

	public static void releaseLater(long object) {
		if (object != 0) encoder().releaseLater(object);
	}

	public static void afterGpu(Runnable r) {
		encoder().afterGpuFinishes(r);
	}

	public static void generateMipmaps(GpuTexture texture) {
		generateMipmaps(texture(texture));
	}

	public static void generateMipmaps(long texture) {
		ApiNative.mipmaps(encoder().enc, texture);
	}

	public static void writeTexture(long texture, @Nullable ByteBuffer data, int bytesPerRow, int bytesPerImage) {
		ApiNative.textureWrite(encoder().enc, texture, data == null ? 0 : MemoryUtil.memAddress(data), bytesPerRow, bytesPerImage);
	}

	public static Compute compute() {
		return new Compute(ApiNative.computeBegin(encoder().enc));
	}

	public static final class Compute implements AutoCloseable {
		private long encoder;

		private Compute(long encoder) {
			this.encoder = encoder;
		}

		public long handle() {
			return this.encoder;
		}

		public void pipeline(long pso) {
			ApiNative.cPipeline(this.encoder, pso);
		}

		public void buffer(int index, long buffer, long offset) {
			ApiNative.cBuffer(this.encoder, index, buffer, offset);
		}

		public void buffer(int index, GpuBufferSlice slice) {
			ApiNative.cBuffer(this.encoder, index, MetalApi.buffer(slice.buffer()), slice.offset());
		}

		public void bytes(int index, ByteBuffer data) {
			ApiNative.cBytes(this.encoder, index, MemoryUtil.memAddress(data), data.remaining());
		}

		public void texture(int index, long texture, long sampler) {
			ApiNative.cTexture(this.encoder, index, texture, sampler);
		}

		public void dispatch(int x, int y, int z, int localX, int localY, int localZ) {
			ApiNative.cDispatch(this.encoder, x, y, z, localX, localY, localZ);
		}

		public void dispatchIndirect(GpuBufferSlice args, int localX, int localY, int localZ) {
			ApiNative.cDispatchIndirect(this.encoder, MetalApi.buffer(args.buffer()), args.offset(), localX, localY, localZ);
		}

		@Override
		public void close() {
			if (this.encoder != 0) {
				ApiNative.computeEnd(this.encoder);
				this.encoder = 0;
			}
		}
	}

	private static MetalRenderPass pass(RenderPassBackend pass) {
		if (!(pass instanceof MetalRenderPass p)) throw new IllegalArgumentException("not an mcopt Metal pass: " + pass);
		return p;
	}

	public static long renderEncoder(RenderPassBackend pass) {
		return ApiNative.renderEncoder(pass(pass).enc);
	}

	public static void bindPipeline(RenderPassBackend pass, long pso, long depthState, boolean cull, boolean wireframe, float depthBiasConstant,
		float depthBiasSlope, int primitive) {
		MetalRenderPass p = pass(pass);
		p.forget();
		Native.pipeline(p.enc, pso, depthState, cull ? 1 : 0, wireframe ? 1 : 0, depthBiasConstant, depthBiasSlope, primitive);
	}

	public static void setBuffer(RenderPassBackend pass, int stages, int index, long buffer, long offset) {
		ApiNative.stageBuffer(pass(pass).enc, stages, index, buffer, offset);
	}

	public static void setBuffer(RenderPassBackend pass, int stages, int index, GpuBufferSlice slice) {
		MetalRenderPass p = pass(pass);
		ApiNative.stageBuffer(p.enc, stages, index, p.encoder.use(slice.buffer()).handle, slice.offset());
	}

	public static void setBytes(RenderPassBackend pass, int stages, int index, ByteBuffer data) {
		ApiNative.stageBytes(pass(pass).enc, stages, index, MemoryUtil.memAddress(data), data.remaining());
	}

	public static void setTexture(RenderPassBackend pass, int stages, int index, long texture, long sampler) {
		ApiNative.stageTexture(pass(pass).enc, stages, index, texture, sampler);
	}

	public static void setViewport(RenderPassBackend pass, int x, int y, int width, int height) {
		ApiNative.viewport(pass(pass).enc, x, y, width, height);
	}

	public static void draw(RenderPassBackend pass, int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		Native.draw(pass(pass).enc, vertexCount, instanceCount, firstVertex, firstInstance);
	}

	public static void drawIndexed(RenderPassBackend pass, int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
		Native.drawIndexed(pass(pass).enc, indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
	}

	public static void drawIndirect(RenderPassBackend pass, GpuBufferSlice commands, int drawCount) {
		MetalRenderPass p = pass(pass);
		Native.drawIndirect(p.enc, p.encoder.use(commands.buffer()).handle, commands.offset(), drawCount);
	}

	public static void drawIndexedIndirect(RenderPassBackend pass, GpuBufferSlice commands, int drawCount) {
		MetalRenderPass p = pass(pass);
		Native.drawIndexedIndirect(p.enc, p.encoder.use(commands.buffer()).handle, commands.offset(), drawCount);
	}

	public static void multiDraw(RenderPassBackend pass, IntBuffer parameters, int instanceCount, int firstInstance, int drawCount) {
		Native.multiDraw(pass(pass).enc, MemoryUtil.memAddress(parameters), drawCount, instanceCount, firstInstance);
	}

	public static void multiDrawIndexed(RenderPassBackend pass, IntBuffer parameters, int instanceCount, int firstInstance, int drawCount) {
		Native.multiDrawIndexed(pass(pass).enc, MemoryUtil.memAddress(parameters), drawCount, instanceCount, firstInstance);
	}

	public static void multiDraw(RenderPassBackend pass, IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		Native.multiDrawSeparate(pass(pass).enc, MemoryUtil.memAddress(firstVertices), MemoryUtil.memAddress(vertexCounts), drawCount);
	}

	public static void multiDrawIndexed(RenderPassBackend pass, long offsetsAddress, IntBuffer indexCounts, @Nullable IntBuffer baseVertices, int drawCount) {
		MetalRenderPass p = pass(pass);
		if (baseVertices == null) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer zeros = stack.callocInt(drawCount);
				Native.multiDrawIndexedSeparate(p.enc, offsetsAddress, MemoryUtil.memAddress(indexCounts), MemoryUtil.memAddress(zeros), drawCount);
			}
			return;
		}
		Native.multiDrawIndexedSeparate(p.enc, offsetsAddress, MemoryUtil.memAddress(indexCounts), MemoryUtil.memAddress(baseVertices), drawCount);
	}
}
