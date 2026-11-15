package mcopt.metal.iris;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import mcopt.metal.MetalApi;
import net.irisshaders.iris.backend.ComputePipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.samplers.ProgramSamplers;
import net.irisshaders.iris.shaderpack.FilledIndirectPointer;
import net.irisshaders.iris.uniforms.buffer.DrawUniforms;
import org.joml.Vector3i;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

final class MetalComputePipeline implements ComputePipeline {
	private record Compiled(MetalProgram program, long pso) {
	}

	private final ProgramSamplers resources;
	private final DrawUniforms uniforms;
	private final CompletableFuture<Compiled> compiled;
	private boolean closed;

	MetalComputePipeline(MetalRenderBackend backend, String name, PatchedShader source, ProgramSamplers resources, DrawUniforms uniforms) {
		this.resources = resources;
		this.uniforms = uniforms;
		uniforms.resolveLayout(source.layout());
		var constexpr = MetalGraphicsShader.constexprSamplers(source.layout(), resources);
		this.compiled = backend.track(CompletableFuture.supplyAsync(() -> {
			MetalProgram program = MetalProgram.compile(name, source, constexpr);
			var stage = program.stage(PatchShaderType.COMPUTE);
			try {
				return new Compiled(program, MetalApi.computePipeline(stage.library(), stage.entry()));
			} catch (RuntimeException | Error e) {
				program.close();
				throw e;
			}
		}, IrisMetal.COMPILER));
	}

	@Override
	public Vector3i localSize() {
		int[] size = IrisMetal.await(this.compiled).program().localSize;
		return new Vector3i(size[0], size[1], size[2]);
	}

	@Override
	public void dispatch(Vector3i groups, FilledIndirectPointer indirect) {
		Compiled c = IrisMetal.await(this.compiled);
		int[] local = c.program().localSize;
		var pack = (MetalPackResources) this.resources.getPackSamplers().getResources();
		try (MetalApi.Compute compute = MetalApi.compute()) {
			compute.pipeline(c.pso());
			c.program().bind(new MetalProgram.Binder() {
				@Override
				public void buffer(int index, GpuBufferSlice slice) {
					compute.buffer(index, slice);
				}

				@Override
				public void texture(int index, long texture, long sampler) {
					compute.texture(index, texture, sampler);
				}

				@Override
				public void bytes(int index, ByteBuffer data) {
					compute.bytes(index, data);
				}
			}, this.resources, this.uniforms, pack);
			ByteBuffer push = this.uniforms.pushConstants();
			if (push.hasRemaining()) compute.bytes(MetalApi.PUSH_CONSTANTS_INDEX, push);
			if (indirect == null) {
				if (groups.x > 0 && groups.y > 0 && groups.z > 0) compute.dispatch(groups.x, groups.y, groups.z, local[0], local[1], local[2]);
			} else {
				compute.dispatchIndirect(indirect.buffer().slice(indirect.offset(), 12), local[0], local[1], local[2]);
			}
		}
	}

	@Override
	public void close() {
		if (this.closed) return;
		this.closed = true;
		this.compiled.handle((c, failure) -> {
			if (c != null) {
				MetalApi.releaseLater(c.pso());
				c.program().close();
			}
			return null;
		});
	}
}
