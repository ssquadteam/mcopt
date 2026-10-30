package mcopt.metal.shaderpack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcMslConstexprSampler;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;

public final class ShaderCompiler {
	public enum Kind {
		VERTEX, FRAGMENT, COMPUTE
	}

	public static final class CompileException extends RuntimeException {
		public CompileException(String message) {
			super(message);
		}
	}

	public record Sampler(boolean linear, boolean mipmaps, boolean repeat, boolean compare) {
		public static final Sampler NEAREST = new Sampler(false, false, false, false);
		public static final Sampler LINEAR = new Sampler(true, false, false, false);
	}

	public interface Bindings {
		int[] fixed(int set, int binding);

		@Nullable Sampler sampler(String name, boolean depth, int dimension);

		int dynamicSampler(String name);

		int textureSlot(String name);
	}

	public record Member(String name, int base, int vector, int columns, int array, int offset, int arrayStride, int matrixStride) {
	}

	public record Texture(String name, int slot, int sampler, int dimension, boolean depth, boolean compare, boolean storage, int sampledType) {
	}

	public record Reflection(List<Member> uniforms, int uniformSize, List<Texture> textures, Map<Integer, String> outputs, List<String> buffers) {
	}

	public record Msl(String source, String entry, Reflection reflection) {
	}

	public static final int PACK_UNIFORMS = 24;
	public static final int PACK_SET = 0;
	public static final int FIXED_SET = 1;
	public static final int TEXTURE_BASE = 0;

	private static final ThreadLocal<long[]> COMPILER = ThreadLocal.withInitial(() -> new long[] {shaderc_compiler_initialize()});

	private ShaderCompiler() {
	}

	private static int shadercKind(Kind kind) {
		return switch (kind) {
			case VERTEX -> shaderc_vertex_shader;
			case FRAGMENT -> shaderc_fragment_shader;
			case COMPUTE -> shaderc_compute_shader;
		};
	}

	private static long options(Map<String, String> macros) {
		long o = shaderc_compile_options_initialize();
		shaderc_compile_options_set_target_env(o, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
		shaderc_compile_options_set_target_spirv(o, shaderc_spirv_version_1_5);
		shaderc_compile_options_set_source_language(o, shaderc_source_language_glsl);
		shaderc_compile_options_set_vulkan_rules_relaxed(o, true);
		shaderc_compile_options_set_auto_map_locations(o, true);
		shaderc_compile_options_set_auto_bind_uniforms(o, true);
		shaderc_compile_options_set_auto_combined_image_sampler(o, true);
		shaderc_compile_options_set_optimization_level(o, shaderc_optimization_level_zero);
		shaderc_compile_options_set_suppress_warnings(o);
		for (var e : macros.entrySet()) shaderc_compile_options_add_macro_definition(o, e.getKey(), e.getValue());
		return o;
	}

	public static String preprocess(String source, Kind kind, String name, Map<String, String> macros) {
		long compiler = COMPILER.get()[0];
		long o = options(macros);
		try {
			ByteBuffer src = MemoryUtil.memUTF8(source, false), file = MemoryUtil.memUTF8(name), entry = MemoryUtil.memUTF8("main");
			long result = shaderc_compile_into_preprocessed_text(compiler, src, shadercKind(kind), file, entry, o);
			MemoryUtil.memFree(src);
			MemoryUtil.memFree(file);
			MemoryUtil.memFree(entry);
			try {
				if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
					throw new CompileException(name + ": " + shaderc_result_get_error_message(result));
				}
				ByteBuffer bytes = shaderc_result_get_bytes(result);
				return bytes == null ? "" : MemoryUtil.memUTF8(bytes);
			} finally {
				shaderc_result_release(result);
			}
		} finally {
			shaderc_compile_options_release(o);
		}
	}

	public static int[] spirv(String source, Kind kind, String name) {
		long compiler = COMPILER.get()[0];
		long o = options(Map.of());
		try {
			ByteBuffer src = MemoryUtil.memUTF8(source, false), file = MemoryUtil.memUTF8(name), entry = MemoryUtil.memUTF8("main");
			long result = shaderc_compile_into_spv(compiler, src, shadercKind(kind), file, entry, o);
			MemoryUtil.memFree(src);
			MemoryUtil.memFree(file);
			MemoryUtil.memFree(entry);
			try {
				if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
					throw new CompileException(name + ": " + firstErrors(shaderc_result_get_error_message(result)));
				}
				ByteBuffer bytes = shaderc_result_get_bytes(result);
				IntBuffer words = bytes.asIntBuffer();
				int[] out = new int[words.remaining()];
				words.get(out);
				return out;
			} finally {
				shaderc_result_release(result);
			}
		} finally {
			shaderc_compile_options_release(o);
		}
	}

	private static String firstErrors(@Nullable String message) {
		if (message == null) return "unknown error";
		String[] lines = message.split("\n");
		StringBuilder b = new StringBuilder();
		int count = 0;
		for (String l : lines) {
			if (!l.contains("error")) continue;
			if (count++ >= 6) break;
			b.append(b.isEmpty() ? "" : "\n").append(l);
		}
		return b.isEmpty() ? message : b.toString();
	}

	public static Msl msl(int[] spirv, Kind kind, Bindings bindings) {
		int model = switch (kind) {
			case VERTEX -> Spv.SpvExecutionModelVertex;
			case FRAGMENT -> Spv.SpvExecutionModelFragment;
			case COMPUTE -> Spv.SpvExecutionModelGLCompute;
		};
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(0, spvc_context_create(out), "context");
			long context = out.get(0);
			IntBuffer code = MemoryUtil.memAllocInt(spirv.length);
			try {
				code.put(spirv).flip();
				check(context, spvc_context_parse_spirv(context, code, spirv.length, out), "parse");
				long ir = out.get(0);
				check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "compiler");
				long compiler = out.get(0);
				check(context, spvc_compiler_create_compiler_options(compiler, out), "options");
				long options = out.get(0);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, 30000);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, false);
				if (kind == Kind.VERTEX) spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
				check(context, spvc_compiler_install_compiler_options(compiler, options), "install options");
				Reflection reflection = reflect(stack, context, compiler, model, bindings);
				check(context, spvc_compiler_compile(compiler, out), "compile");
				String msl = MemoryUtil.memUTF8(out.get(0));
				return new Msl(msl, spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model), reflection);
			} finally {
				MemoryUtil.memFree(code);
				spvc_context_destroy(context);
			}
		}
	}

	private static Reflection reflect(MemoryStack stack, long context, long compiler, int model, Bindings bindings) {
		PointerBuffer out = stack.callocPointer(1);
		check(context, spvc_compiler_create_shader_resources(compiler, out), "resources");
		long resources = out.get(0);
		List<Member> members = new ArrayList<>();
		int[] uniformSize = {0};
		List<Texture> textures = new ArrayList<>();
		Map<Integer, String> outputs = new HashMap<>();
		List<String> buffers = new ArrayList<>();
		int[] nextTexture = {TEXTURE_BASE};
		int uniformSlot = model == Spv.SpvExecutionModelFragment ? PACK_UNIFORMS + 1 : PACK_UNIFORMS;
		int[] unique = {0};
		for (int type : new int[] {SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, SPVC_RESOURCE_TYPE_STORAGE_IMAGE, SPVC_RESOURCE_TYPE_STORAGE_BUFFER}) {
			forEach(stack, context, resources, type, r -> {
				if (spvc_compiler_get_decoration(compiler, r.id(), Spv.SpvDecorationDescriptorSet) != FIXED_SET) {
					spvc_compiler_set_decoration(compiler, r.id(), Spv.SpvDecorationBinding, unique[0]++);
				}
			});
		}
		forEach(stack, context, resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, r -> {
			int set = spvc_compiler_get_decoration(compiler, r.id(), Spv.SpvDecorationDescriptorSet);
			int binding = spvc_compiler_get_decoration(compiler, r.id(), Spv.SpvDecorationBinding);
			if (set == FIXED_SET) {
				bind(stack, compiler, model, set, binding, bindings.fixed(set, binding));
				return;
			}
			String name = r.nameString();
			if (!name.contains("gl_DefaultUniformBlock") && !spvc_compiler_get_name(compiler, r.base_type_id()).contains("gl_DefaultUniformBlock")) {
				buffers.add(name);
				return;
			}
			bind(stack, compiler, model, set, binding, new int[] {uniformSlot, 0, 0});
			long type = spvc_compiler_get_type_handle(compiler, r.base_type_id());
			PointerBuffer size = stack.callocPointer(1);
			spvc_compiler_get_declared_struct_size(compiler, type, size);
			uniformSize[0] = (int) size.get(0);
			int count = spvc_type_get_num_member_types(type);
			for (int m = 0; m < count; m++) {
				int memberTypeId = spvc_type_get_member_type(type, m);
				long mt = spvc_compiler_get_type_handle(compiler, memberTypeId);
				int dims = spvc_type_get_num_array_dimensions(mt);
				int array = dims > 0 ? spvc_type_get_array_dimension(mt, 0) : 0;
				int offset = spvc_compiler_get_member_decoration(compiler, r.base_type_id(), m, Spv.SpvDecorationOffset);
				int arrayStride = dims > 0 ? spvc_compiler_get_decoration(compiler, memberTypeId, Spv.SpvDecorationArrayStride) : 0;
				int matrixStride = spvc_compiler_has_member_decoration(compiler, r.base_type_id(), m, Spv.SpvDecorationMatrixStride)
					? spvc_compiler_get_member_decoration(compiler, r.base_type_id(), m, Spv.SpvDecorationMatrixStride) : 0;
				members.add(new Member(spvc_compiler_get_member_name(compiler, r.base_type_id(), m), spvc_type_get_basetype(mt), spvc_type_get_vector_size(mt),
					spvc_type_get_columns(mt), array, offset, arrayStride, matrixStride));
			}
		});
		forEach(stack, context, resources, SPVC_RESOURCE_TYPE_PUSH_CONSTANT, r -> bind(stack, compiler, model, SPVC_MSL_PUSH_CONSTANT_DESC_SET,
			SPVC_MSL_PUSH_CONSTANT_BINDING, bindings.fixed(SPVC_MSL_PUSH_CONSTANT_DESC_SET, SPVC_MSL_PUSH_CONSTANT_BINDING)));
		forEach(stack, context, resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, r -> {
			int set = spvc_compiler_get_decoration(compiler, r.id(), Spv.SpvDecorationDescriptorSet);
			int binding = spvc_compiler_get_decoration(compiler, r.id(), Spv.SpvDecorationBinding);
			if (set == FIXED_SET) {
				bind(stack, compiler, model, set, binding, bindings.fixed(set, binding));
				SpvcMslConstexprSampler c = SpvcMslConstexprSampler.calloc(stack);
				spvc_msl_constexpr_sampler_init(c);
				check(context, spvc_compiler_msl_remap_constexpr_sampler_by_binding(compiler, set, binding, c), "constexpr sampler");
				return;
			}
			long type = spvc_compiler_get_type_handle(compiler, r.type_id());
			long base = spvc_compiler_get_type_handle(compiler, r.base_type_id());
			int dim = spvc_type_get_image_dimension(base);
			boolean depth = spvc_type_get_image_is_depth(base);
			String name = r.nameString();
			int slot = bindings.textureSlot(name);
			nextTexture[0] = Math.max(nextTexture[0], slot + 1);
			Sampler s = bindings.sampler(name, depth, dim);
			int samplerSlot = -1;
			if (s == null) {
				samplerSlot = bindings.dynamicSampler(name);
				bind(stack, compiler, model, set, binding, new int[] {uniformSlot, slot, samplerSlot});
			} else {
				bind(stack, compiler, model, set, binding, new int[] {uniformSlot, slot, 0});
				SpvcMslConstexprSampler c = SpvcMslConstexprSampler.calloc(stack);
				spvc_msl_constexpr_sampler_init(c);
				c.min_filter(s.linear() ? SPVC_MSL_SAMPLER_FILTER_LINEAR : SPVC_MSL_SAMPLER_FILTER_NEAREST);
				c.mag_filter(s.linear() ? SPVC_MSL_SAMPLER_FILTER_LINEAR : SPVC_MSL_SAMPLER_FILTER_NEAREST);
				c.mip_filter(s.mipmaps() ? SPVC_MSL_SAMPLER_MIP_FILTER_LINEAR : SPVC_MSL_SAMPLER_MIP_FILTER_NONE);
				int address = s.repeat() ? SPVC_MSL_SAMPLER_ADDRESS_REPEAT : SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE;
				c.s_address(address).t_address(address).r_address(address);
				if (s.compare()) c.compare_enable(true).compare_func(SPVC_MSL_SAMPLER_COMPARE_FUNC_LESS_EQUAL);
				check(context, spvc_compiler_msl_remap_constexpr_sampler_by_binding(compiler, set, binding, c), "constexpr sampler " + name);
			}
			textures.add(new Texture(name, slot, samplerSlot, dim, depth, depth && s != null && s.compare(), false, spvc_type_get_basetype(
				spvc_compiler_get_type_handle(compiler, spvc_type_get_image_sampled_type(base)))));
			if (type == 0) throw new IllegalStateException();
		});
		forEach(stack, context, resources, SPVC_RESOURCE_TYPE_STORAGE_IMAGE, r -> {
			int set = spvc_compiler_get_decoration(compiler, r.id(), Spv.SpvDecorationDescriptorSet);
			int binding = spvc_compiler_get_decoration(compiler, r.id(), Spv.SpvDecorationBinding);
			long base = spvc_compiler_get_type_handle(compiler, r.base_type_id());
			int slot = bindings.textureSlot(r.nameString());
			nextTexture[0] = Math.max(nextTexture[0], slot + 1);
			bind(stack, compiler, model, set, binding, new int[] {uniformSlot, slot, 0});
			textures.add(new Texture(r.nameString(), slot, -1, spvc_type_get_image_dimension(base), false, false, true,
				spvc_type_get_basetype(spvc_compiler_get_type_handle(compiler, spvc_type_get_image_sampled_type(base)))));
		});
		forEach(stack, context, resources, SPVC_RESOURCE_TYPE_STORAGE_BUFFER, r -> {
			int set = spvc_compiler_get_decoration(compiler, r.id(), Spv.SpvDecorationDescriptorSet);
			int binding = spvc_compiler_get_decoration(compiler, r.id(), Spv.SpvDecorationBinding);
			if (set == FIXED_SET) bind(stack, compiler, model, set, binding, bindings.fixed(set, binding));
			else buffers.add(r.nameString());
		});
		if (model == Spv.SpvExecutionModelFragment) {
			forEach(stack, context, resources, SPVC_RESOURCE_TYPE_STAGE_OUTPUT, r -> {
				if (spvc_compiler_has_decoration(compiler, r.id(), Spv.SpvDecorationLocation)) {
					outputs.put(spvc_compiler_get_decoration(compiler, r.id(), Spv.SpvDecorationLocation), r.nameString());
				}
			});
		}
		if (nextTexture[0] > 96) throw new CompileException("too many textures (" + nextTexture[0] + ")");
		return new Reflection(members, uniformSize[0], textures, outputs, buffers);
	}

	private interface ResourceVisitor {
		void visit(SpvcReflectedResource r);
	}

	private static void forEach(MemoryStack stack, long context, long resources, int type, ResourceVisitor visitor) {
		PointerBuffer list = stack.callocPointer(1), count = stack.callocPointer(1);
		check(context, spvc_resources_get_resource_list_for_type(resources, type, list, count), "resource list");
		int n = (int) count.get(0);
		if (n == 0) return;
		SpvcReflectedResource.Buffer items = SpvcReflectedResource.create(list.get(0), n);
		for (int i = 0; i < n; i++) {
			try (MemoryStack frame = stack.push()) {
				visitor.visit(items.get(i));
			}
		}
	}

	private static void bind(MemoryStack stack, long compiler, int model, int set, int binding, int[] slots) {
		SpvcMslResourceBinding b = SpvcMslResourceBinding.calloc(stack);
		spvc_msl_resource_binding_init(b);
		b.stage(model).desc_set(set).binding(binding).msl_buffer(slots[0]).msl_texture(slots[1]).msl_sampler(slots[2]);
		spvc_compiler_msl_add_resource_binding(compiler, b);
	}

	private static void check(long context, int result, String step) {
		if (result != 0) throw new CompileException("SPIRV-Cross " + step + " failed: " + (context != 0 ? spvc_context_get_last_error_string(context) : result));
	}
}
