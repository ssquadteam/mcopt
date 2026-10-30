package mcopt.metal.shaderpack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProgramAssembler {
	public interface Adapter {
		String key();

		String declarations(ShaderCompiler.Kind stage);

		String matrices(ShaderCompiler.Kind stage);

		String vertex();

		default String alphaTest() {
			return "";
		}

		default int output(int location) {
			return location;
		}
	}

	private static final String LIGHTMAP_MATRIX = "mat4(vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0), vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0))";
	private static final Map<String, String> CANONICAL = Map.ofEntries(Map.entry("mc_Entity", "iris_in_Entity"), Map.entry("iris_Entity", "iris_in_Entity"),
		Map.entry("mc_midTexCoord", "iris_in_MidTex"), Map.entry("at_tangent", "iris_in_Tangent"), Map.entry("at_midBlock", "iris_in_MidBlock"),
		Map.entry("at_velocity", "iris_in_Velocity"), Map.entry("vaPosition", "vec4(iris_in_Position, 1.0)"), Map.entry("vaColor", "iris_in_Color"),
		Map.entry("vaUV0", "vec4(iris_in_UV0, 0.0, 1.0)"), Map.entry("vaUV1", "vec4(iris_in_UV1, 0.0, 1.0)"), Map.entry("vaUV2", "vec4(iris_in_UV2, 0.0, 1.0)"),
		Map.entry("vaNormal", "vec4(iris_in_Normal, 0.0)"), Map.entry("mc_chunkFade", "vec4(1.0)"));

	private ProgramAssembler() {
	}

	public static Map<String, Integer> locations(GlslRewrite.Result vertex) {
		Map<String, Integer> out = new LinkedHashMap<>();
		int next = 0;
		for (GlslRewrite.Varying v : vertex.outputs) {
			out.put(v.name(), next);
			next += v.slots(vertex.structs);
		}
		return out;
	}

	private static String common(GlslRewrite.Result r) {
		StringBuilder b = new StringBuilder();
		b.append("#version 450\n#extension GL_EXT_shader_image_load_formatted : enable\n#extension GL_ARB_shader_draw_parameters : enable\n");
		for (String e : r.extensions) {
			if (e.equals("GL_EXT_control_flow_attributes") || e.startsWith("GL_KHR_shader_subgroup")) {
				b.append("#extension ").append(e).append(" : enable\n");
			}
		}
		b.append("mat4 iris_ModelViewMat = mat4(1.0);\nmat4 iris_ProjMat = mat4(1.0);\nmat4 iris_TextureMat0 = mat4(1.0);\nmat4 iris_TextureMat1 = ")
			.append(LIGHTMAP_MATRIX).append(";\n");
		b.append("vec3 iris_ChunkOffset = vec3(0.0);\nvec4 iris_ColorModulator = vec4(1.0);\nfloat iris_AlphaRef = 0.0;\n");
		b.append("mat4 iris_ModelViewMatInv = mat4(1.0);\nmat4 iris_ProjMatInv = mat4(1.0);\nmat4 iris_MVP = mat4(1.0);\nmat4 iris_MVPInv = mat4(1.0);\n");
		b.append("mat4 iris_ModelViewMatT = mat4(1.0);\nmat4 iris_ProjMatT = mat4(1.0);\nmat4 iris_ModelViewMatInvT = mat4(1.0);\nmat4 iris_ProjMatInvT = mat4(1.0);\n");
		b.append("mat3 iris_NormalMat = mat3(1.0);\nvec4 iris_ClipVertex;\nbool iris_Hand = false;\n");
		b.append("struct iris_FogParameters { vec4 color; float density; float start; float end; float scale; };\niris_FogParameters iris_Fog;\n");
		return b.toString();
	}

	private static String derive(Set<String> used) {
		StringBuilder b = new StringBuilder("void iris_derive() {\n");
		boolean inv = used.contains("iris_ModelViewMatInv") || used.contains("iris_ModelViewMatInvT") || used.contains("iris_MVPInv");
		if (inv) b.append("\tiris_ModelViewMatInv = inverse(iris_ModelViewMat);\n");
		if (used.contains("iris_ProjMatInv") || used.contains("iris_ProjMatInvT") || used.contains("iris_MVPInv")) {
			b.append("\tiris_ProjMatInv = inverse(iris_ProjMat);\n");
		}
		if (used.contains("iris_MVP") || used.contains("iris_ftransform") || used.contains("iris_MVPInv")) b.append("\tiris_MVP = iris_ProjMat * iris_ModelViewMat;\n");
		if (used.contains("iris_MVPInv")) b.append("\tiris_MVPInv = iris_ModelViewMatInv * iris_ProjMatInv;\n");
		if (used.contains("iris_NormalMat")) b.append("\tiris_NormalMat = transpose(inverse(mat3(iris_ModelViewMat)));\n");
		if (used.contains("iris_ModelViewMatT")) b.append("\tiris_ModelViewMatT = transpose(iris_ModelViewMat);\n");
		if (used.contains("iris_ProjMatT")) b.append("\tiris_ProjMatT = transpose(iris_ProjMat);\n");
		if (used.contains("iris_ModelViewMatInvT")) b.append("\tiris_ModelViewMatInvT = transpose(iris_ModelViewMatInv);\n");
		if (used.contains("iris_ProjMatInvT")) b.append("\tiris_ProjMatInvT = transpose(iris_ProjMatInv);\n");
		return b.append("}\n").toString();
	}

	private static String helpers(Set<String> used) {
		StringBuilder b = new StringBuilder();
		if (used.contains("iris_shadow2D")) b.append("vec4 iris_shadow2D(sampler2DShadow s, vec3 c) { return vec4(texture(s, c)); }\n")
			.append("vec4 iris_shadow2D(sampler2D s, vec3 c) { return vec4(step(c.z, texture(s, c.xy).r)); }\n");
		if (used.contains("iris_shadow2DLod")) b.append("vec4 iris_shadow2DLod(sampler2DShadow s, vec3 c, float l) { return vec4(textureLod(s, c, l)); }\n")
			.append("vec4 iris_shadow2DLod(sampler2D s, vec3 c, float l) { return vec4(step(c.z, textureLod(s, c.xy, l).r)); }\n");
		if (used.contains("iris_shadow2DProj")) b.append("vec4 iris_shadow2DProj(sampler2DShadow s, vec4 c) { return vec4(textureProj(s, c)); }\n");
		if (used.contains("iris_shadow2DProjLod")) b.append("vec4 iris_shadow2DProjLod(sampler2DShadow s, vec4 c, float l) { return vec4(textureProjLod(s, c, l)); }\n");
		return b.toString();
	}

	public static String vertex(GlslRewrite.Result r, Map<String, Integer> locations, Adapter adapter) {
		StringBuilder b = new StringBuilder(r.body.length() + 8192);
		b.append(common(r));
		b.append(adapter.declarations(ShaderCompiler.Kind.VERTEX));
		b.append("vec3 iris_in_Position = vec3(0.0);\nvec4 iris_in_Color = vec4(1.0);\nvec2 iris_in_UV0 = vec2(0.0);\nivec2 iris_in_UV1 = ivec2(0, 10);\n");
		b.append("ivec2 iris_in_UV2 = ivec2(240, 240);\nvec3 iris_in_Normal = vec3(0.0, 1.0, 0.0);\nvec4 iris_in_Entity = vec4(-1.0, 0.0, 0.0, 0.0);\n");
		b.append("vec4 iris_in_MidTex = vec4(0.0, 0.0, 0.0, 1.0);\nvec4 iris_in_Tangent = vec4(1.0, 0.0, 0.0, 1.0);\nvec4 iris_in_MidBlock = vec4(0.0);\n");
		b.append("vec4 iris_in_Velocity = vec4(0.0);\n");
		b.append("vec4 iris_Vertex;\nvec4 iris_Color;\nvec3 iris_Normal;\nvec4 iris_MultiTexCoord0;\nvec4 iris_MultiTexCoord1;\nvec4 iris_MultiTexCoord2;\nvec4 iris_MultiTexCoord3;\n");
		for (var a : r.attributes.entrySet()) b.append(a.getValue()).append(' ').append(a.getKey()).append(";\n");
		Map<String, String> inPlace = new LinkedHashMap<>();
		for (GlslRewrite.Varying v : r.outputs) {
			String decl = v.declaration("out", locations.get(v.name()));
			if (v.name().startsWith("iris_")) b.append(decl);
			else inPlace.put(v.name(), decl);
		}
		b.append(adapter.matrices(ShaderCompiler.Kind.VERTEX));
		b.append(adapter.vertex());
		b.append(derive(r.builtins));
		b.append(helpers(r.builtins));
		if (r.builtins.contains("iris_ftransform")) b.append("vec4 iris_ftransform() { return iris_MVP * iris_Vertex; }\n");
		b.append("#line 1\n");
		b.append(substitute(r.body, inPlace));
		b.append("\nvoid main() {\n\tiris_matrices();\n\tiris_attributes();\n");
		b.append("\tiris_Vertex = vec4(iris_in_Position, 1.0);\n\tiris_Color = iris_in_Color;\n\tiris_Normal = iris_in_Normal;\n");
		b.append("\tiris_MultiTexCoord0 = vec4(iris_in_UV0, 0.0, 1.0);\n\tiris_MultiTexCoord1 = vec4(iris_in_UV2, 0.0, 1.0);\n");
		b.append("\tiris_MultiTexCoord2 = vec4(iris_in_UV2, 0.0, 1.0);\n\tiris_MultiTexCoord3 = vec4(iris_in_UV1, 0.0, 1.0);\n");
		for (var a : r.attributes.entrySet()) {
			String src = CANONICAL.getOrDefault(a.getKey(), "vec4(0.0)");
			b.append('\t').append(a.getKey()).append(" = ").append(convert(a.getValue(), src)).append(";\n");
		}
		b.append("\tiris_derive();\n\tiris_main();\n\tgl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n");
		b.append("\tif (iris_Hand) gl_Position.z *= 0.125;\n}\n");
		return b.toString();
	}

	private static String substitute(String body, Map<String, String> declarations) {
		if (body.indexOf(GlslRewrite.MARK) < 0) return body;
		StringBuilder b = new StringBuilder(body.length() + 1024);
		int i = 0;
		while (true) {
			int a = body.indexOf(GlslRewrite.MARK, i);
			if (a < 0) break;
			int z = body.indexOf(GlslRewrite.MARK, a + 1);
			b.append(body, i, a);
			String decl = declarations.get(body.substring(a + 1, z));
			if (decl != null) b.append(decl.substring(0, decl.length() - 1));
			i = z + 1;
		}
		return b.append(body, i, body.length()).toString();
	}

	private static String convert(String type, String vec4) {
		return switch (type) {
			case "float", "int", "uint", "bool" -> type + "(" + vec4 + ".x)";
			case "vec2", "ivec2", "uvec2", "bvec2" -> type + "(" + vec4 + ".xy)";
			case "vec3", "ivec3", "uvec3", "bvec3" -> type + "(" + vec4 + ".xyz)";
			default -> type + "(" + vec4 + ")";
		};
	}

	public static String fragment(GlslRewrite.Result r, Map<String, Integer> locations, Adapter adapter, List<Integer> drawBuffers) {
		StringBuilder b = new StringBuilder(r.body.length() + 8192);
		b.append(common(r));
		b.append(adapter.declarations(ShaderCompiler.Kind.FRAGMENT));
		Map<String, String> inPlace = new LinkedHashMap<>();
		for (GlslRewrite.Varying v : r.inputs) {
			Integer location = locations.get(v.name());
			String decl = location != null ? v.declaration("in", location) : v.type() + " " + v.name() + (v.array() > 0 ? "[" + v.array() + "]" : "") + ";\n";
			if (v.name().startsWith("iris_")) b.append(decl);
			else inPlace.put(v.name(), decl);
		}
		int outputs = 0;
		for (GlslRewrite.FragOutput o : r.fragOutputs) {
			int mapped = adapter.output(o.location());
			if (mapped < 0) b.append(o.type()).append(' ').append(o.name()).append(";\n");
			else b.append("layout(location = ").append(mapped).append(") out ").append(o.type()).append(' ').append(o.name()).append(";\n");
			outputs = Math.max(outputs, o.location() + 1);
		}
		boolean dynamic = r.builtins.contains("iris_FragDataArray");
		if (dynamic) {
			int count = Math.max(outputs, drawBuffers.size());
			for (int i = 0; i < count; i++) {
				final int loc = i;
				if (r.fragOutputs.stream().noneMatch(o -> o.location() == loc)) {
					int mapped = adapter.output(i);
					if (mapped < 0) b.append("vec4 iris_FragData").append(i).append(";\n");
					else b.append("layout(location = ").append(mapped).append(") out vec4 iris_FragData").append(i).append(";\n");
				}
			}
			b.append("vec4 iris_FragData[").append(count).append("];\n");
		}
		b.append(adapter.matrices(ShaderCompiler.Kind.FRAGMENT));
		b.append(derive(r.builtins));
		b.append(helpers(r.builtins));
		b.append("#line 1\n");
		b.append(substitute(r.body, inPlace));
		b.append("\nvoid main() {\n\tiris_matrices();\n\tiris_derive();\n");
		if (dynamic) b.append("\tfor (int i = 0; i < iris_FragData.length(); i++) iris_FragData[i] = vec4(0.0);\n");
		b.append("\tiris_main();\n");
		if (dynamic) {
			int count = Math.max(outputs, drawBuffers.size());
			for (int i = 0; i < count; i++) b.append("\tiris_FragData").append(i).append(" = iris_FragData[").append(i).append("];\n");
		}
		String alpha = adapter.alphaTest();
		if (!alpha.isEmpty() && !r.fragOutputs.isEmpty()) {
			String first = r.fragOutputs.stream().filter(o -> o.location() == 0).map(GlslRewrite.FragOutput::name).findFirst().orElse(r.fragOutputs.getFirst().name());
			String type = r.fragOutputs.stream().filter(o -> o.name().equals(first)).findFirst().get().type();
			if (type.equals("vec4")) b.append("\tif (!(").append(first).append(".a ").append(alpha).append(")) discard;\n");
		}
		b.append("}\n");
		return b.toString();
	}

	public static String compute(GlslRewrite.Result r) {
		StringBuilder b = new StringBuilder(r.body.length() + 2048);
		b.append(common(r));
		b.append(r.localSize.isEmpty() ? "layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;\n" : r.localSize + " in;\n");
		b.append(helpers(r.builtins));
		b.append("void iris_matrices() {}\n").append(derive(r.builtins));
		b.append("#line 1\n").append(r.body);
		b.append("\nvoid main() {\n\tiris_derive();\n\tiris_main();\n}\n");
		return b.toString();
	}
}
