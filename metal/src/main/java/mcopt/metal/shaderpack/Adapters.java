package mcopt.metal.shaderpack;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public final class Adapters {
	public static final int DYNAMIC_TRANSFORMS = 0, PROJECTION = 1, SODIUM_GLOBALS = 2, FRAME = 3, SODIUM_VERTICES = 6, BLOCK_IDS = 7;
	public static final int DYNAMIC_TRANSFORMS_SLOT = 20, PROJECTION_SLOT = 21, SODIUM_GLOBALS_SLOT = 22, FRAME_SLOT = 23, PUSH_CONSTANTS_SLOT = 26,
		SODIUM_VERTICES_SLOT = 27;
	public static final String FRAME_BLOCK = "layout(std140, set = 1, binding = " + FRAME
		+ ") uniform iris_FrameBlock { mat4 shadowModelView; mat4 shadowProjection; vec4 fogColor; vec4 fog; vec4 misc; } iris_frame;\n";
	private static final String TO_GL = "mat4(vec4(1.0, 0.0, 0.0, 0.0), vec4(0.0, 1.0, 0.0, 0.0), vec4(0.0, 0.0, -2.0, 0.0), vec4(0.0, 0.0, 1.0, 1.0))";
	private static final String FOG = "\tiris_Fog.color = iris_frame.fogColor;\n\tiris_Fog.density = iris_frame.fog.x;\n\tiris_Fog.start = iris_frame.fog.y;\n"
		+ "\tiris_Fog.end = iris_frame.fog.z;\n\tiris_Fog.scale = iris_frame.fog.w;\n";

	public record Attribute(String name, int location, String format) {
		public String glslType() {
			String f = this.format.toUpperCase(Locale.ROOT);
			int components = f.startsWith("RGBA") ? 4 : f.startsWith("RGB") ? 3 : f.startsWith("RG") ? 2 : 1;
			String prefix = f.endsWith("_UINT") ? "u" : f.endsWith("_SINT") ? "i" : "";
			return components == 1 ? (prefix.equals("u") ? "uint" : prefix.equals("i") ? "int" : "float") : prefix + "vec" + components;
		}
	}

	public record Layout(String name, List<Attribute> attributes, boolean quads) {
		public @Nullable Attribute find(String attribute) {
			for (Attribute a : this.attributes) if (a.name().equals(attribute)) return a;
			return null;
		}
	}

	private Adapters() {
	}

	private record Simple(String key, String declarations, String matrices, String vertex, String alphaTest, int[] outputs) implements ProgramAssembler.Adapter {
		@Override
		public int output(int location) {
			return this.outputs.length == 0 ? location : location < this.outputs.length ? this.outputs[location] : -1;
		}

		@Override
		public String declarations(ShaderCompiler.Kind stage) {
			return this.declarations;
		}

		@Override
		public String matrices(ShaderCompiler.Kind stage) {
			return this.matrices;
		}
	}

	public static ProgramAssembler.Adapter composite() {
		String matrices = "void iris_matrices() {\n\tiris_ProjMat = mat4(vec4(2.0, 0.0, 0.0, 0.0), vec4(0.0, 2.0, 0.0, 0.0), vec4(0.0, 0.0, -2.0, 0.0), vec4(-1.0, -1.0, -1.0, 1.0));\n"
			+ FOG + "}\n";
		String vertex = "void iris_attributes() {\n\tvec2 p = vec2(float(gl_VertexIndex & 1), float((gl_VertexIndex >> 1) & 1));\n\tiris_in_Position = vec3(p, 0.0);\n"
			+ "\tiris_in_UV0 = p;\n\tiris_in_Normal = vec3(0.0, 0.0, 1.0);\n}\n";
		return new Simple("composite", FRAME_BLOCK, matrices, vertex, "", new int[0]);
	}

	public static String alpha(PackProperties.@Nullable AlphaTest test, float fallback) {
		if (test == null) return fallback <= 0 ? "" : "> " + fallback;
		if (test.off()) return "";
		String ref = Float.toString(test.reference());
		return switch (test.compare()) {
			case 0 -> "&& false";
			case 1 -> "< " + ref;
			case 2 -> "== " + ref;
			case 3 -> "<= " + ref;
			case 4 -> "> " + ref;
			case 5 -> "!= " + ref;
			case 6 -> ">= " + ref;
			default -> "";
		};
	}

	public static ProgramAssembler.Adapter vanilla(Layout layout, boolean oldTransforms, boolean shadow, boolean hand, String alpha, int[] outputs) {
		StringBuilder d = new StringBuilder(FRAME_BLOCK);
		d.append("layout(std140, set = 1, binding = ").append(DYNAMIC_TRANSFORMS).append(") uniform DynamicTransforms { ")
			.append(oldTransforms ? "mat4 ModelViewMat; vec4 ColorModulator; vec3 ModelOffset; mat4 TextureMat;" : "mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset;")
			.append(" } iris_dt;\n");
		d.append("layout(std140, set = 1, binding = ").append(PROJECTION).append(") uniform Projection { mat4 ProjMat; } iris_proj;\n");
		String declarations = d.toString();
		StringBuilder m = new StringBuilder("void iris_matrices() {\n");
		if (shadow) {
			m.append("\tiris_ModelViewMat = iris_frame.shadowModelView * iris_dt.ModelViewMat;\n\tiris_ProjMat = iris_frame.shadowProjection;\n");
		} else {
			m.append("\tiris_ModelViewMat = iris_dt.ModelViewMat;\n\tiris_ProjMat = ").append(TO_GL).append(" * iris_proj.ProjMat;\n");
		}
		m.append("\tiris_TextureMat0 = iris_dt.TextureMat;\n\tiris_ColorModulator = iris_dt.ColorModulator;\n").append(FOG).append("}\n");
		StringBuilder v = new StringBuilder();
		for (Attribute a : layout.attributes()) v.append("layout(location = ").append(a.location()).append(") in ").append(a.glslType()).append(" iris_va_").append(a.name()).append(";\n");
		v.append("void iris_attributes() {\n");
		Attribute position = layout.find("Position"), color = layout.find("Color"), uv0 = layout.find("UV0"), uv1 = layout.find("UV1"), uv2 = layout.find("UV2");
		Attribute normal = layout.find("Normal");
		if (position != null) v.append("\tiris_in_Position = vec3(iris_va_Position) + iris_dt.ModelOffset;\n");
		if (color != null) v.append("\tiris_in_Color = vec4(iris_va_Color) * iris_dt.ColorModulator;\n");
		else v.append("\tiris_in_Color = iris_dt.ColorModulator;\n");
		if (uv0 != null) v.append("\tiris_in_UV0 = vec2(iris_va_UV0);\n");
		if (uv1 != null) v.append("\tiris_in_UV1 = ivec2(iris_va_UV1);\n");
		if (uv2 != null) v.append("\tiris_in_UV2 = ivec2(iris_va_UV2);\n");
		if (normal != null) v.append("\tiris_in_Normal = vec3(iris_va_Normal);\n");
		if (uv0 != null) v.append("\tiris_in_MidTex = vec4(iris_in_UV0, 0.0, 1.0);\n");
		v.append("}\n");
		if (hand) m.insert(m.length() - 2, "\tiris_Hand = true;\n");
		return new Simple("vanilla:" + layout.name() + (shadow ? ":shadow" : "") + (hand ? ":hand" : "") + ":" + alpha + ":" + java.util.Arrays.toString(outputs), declarations,
			m.toString(), v.toString(), alpha, outputs);
	}

	public static ProgramAssembler.Adapter sodium(boolean shadow, String alpha, int[] outputs) {
		StringBuilder d = new StringBuilder(FRAME_BLOCK);
		if (shadow) {
			d.append("vec2 iris_shrink() { return vec2(0.0); }\n");
		} else {
			d.append("layout(std140, set = 1, binding = ").append(SODIUM_GLOBALS).append(") uniform u_Globals { mat4 u_ProjectionMatrix; mat4 u_ModelViewMatrix; ")
				.append("vec4 u_FogColor; vec2 u_EnvironmentFog; vec2 u_RenderFog; vec2 u_TexelSize; vec2 u_TexCoordShrink; float u_FadePeriodInv; bool u_UseRGSS; } iris_sg;\n");
			d.append("vec2 iris_shrink() { return iris_sg.u_TexCoordShrink; }\n");
		}
		d.append("layout(push_constant) uniform iris_PushConstants { vec3 u_RegionOffset; int u_CurrentTime; uint u_RegionID; } iris_pc;\n");
		String declarations = d.toString();
		StringBuilder m = new StringBuilder("void iris_matrices() {\n");
		if (shadow) {
			m.append("\tiris_ModelViewMat = iris_frame.shadowModelView;\n\tiris_ProjMat = iris_frame.shadowProjection;\n");
		} else {
			m.append("\tiris_ModelViewMat = iris_sg.u_ModelViewMatrix;\n\tiris_ProjMat = ").append(TO_GL).append(" * iris_sg.u_ProjectionMatrix;\n");
		}
		m.append(FOG).append("}\n");
		StringBuilder v = new StringBuilder();
		v.append("layout(std430, set = 1, binding = ").append(SODIUM_VERTICES).append(") readonly buffer iris_SodiumVertices { uint iris_sv[]; };\n");
		v.append("layout(set = 1, binding = ").append(BLOCK_IDS).append(") uniform usampler2D iris_BlockIds;\n");
		v.append("vec3 iris_sodium_position(uint i) {\n\tuvec2 p = uvec2(iris_sv[i * 5u], iris_sv[i * 5u + 1u]);\n");
		v.append("\tuvec3 hi = (uvec3(p.x) >> uvec3(0u, 10u, 20u)) & 0x3FFu;\n\tuvec3 lo = (uvec3(p.y) >> uvec3(0u, 10u, 20u)) & 0x3FFu;\n");
		v.append("\treturn vec3((hi << 10u) | lo) * (32.0 / 1048576.0) - 8.0;\n}\n");
		v.append("vec2 iris_sodium_uv(uint i) {\n\tuint w = iris_sv[i * 5u + 3u];\n\tuvec2 t = uvec2(w & 0xFFFFu, w >> 16u);\n");
		v.append("\treturn vec2(t & 0x7FFFu) / 32768.0 + mix(vec2(-1.0), vec2(1.0), bvec2(t >> 15u)) * iris_shrink();\n}\n");
		v.append("void iris_attributes() {\n");
		v.append("\tuint vi = uint(gl_VertexIndex);\n\tuint q = uint(gl_BaseVertexARB) + ((vi - uint(gl_BaseVertexARB)) & ~3u);\n");
		v.append("\tvec3 local = iris_sodium_position(vi);\n\tuint ld = iris_sv[vi * 5u + 4u];\n");
		v.append("\tvec3 offset = iris_pc.u_RegionOffset + vec3((uvec3(ld >> 24u) >> uvec3(5u, 0u, 2u)) & uvec3(7u, 3u, 7u)) * 16.0;\n");
		v.append("\tiris_in_Position = local + offset;\n\tiris_ChunkOffset = offset;\n");
		v.append("\tiris_in_Color = unpackUnorm4x8(iris_sv[vi * 5u + 2u]);\n\tiris_in_UV0 = iris_sodium_uv(vi);\n");
		v.append("\tiris_in_UV2 = ivec2(ld & 0xFFu, (ld >> 8u) & 0xFFu);\n");
		v.append("\tvec3 p0 = iris_sodium_position(q), p1 = iris_sodium_position(q + 1u), p2 = iris_sodium_position(q + 2u), p3 = iris_sodium_position(q + 3u);\n");
		v.append("\tvec2 t0 = iris_sodium_uv(q), t1 = iris_sodium_uv(q + 1u), t2 = iris_sodium_uv(q + 2u), t3 = iris_sodium_uv(q + 3u);\n");
		v.append("\tvec3 e1 = p1 - p0, e2 = p2 - p0;\n\tvec3 n = cross(e1, e2);\n\tif (dot(n, n) < 1e-12) n = cross(p2 - p0, p3 - p0);\n");
		v.append("\tiris_in_Normal = dot(n, n) > 1e-12 ? normalize(n) : vec3(0.0, 1.0, 0.0);\n");
		v.append("\tvec2 mid = (t0 + t1 + t2 + t3) * 0.25;\n\tiris_in_MidTex = vec4(mid, 0.0, 1.0);\n");
		v.append("\tvec2 d1 = t1 - t0, d2 = t2 - t0;\n\tfloat r = d1.x * d2.y - d2.x * d1.y;\n");
		v.append("\tif (abs(r) > 1e-12) {\n\t\tvec3 t = (e1 * d2.y - e2 * d1.y) / r, b = (e2 * d1.x - e1 * d2.x) / r;\n");
		v.append("\t\tt -= iris_in_Normal * dot(iris_in_Normal, t);\n");
		v.append("\t\tif (dot(t, t) > 1e-12) iris_in_Tangent = vec4(normalize(t), dot(cross(iris_in_Normal, t), b) < 0.0 ? -1.0 : 1.0);\n\t}\n");
		v.append("\tvec3 center = (p0 + p2) * 0.5 - iris_in_Normal * 0.05;\n\tiris_in_MidBlock = vec4((floor(center) + 0.5 - local) * 64.0, 0.0);\n");
		v.append("\tivec2 size = textureSize(iris_BlockIds, 0);\n\tuint id = texelFetch(iris_BlockIds, clamp(ivec2(mid * vec2(size)), ivec2(0), size - 1), 0).r;\n");
		v.append("\tiris_in_Entity = vec4(id == 0u ? -1.0 : float(id), 1.0, 0.0, 0.0);\n}\n");
		return new Simple("sodium:" + (shadow ? "shadow" : "main") + ":" + alpha + ":" + java.util.Arrays.toString(outputs), declarations, m.toString(), v.toString(), alpha, outputs);
	}
}
