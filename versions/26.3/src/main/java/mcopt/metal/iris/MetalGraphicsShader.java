package mcopt.metal.iris;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import mcopt.metal.MetalApi;
import net.irisshaders.iris.backend.FramebufferDescription;
import net.irisshaders.iris.backend.GraphicsPipeline;
import net.irisshaders.iris.backend.GraphicsShader;
import net.irisshaders.iris.backend.PipelineState;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.pipeline.transform.WorldShaderTransformer;
import net.irisshaders.iris.samplers.ProgramSamplers;
import net.irisshaders.iris.uniforms.buffer.DrawUniforms;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class MetalGraphicsShader implements GraphicsShader {
	private final MetalRenderBackend backend;
	private final PatchedShader source;
	private final ProgramSamplers resources;
	private final DrawUniforms uniforms;
	private final CompletableFuture<MetalProgram> program;
	private final List<Pipeline> pipelines = new ArrayList<>();
	private boolean closed;

	MetalGraphicsShader(MetalRenderBackend backend, String name, PatchedShader source, ProgramSamplers resources, DrawUniforms uniforms) {
		this.backend = backend;
		this.source = source;
		this.resources = resources;
		this.uniforms = uniforms;
		Map<String, GlSampler.Description> constexpr = constexprSamplers(source.layout(), resources);
		this.program = backend.track(CompletableFuture.supplyAsync(() -> MetalProgram.compile(name, source, constexpr), IrisMetal.COMPILER));
	}

	static Map<String, GlSampler.Description> constexprSamplers(ShaderResourceLayout layout, ProgramSamplers resources) {
		if (!MetalProgram.needsConstexpr(layout)) return Map.of();
		Map<String, GlSampler.Description> fixed = new HashMap<>();
		for (var d : layout.descriptors()) {
			if (d.kind() != ShaderResourceLayout.Kind.SAMPLER || MetalProgram.texel(d)) continue;
			var sampler = resources.getSamplers().get(d.name());
			if (sampler == null || sampler.texture() == null) continue;
			try {
				var resolved = sampler.texture().resolve(sampler.reference().type());
				if (resolved.nativeSampler() == null && resolved.sampler() != null) fixed.put(d.name(), MetalProgram.state(resolved.sampler(), sampler.reference().type()));
			} catch (RuntimeException notReady) {
			}
		}
		return fixed;
	}

	@Override
	public PatchedShader getSource() {
		return this.source;
	}

	@Override
	public GraphicsPipeline createPipeline(PipelineState state) {
		return this.createPipeline(state, false);
	}

	@Override
	public GraphicsPipeline createPipeline(PipelineState state, boolean background) {
		if (this.closed) throw new IllegalStateException("shader closed");
		Pipeline pipeline = new Pipeline(state, this.backend.track(this.program.thenApplyAsync(p -> Built.of(p, state), IrisMetal.COMPILER)));
		this.pipelines.add(pipeline);
		return pipeline;
	}

	@Override
	public void cancelCompilation() {
		this.pipelines.forEach(p -> p.compilation.cancel(false));
	}

	@Override
	public void close() {
		if (this.closed) return;
		this.closed = true;
		this.pipelines.forEach(Pipeline::close);
		this.pipelines.clear();
		this.program.handle((p, failure) -> {
			if (p != null) p.close();
			return null;
		});
	}

	record Built(MetalProgram program, long pso, long depthState, boolean cull, boolean wireframe, float biasConstant, float biasSlope, int primitive,
		int zeroBinding) {
		static Built of(MetalProgram program, PipelineState state) {
			var vanilla = state.vanillaPipeline();
			PrimitiveTopology topology = vanilla == null ? PrimitiveTopology.TRIANGLES : vanilla.getPrimitiveTopology();
			boolean points = topology == PrimitiveTopology.POINTS;
			IntArrayList d = new IntArrayList();
			int zeroBinding = -1;
			if (vanilla != null) {
				var formats = state.vertexFormats();
				int buffers = formats.size();
				if (state.zeroVertexBuffer() != null) zeroBinding = WorldShaderTransformer.fallbackVertexBinding(formats);
				boolean extra = zeroBinding >= 0 && zeroBinding >= buffers;
				int used = 0;
				for (int i = 0; i < buffers; i++) if (formats.get(i) != null || i == zeroBinding) used++;
				d.add(used + (extra ? 1 : 0));
				for (int i = 0; i < buffers; i++) {
					if (formats.get(i) == null && i != zeroBinding) continue;
					d.add(MetalApi.VERTEX_BUFFER_BASE + i);
					d.add(i == zeroBinding ? 16 : formats.get(i).getVertexSize());
					d.add(i == zeroBinding ? MetalApi.STEP_CONSTANT : formats.get(i).getStepRate());
				}
				if (extra) {
					d.add(MetalApi.VERTEX_BUFFER_BASE + zeroBinding);
					d.add(16);
					d.add(MetalApi.STEP_CONSTANT);
				}
			} else if (state.vertexAttributes().isEmpty()) {
				d.add(0);
			} else {
				d.add(1);
				d.add(MetalApi.VERTEX_BUFFER_BASE);
				d.add(state.vertexStride());
				d.add(0);
			}
			d.add(state.vertexAttributes().size());
			for (var a : state.vertexAttributes()) {
				d.add(a.location());
				d.add(MetalApi.VERTEX_BUFFER_BASE + a.binding());
				d.add(a.offset());
				d.add(MetalApi.vertexFormat(a.format()));
			}
			int colors = state.colorAttachmentCount();
			var written = program.source.writtenAttachments();
			d.add(colors);
			for (int i = 0; i < colors; i++) {
				var format = state.colorFormats()[i];
				int mask = PipelineState.colorMask(vanilla, written == null || written.get(i));
				BlendMode blend = mask == 0 || PipelineState.isInteger(format) ? null : state.blendModes().get(i);
				d.add(MetalApi.pixelFormat(format));
				d.add((mask & 1) << 3 | (mask & 2) << 1 | (mask & 4) >> 1 | (mask & 8) >> 3);
				if (blend == null) {
					d.addElements(d.size(), new int[] {0, 1, 0, 0, 1, 0, 0});
				} else {
					d.addElements(d.size(), new int[] {1, factor(blend.srcRgb()), factor(blend.dstRgb()), 0, factor(blend.srcAlpha()), factor(blend.dstAlpha()), 0});
				}
			}
			d.add(state.depthFormat() == null ? 0 : MetalApi.pixelFormat(state.depthFormat()));
			d.add(MetalApi.topologyClass(topology));
			var vertex = points ? program.pointVertex() : program.stage(PatchShaderType.VERTEX);
			var fragment = program.stage(PatchShaderType.FRAGMENT);
			long pso = MetalApi.renderPipeline(vertex.library(), vertex.entry(), fragment.library(), fragment.entry(), d.toIntArray());
			var depth = state.depthState();
			long depthState = depth == null ? MetalApi.depthState(7, false) : MetalApi.depthState(MetalApi.compareFunction(depth.depthTest()), depth.writeDepth());
			boolean wireframe = vanilla != null && vanilla.getPolygonMode() == PolygonMode.WIREFRAME;
			return new Built(program, pso, depthState, PipelineState.cull(vanilla, state.shadowPass()), wireframe, depth == null ? 0 : depth.depthBiasConstant(),
				depth == null ? 0 : depth.depthBiasScaleFactor(), MetalApi.primitive(topology), zeroBinding);
		}

		private static int factor(int gl) {
			return switch (gl) {
				case 0 -> 0;
				case 1 -> 1;
				case 0x300 -> 2;
				case 0x301 -> 3;
				case 0x302 -> 4;
				case 0x303 -> 5;
				case 0x304 -> 8;
				case 0x305 -> 9;
				case 0x306 -> 6;
				case 0x307 -> 7;
				case 0x308 -> 10;
				case 0x8001 -> 11;
				case 0x8002 -> 12;
				case 0x8003 -> 13;
				case 0x8004 -> 14;
				default -> throw new IllegalArgumentException("unknown blend factor 0x" + Integer.toHexString(gl));
			};
		}

		void release() {
			MetalApi.releaseLater(this.pso);
			MetalApi.releaseLater(this.depthState);
		}
	}

	final class Pipeline implements GraphicsPipeline {
		private final PipelineState state;
		final CompletableFuture<Built> compilation;
		private Built built;
		private boolean closed;

		private Pipeline(PipelineState state, CompletableFuture<Built> compilation) {
			this.state = state;
			this.compilation = compilation;
		}

		Built built() {
			if (this.built == null) this.built = IrisMetal.await(this.compilation);
			return this.built;
		}

		void bind(RenderPassBackend pass, MetalWorldCommands commands) {
			Built b = this.built();
			MetalApi.bindPipeline(pass, b.pso(), b.depthState(), b.cull(), b.wireframe(), b.biasConstant(), b.biasSlope(), b.primitive());
			var pack = (MetalPackResources) MetalGraphicsShader.this.resources.getPackSamplers().getResources();
			b.program().bind(new MetalProgram.Binder() {
				@Override
				public void buffer(int index, GpuBufferSlice slice) {
					MetalApi.setBuffer(pass, MetalApi.STAGE_ALL, index, slice);
				}

				@Override
				public void texture(int index, long texture, long sampler) {
					MetalApi.setTexture(pass, MetalApi.STAGE_ALL, index, texture, sampler);
				}

				@Override
				public void bytes(int index, ByteBuffer data) {
					MetalApi.setBytes(pass, MetalApi.STAGE_ALL, index, data);
				}
			}, MetalGraphicsShader.this.resources, MetalGraphicsShader.this.uniforms, pack);
			if (b.zeroBinding() >= 0) {
				MetalApi.setBuffer(pass, MetalApi.STAGE_VERTEX, MetalApi.VERTEX_BUFFER_BASE + b.zeroBinding(), MetalApi.buffer(this.state.zeroVertexBuffer()), 0);
			}
			commands.irisPushConstants(MetalGraphicsShader.this.uniforms.pushConstants());
		}

		@Override
		public FramebufferDescription framebuffer() {
			return this.state.framebuffer();
		}

		@Override
		public void close() {
			if (this.closed) return;
			this.closed = true;
			this.compilation.handle((b, failure) -> {
				if (b != null) b.release();
				return null;
			});
		}
	}
}
