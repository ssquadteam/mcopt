package mcopt.metal.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass.RenderArea;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import mcopt.metal.MetalApi;
import net.irisshaders.iris.backend.GraphicsPipeline;
import net.irisshaders.iris.backend.WorldPassCommands;
import net.irisshaders.iris.pipeline.transform.VulkanPushConstants;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

final class MetalWorldCommands implements WorldPassCommands {
	private final boolean owned;
	private RenderPassBackend pass;
	private RenderPassDescriptor descriptor;
	private MetalGraphicsShader.Pipeline pipeline;
	private final Map<TexelKey, Long> texels = new HashMap<>();
	private final byte[] push = new byte[VulkanPushConstants.MAX_SIZE];
	private int pushLength;
	private boolean pushDirty;
	private boolean closed;

	private record TexelKey(GpuBufferSlice slice, GpuFormat format) {
	}

	static CommandEncoderBackend encoder() {
		return ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend();
	}

	MetalWorldCommands(RenderPassDescriptor descriptor) {
		this.owned = true;
		this.descriptor = descriptor;
		this.pass = encoder().createRenderPass(descriptor);
	}

	MetalWorldCommands(RenderPassBackend pass) {
		this.owned = false;
		this.pass = pass;
	}

	void irisPushConstants(ByteBuffer data) {
		int n = Math.min(data.remaining(), this.push.length);
		if (n == 0) return;
		int native0 = VulkanPushConstants.NATIVE_OFFSET, nativeEnd = native0 + VulkanPushConstants.NATIVE_SIZE;
		for (int i = 0; i < n; i++) {
			if (i < native0 || i >= nativeEnd) this.push[i] = data.get(data.position() + i);
		}
		this.pushLength = Math.max(this.pushLength, n);
		this.pushDirty = true;
	}

	@Override
	public void pushConstants(ByteBuffer data) {
		int n = Math.min(data.remaining(), this.push.length - VulkanPushConstants.NATIVE_OFFSET);
		data.get(data.position(), this.push, VulkanPushConstants.NATIVE_OFFSET, n);
		this.pushLength = Math.max(this.pushLength, VulkanPushConstants.NATIVE_OFFSET + n);
		this.pushDirty = true;
	}

	private boolean prepareDraw() {
		if (this.pipeline == null) return false;
		if (this.pushDirty && this.pushLength > 0) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer bytes = stack.malloc(this.pushLength).put(this.push, 0, this.pushLength).flip();
				MetalApi.setBytes(this.pass, MetalApi.STAGE_ALL, MetalApi.PUSH_CONSTANTS_INDEX, bytes);
			}
			this.pushDirty = false;
		}
		return true;
	}

	@Override
	public void bindPipeline(GraphicsPipeline pipeline) {
		this.pipeline = (MetalGraphicsShader.Pipeline) pipeline;
		this.pipeline.bind(this.pass, this);
		this.pushDirty = true;
	}

	@Override
	public void setViewport(int x, int y, int width, int height) {
		MetalApi.setViewport(this.pass, x, y, width, height);
	}

	@Override
	public void setAttachments(List<GpuTextureView> colors, GpuTextureView depth, RenderArea area) {
		if (!this.owned) throw new IllegalStateException("Iris can only retarget its own passes");
		CommandEncoderBackend encoder = encoder();
		encoder.submitRenderPass();
		var builder = RenderPassDescriptor.builder(this.descriptor.label()).withRenderArea(area);
		colors.forEach(color -> builder.withColorAttachment(color, Optional.empty()));
		if (depth != null) builder.withDepthAttachment(depth, OptionalDouble.empty());
		this.descriptor = builder.build();
		this.pass = encoder.createRenderPass(this.descriptor);
		this.pipeline = null;
	}

	@Override
	public long getTexelBufferView(GpuBufferSlice slice, GpuFormat format) {
		return this.texels.computeIfAbsent(new TexelKey(slice, format), key -> MetalApi.textureBuffer(key.slice(), key.format()));
	}

	@Override
	public void end() {
		this.texels.values().forEach(MetalApi::releaseLater);
		this.texels.clear();
	}

	@Override
	public void close() {
		if (this.owned && !this.closed) {
			this.closed = true;
			encoder().submitRenderPass();
			this.end();
		}
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
		this.pass.pushDebugGroup(label);
	}

	@Override
	public void popDebugGroup() {
		this.pass.popDebugGroup();
	}

	@Override
	public void setPipeline(BackendRenderPipeline pipeline) {
	}

	@Override
	public void setUniform(int index, Object value) {
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		this.pass.enableScissor(x, y, width, height);
	}

	@Override
	public void disableScissor() {
		this.pass.disableScissor();
	}

	@Override
	public void setVertexBuffer(int slot, GpuBufferSlice slice) {
		this.pass.setVertexBuffer(slot, slice);
	}

	@Override
	public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
		this.pass.setIndexBuffer(buffer, type);
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int query) {
		this.pass.writeTimestamp(pool, query);
	}

	@Override
	public void draw(int count, int instances, int first, int firstInstance) {
		if (this.prepareDraw()) MetalApi.draw(this.pass, count, instances, first, firstInstance);
	}

	@Override
	public void drawIndexed(int count, int instances, int first, int baseVertex, int firstInstance) {
		if (this.prepareDraw()) MetalApi.drawIndexed(this.pass, count, instances, first, baseVertex, firstInstance);
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int count) {
		if (this.prepareDraw()) MetalApi.drawIndirect(this.pass, commands, count);
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int count) {
		if (this.prepareDraw()) MetalApi.drawIndexedIndirect(this.pass, commands, count);
	}

	@Override
	public void multiDraw(IntBuffer commands, int instances, int firstInstance, int count) {
		if (this.prepareDraw()) MetalApi.multiDraw(this.pass, commands, instances, firstInstance, count);
	}

	@Override
	public void multiDrawIndexed(IntBuffer commands, int instances, int firstInstance, int count) {
		if (this.prepareDraw()) MetalApi.multiDrawIndexed(this.pass, commands, instances, firstInstance, count);
	}

	@Override
	public void multiDraw(IntBuffer first, IntBuffer counts, int count) {
		if (this.prepareDraw()) MetalApi.multiDraw(this.pass, first, counts, count);
	}

	@Override
	public void multiDrawIndexed(PointerBuffer offsets, IntBuffer counts, IntBuffer bases, int count) {
		if (this.prepareDraw()) MetalApi.multiDrawIndexed(this.pass, MemoryUtil.memAddress(offsets), counts, bases, count);
	}
}
