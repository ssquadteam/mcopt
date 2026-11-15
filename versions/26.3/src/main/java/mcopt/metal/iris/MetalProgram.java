package mcopt.metal.iris;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import mcopt.metal.MetalApi;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.samplers.ProgramSamplers;
import net.irisshaders.iris.samplers.SamplerHeapLayout;
import net.irisshaders.iris.uniforms.buffer.DrawUniforms;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcMslConstexprSampler;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.util.spvc.Spvc.*;

final class MetalProgram implements AutoCloseable {
	static final int BUFFER_SIZES_INDEX = 25;
	private static final int MAX_BUFFERS = BUFFER_SIZES_INDEX;
	private static final int MAX_SAMPLERS = 16;
	private static final int MAX_TEXTURES = 128;

	enum Kind { BUFFER, TEXTURE, TEXEL_BUFFER, IMAGE }

	record Slot(ShaderResourceLayout.Descriptor descriptor, Kind kind, int index, int sampler) {
	}

	record Stage(long library, String entry) {
	}

	interface Binder {
		void buffer(int index, GpuBufferSlice slice);

		void texture(int index, long texture, long sampler);

		void bytes(int index, ByteBuffer data);
	}

	private static final ThreadLocal<long[]> SHADERC = ThreadLocal.withInitial(() -> {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
		Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_5);
		Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
		return new long[] {compiler, options};
	});

	final String name;
	final PatchedShader source;
	final List<Slot> slots;
	final Map<String, GlSampler.Description> constexpr;
	private final Map<PatchShaderType, ByteBuffer> spirv = new EnumMap<>(PatchShaderType.class);
	private final Map<PatchShaderType, Stage> stages = new EnumMap<>(PatchShaderType.class);
	private @Nullable Stage pointVertex;
	boolean bufferSizes;
	int[] localSize = {1, 1, 1};
	private boolean closed;

	private MetalProgram(String name, PatchedShader source, List<Slot> slots, Map<String, GlSampler.Description> constexpr) {
		this.name = name;
		this.source = source;
		this.slots = slots;
		this.constexpr = constexpr;
	}

	static MetalProgram compile(String name, PatchedShader source, Map<String, GlSampler.Description> constexpr) {
		for (PatchShaderType stage : source.sources().keySet()) {
			if (stage == PatchShaderType.GEOMETRY || stage == PatchShaderType.TESS_CONTROL || stage == PatchShaderType.TESS_EVAL) {
				throw new IllegalArgumentException("Metal has no " + stage.name().toLowerCase() + " shaders: " + name);
			}
		}
		MetalProgram program = new MetalProgram(name, source, assign(name, source.layout(), constexpr), constexpr);
		try {
			for (var entry : source.sources().entrySet()) {
				program.spirv.put(entry.getKey(), spirv(name + entry.getKey().extension, entry.getKey(), entry.getValue()));
			}
			for (PatchShaderType stage : program.spirv.keySet()) {
				program.stages.put(stage, program.translate(stage, false));
			}
			if (!source.sources().containsKey(PatchShaderType.VERTEX)) {
				program.spirv.values().forEach(MemoryUtil::memFree);
				program.spirv.clear();
			} else {
				program.spirv.entrySet().removeIf(e -> {
					if (e.getKey() == PatchShaderType.VERTEX) return false;
					MemoryUtil.memFree(e.getValue());
					return true;
				});
			}
			return program;
		} catch (RuntimeException | Error e) {
			program.close();
			throw e;
		}
	}

	private static List<Slot> assign(String name, ShaderResourceLayout layout, Map<String, GlSampler.Description> constexpr) {
		List<Slot> slots = new ArrayList<>();
		int buffers = 0, textures = 0, samplers = 0;
		for (var d : layout.descriptors()) {
			switch (d.kind()) {
				case UNIFORM_BUFFER, STORAGE_BUFFER -> {
					slots.add(new Slot(d, Kind.BUFFER, buffers, -1));
					buffers += d.count();
				}
				case SAMPLER -> {
					boolean texel = texel(d);
					int sampler = texel || constexpr.containsKey(d.name()) ? -1 : samplers;
					slots.add(new Slot(d, texel ? Kind.TEXEL_BUFFER : Kind.TEXTURE, textures, sampler));
					textures += d.count();
					if (sampler >= 0) samplers += d.count();
				}
				case IMAGE -> {
					slots.add(new Slot(d, Kind.IMAGE, textures, -1));
					textures += d.count();
				}
			}
		}
		if (buffers > MAX_BUFFERS) throw new IllegalArgumentException(name + " needs " + buffers + " buffers; Metal has " + MAX_BUFFERS + " for it");
		if (textures > MAX_TEXTURES) throw new IllegalArgumentException(name + " needs " + textures + " textures; Metal has " + MAX_TEXTURES);
		if (samplers > MAX_SAMPLERS) throw new IllegalArgumentException(name + " needs " + samplers + " sampler states; Metal has " + MAX_SAMPLERS);
		return List.copyOf(slots);
	}

	static int samplerCount(ShaderResourceLayout layout) {
		int n = 0;
		for (var d : layout.descriptors()) {
			if (d.kind() == ShaderResourceLayout.Kind.SAMPLER && !texel(d)) n += d.count();
		}
		return n;
	}

	static boolean texel(ShaderResourceLayout.Descriptor d) {
		try {
			return SamplerHeapLayout.Type.valueOf(d.type()).texelBuffer();
		} catch (IllegalArgumentException unknown) {
			return d.type().toLowerCase(java.util.Locale.ROOT).contains("buffer");
		}
	}

	static GlSampler.Description state(GlSampler.Description d, SamplerHeapLayout.Type type) {
		boolean shadow = type.glsl.endsWith("Shadow");
		return d.hardwareShadow() == shadow ? d : new GlSampler.Description(d.linear(), d.linearMips(), d.mipmapped(), shadow, d.clamp());
	}

	static boolean needsConstexpr(ShaderResourceLayout layout) {
		return samplerCount(layout) > MAX_SAMPLERS;
	}

	private static ByteBuffer spirv(String name, PatchShaderType stage, String glsl) {
		long[] shaderc = SHADERC.get();
		int kind = switch (stage) {
			case VERTEX -> Shaderc.shaderc_vertex_shader;
			case FRAGMENT -> Shaderc.shaderc_fragment_shader;
			case COMPUTE -> Shaderc.shaderc_compute_shader;
			default -> throw new IllegalArgumentException(stage.name());
		};
		ByteBuffer src = MemoryUtil.memUTF8(glsl, false);
		ByteBuffer file = MemoryUtil.memUTF8(name);
		ByteBuffer entry = MemoryUtil.memUTF8("main");
		long result;
		try {
			result = Shaderc.shaderc_compile_into_spv(shaderc[0], src, kind, file, entry, shaderc[1]);
		} finally {
			MemoryUtil.memFree(entry);
			MemoryUtil.memFree(file);
			MemoryUtil.memFree(src);
		}
		if (result == 0) throw new IllegalStateException("shaderc returned nothing for " + name);
		try {
			if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
				throw new IllegalArgumentException("failed to compile " + name + ": " + Shaderc.shaderc_result_get_error_message(result));
			}
			ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
			return MemoryUtil.memAlloc(bytes.remaining()).put(bytes).flip();
		} finally {
			Shaderc.shaderc_result_release(result);
		}
	}

	private Stage translate(PatchShaderType stage, boolean points) {
		ByteBuffer spv = this.spirv.get(stage);
		int model = switch (stage) {
			case VERTEX -> Spv.SpvExecutionModelVertex;
			case FRAGMENT -> Spv.SpvExecutionModelFragment;
			default -> Spv.SpvExecutionModelGLCompute;
		};
		String msl, entry;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(0, spvc_context_create(out), "context");
			long context = out.get(0);
			try {
				var words = spv.asIntBuffer();
				check(context, spvc_context_parse_spirv(context, words, words.remaining(), out), "parse");
				long ir = out.get(0);
				check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "compiler");
				long compiler = out.get(0);
				check(context, spvc_compiler_create_compiler_options(compiler, out), "options");
				long options = out.get(0);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, 30000);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
				spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
				spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_BUFFER_SIZE_BUFFER_INDEX, BUFFER_SIZES_INDEX);
				if (stage == PatchShaderType.VERTEX) {
					spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
					spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, points);
				}
				check(context, spvc_compiler_install_compiler_options(compiler, options), "install options");
				for (Slot slot : this.slots) {
					var d = slot.descriptor();
					SpvcMslResourceBinding b = SpvcMslResourceBinding.calloc(stack);
					spvc_msl_resource_binding_init(b);
					b.stage(model).desc_set(d.set()).binding(d.binding());
					if (slot.kind() == Kind.BUFFER) b.msl_buffer(slot.index());
					else b.msl_texture(slot.index()).msl_sampler(Math.max(0, slot.sampler()));
					check(context, spvc_compiler_msl_add_resource_binding(compiler, b), "binding " + d.name());
					GlSampler.Description fixed = this.constexpr.get(d.name());
					if (fixed != null && slot.kind() == Kind.TEXTURE) {
						check(context, spvc_compiler_msl_remap_constexpr_sampler_by_binding(compiler, d.set(), d.binding(), constexprSampler(stack, fixed)),
							"constexpr sampler " + d.name());
					}
				}
				SpvcMslResourceBinding push = SpvcMslResourceBinding.calloc(stack);
				spvc_msl_resource_binding_init(push);
				push.stage(model).desc_set(SPVC_MSL_PUSH_CONSTANT_DESC_SET).binding(SPVC_MSL_PUSH_CONSTANT_BINDING).msl_buffer(MetalApi.PUSH_CONSTANTS_INDEX);
				check(context, spvc_compiler_msl_add_resource_binding(compiler, push), "push constants");
				check(context, spvc_compiler_compile(compiler, out), "compile");
				msl = MemoryUtil.memUTF8(out.get(0));
				entry = spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model);
				if (spvc_compiler_msl_needs_buffer_size_buffer(compiler)) this.bufferSizes = true;
				if (stage == PatchShaderType.COMPUTE) {
					for (int i = 0; i < 3; i++) {
						this.localSize[i] = Math.max(1, spvc_compiler_get_execution_mode_argument_by_index(compiler, Spv.SpvExecutionModeLocalSize, i));
					}
				}
			} finally {
				spvc_context_destroy(context);
			}
		}
		try {
			return new Stage(MetalApi.library(msl), entry);
		} catch (IllegalStateException e) {
			throw new IllegalStateException("Metal rejected " + this.name + stage.extension + ": " + e.getMessage(), e);
		}
	}

	private static SpvcMslConstexprSampler constexprSampler(MemoryStack stack, GlSampler.Description d) {
		SpvcMslConstexprSampler s = SpvcMslConstexprSampler.calloc(stack);
		spvc_msl_constexpr_sampler_init(s);
		int filter = d.linear() ? SPVC_MSL_SAMPLER_FILTER_LINEAR : SPVC_MSL_SAMPLER_FILTER_NEAREST;
		int address = d.clamp() ? SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE : SPVC_MSL_SAMPLER_ADDRESS_REPEAT;
		s.min_filter(filter).mag_filter(filter).s_address(address).t_address(address).r_address(address)
			.mip_filter(!d.mipmapped() ? SPVC_MSL_SAMPLER_MIP_FILTER_NONE : d.linearMips() ? SPVC_MSL_SAMPLER_MIP_FILTER_LINEAR : SPVC_MSL_SAMPLER_MIP_FILTER_NEAREST);
		if (d.hardwareShadow()) s.compare_enable(true).compare_func(SPVC_MSL_SAMPLER_COMPARE_FUNC_LESS_EQUAL);
		return s;
	}

	private static void check(long context, int result, String step) {
		if (result != SPVC_SUCCESS) {
			throw new IllegalStateException("SPIRV-Cross " + step + " failed: " + (context != 0 ? spvc_context_get_last_error_string(context) : result));
		}
	}

	Stage stage(PatchShaderType type) {
		Stage s = this.stages.get(type);
		if (s == null) throw new IllegalStateException(this.name + " has no " + type + " stage");
		return s;
	}

	synchronized Stage pointVertex() {
		if (this.pointVertex == null) this.pointVertex = this.translate(PatchShaderType.VERTEX, true);
		return this.pointVertex;
	}

	void bind(Binder binder, ProgramSamplers resources, DrawUniforms uniforms, MetalPackResources pack) {
		Map<String, List<GpuBufferSlice>> buffers = null;
		int[] sizes = this.bufferSizes ? new int[BUFFER_SIZES_INDEX] : null;
		for (Slot slot : this.slots) {
			var d = slot.descriptor();
			switch (slot.kind()) {
				case BUFFER -> {
					if (buffers == null) buffers = this.buffers(resources, uniforms, pack);
					List<GpuBufferSlice> slices = buffers.get(d.name());
					if (slices == null) throw new IllegalStateException("missing block " + d.name() + " for " + this.name);
					for (int i = 0; i < Math.min(d.count(), slices.size()); i++) {
						GpuBufferSlice s = slices.get(i);
						binder.buffer(slot.index() + i, s);
						if (sizes != null) sizes[slot.index() + i] = (int) Math.min(Integer.MAX_VALUE, s.length());
					}
				}
				case TEXEL_BUFFER -> {
					var sampler = resources.getSamplers().get(d.name());
					binder.texture(slot.index(), sampler.texelBuffer().getAsLong(), 0);
				}
				case TEXTURE -> {
					var sampler = resources.getSamplers().get(d.name());
					if (sampler == null) throw new IllegalStateException("missing sampler " + d.name() + " for " + this.name);
					if (sampler.texture() == null) {
						binder.texture(slot.index(), sampler.texelBuffer().getAsLong(), 0);
						continue;
					}
					var resolved = sampler.texture().resolve(sampler.reference().type());
					long texture = resolved.image() != null ? resolved.image().getView() : MetalApi.texture(resolved.texture());
					long state = slot.sampler() < 0 ? 0 : resolved.nativeSampler() != null ? MetalApi.sampler(resolved.nativeSampler()) : pack.sampler(state(resolved.sampler(), sampler.reference().type()));
					binder.texture(slot.index(), texture, state);
				}
				case IMAGE -> {
					var image = resources.getImages().get(d.name());
					long texture = image.image() != null ? image.image().get().getView() : MetalApi.texture(image.texture().get());
					binder.texture(slot.index(), texture, 0);
				}
			}
		}
		if (sizes != null) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer data = stack.malloc(sizes.length * 4);
				for (int i = 0; i < sizes.length; i++) data.putInt(i * 4, sizes[i]);
				binder.bytes(BUFFER_SIZES_INDEX, data);
			}
		}
	}

	private Map<String, List<GpuBufferSlice>> buffers(ProgramSamplers resources, DrawUniforms uniforms, MetalPackResources pack) {
		Map<String, List<GpuBufferSlice>> buffers = new HashMap<>(resources.getPackSamplers().getBufferBindings(this.source.layout()));
		buffers.putAll(uniforms.uniformBuffers());
		GpuBufferSlice frame = pack.frameUniforms();
		if (frame != null) buffers.putIfAbsent(ShaderResourceLayout.FRAME_BLOCK, List.of(frame));
		return buffers;
	}

	@Override
	public synchronized void close() {
		if (this.closed) return;
		this.closed = true;
		this.spirv.values().forEach(MemoryUtil::memFree);
		this.spirv.clear();
		for (Stage s : this.stages.values()) MetalApi.releaseLater(s.library());
		if (this.pointVertex != null) MetalApi.releaseLater(this.pointVertex.library());
		this.stages.clear();
	}
}
