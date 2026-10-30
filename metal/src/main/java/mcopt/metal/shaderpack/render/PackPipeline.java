package mcopt.metal.shaderpack.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mcopt.metal.MetalBridge;
import mcopt.metal.PipelineInfo;
import mcopt.metal.shaderpack.PackCompiler;
import mcopt.metal.shaderpack.PackUniforms;
import mcopt.metal.shaderpack.ShaderCompiler;

final class PackPipeline {
	record Attachment(int pixelFormat, int writeMask, int @org.jspecify.annotations.Nullable [] blend) {
	}

	final String program;
	final long pso;
	final long depthState;
	final boolean cull;
	final int primitive;
	final float biasConstant, biasSlope;
	final PackUniforms.Block vertexBlock, fragmentBlock;
	final List<ShaderCompiler.Texture> textures;
	final List<Integer> drawBuffers;
	int frame = -1;
	long vertexOffset, fragmentOffset;

	private PackPipeline(String program, long pso, long depthState, boolean cull, int primitive, float biasConstant, float biasSlope, PackUniforms.Block vertexBlock,
		PackUniforms.Block fragmentBlock, List<ShaderCompiler.Texture> textures, List<Integer> drawBuffers) {
		this.program = program;
		this.pso = pso;
		this.depthState = depthState;
		this.cull = cull;
		this.primitive = primitive;
		this.biasConstant = biasConstant;
		this.biasSlope = biasSlope;
		this.vertexBlock = vertexBlock;
		this.fragmentBlock = fragmentBlock;
		this.textures = textures;
		this.drawBuffers = drawBuffers;
	}

	static PackPipeline build(long ctx, PackCompiler.Compiled compiled, PackUniforms uniforms, Map<String, String> defaults, List<Attachment> attachments, int depthFormat,
		@org.jspecify.annotations.Nullable PipelineInfo game, boolean depthTest, int depthCompare, boolean depthWrite, boolean cull, int primitive, int topologyClass) {
		long vlib = MetalBridge.library(ctx, compiled.vertex().source());
		long flib = 0;
		try {
			flib = MetalBridge.library(ctx, compiled.fragment().source());
			List<Integer> d = new ArrayList<>();
			List<PipelineInfo.Attrib> attribs = game == null || game.name().startsWith("sodium:") ? List.of() : game.attribs();
			Map<Integer, int[]> buffers = new LinkedHashMap<>();
			for (PipelineInfo.Attrib a : attribs) buffers.putIfAbsent(a.bufferSlot(), new int[] {a.stride(), a.stepRate()});
			d.add(buffers.size());
			for (var e : buffers.entrySet()) {
				d.add(27 + e.getKey());
				d.add(e.getValue()[0]);
				d.add(e.getValue()[1]);
			}
			d.add(attribs.size());
			for (PipelineInfo.Attrib a : attribs) {
				d.add(a.location());
				d.add(27 + a.bufferSlot());
				d.add(a.offset());
				d.add(vertexFormat(a.format()));
			}
			d.add(attachments.size());
			for (Attachment a : attachments) {
				d.add(a.pixelFormat());
				d.add(a.writeMask());
				if (a.blend() != null) {
					d.add(1);
					for (int x : a.blend()) d.add(x);
				} else {
					d.add(0);
					d.add(1);
					d.add(0);
					d.add(0);
					d.add(1);
					d.add(0);
					d.add(0);
				}
			}
			d.add(depthFormat);
			d.add(topologyClass);
			int[] description = d.stream().mapToInt(Integer::intValue).toArray();
			long pso = MetalBridge.renderPipeline(ctx, vlib, compiled.vertex().entry(), flib, compiled.fragment().entry(), description);
			long depthState = depthFormat == 0 ? 0 : MetalBridge.depthState(ctx, depthTest ? depthCompare : 7, depthTest && depthWrite);
			List<ShaderCompiler.Texture> textures = new ArrayList<>(compiled.vertex().reflection().textures());
			for (ShaderCompiler.Texture t : compiled.fragment().reflection().textures()) {
				if (textures.stream().noneMatch(x -> x.slot() == t.slot())) textures.add(t);
			}
			return new PackPipeline(compiled.program(), pso, depthState, cull, primitive, game == null ? 0 : -game.biasConstant(), game == null ? 0 : -game.biasSlope(),
				uniforms.block(compiled.vertex().reflection(), defaults), uniforms.block(compiled.fragment().reflection(), defaults), textures, compiled.drawBuffers());
		} finally {
			MetalBridge.release(vlib);
			if (flib != 0) MetalBridge.release(flib);
		}
	}

	private static int vertexFormat(String format) {
		return switch (format) {
			case "R8_UNORM" -> 47;
			case "R8_SNORM" -> 48;
			case "R8_UINT" -> 45;
			case "R8_SINT" -> 46;
			case "RG8_UNORM" -> 7;
			case "RG8_SNORM" -> 10;
			case "RG8_UINT" -> 1;
			case "RG8_SINT" -> 4;
			case "RGB8_UNORM" -> 8;
			case "RGB8_SNORM" -> 11;
			case "RGB8_UINT" -> 2;
			case "RGB8_SINT" -> 5;
			case "RGBA8_UNORM" -> 9;
			case "RGBA8_SNORM" -> 12;
			case "RGBA8_UINT" -> 3;
			case "RGBA8_SINT" -> 6;
			case "R16_UNORM" -> 51;
			case "R16_SNORM" -> 52;
			case "R16_UINT" -> 49;
			case "R16_SINT" -> 50;
			case "R16_FLOAT" -> 53;
			case "RG16_UNORM" -> 19;
			case "RG16_SNORM" -> 22;
			case "RG16_UINT" -> 13;
			case "RG16_SINT" -> 16;
			case "RG16_FLOAT" -> 25;
			case "RGBA16_UNORM" -> 21;
			case "RGBA16_SNORM" -> 24;
			case "RGBA16_UINT" -> 15;
			case "RGBA16_SINT" -> 18;
			case "RGBA16_FLOAT" -> 27;
			case "R32_FLOAT" -> 28;
			case "RG32_FLOAT" -> 29;
			case "RGB32_FLOAT" -> 30;
			case "RGBA32_FLOAT" -> 31;
			case "R32_UINT" -> 36;
			case "R32_SINT" -> 32;
			case "RG32_UINT" -> 37;
			case "RG32_SINT" -> 33;
			default -> 0;
		};
	}

	void release() {
		MetalBridge.release(this.pso);
		if (this.depthState != 0) MetalBridge.release(this.depthState);
	}
}
