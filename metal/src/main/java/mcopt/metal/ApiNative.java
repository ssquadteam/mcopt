package mcopt.metal;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

final class ApiNative {
	private static final Linker LINKER = Linker.nativeLinker();

	private static final MethodHandle DEVICE = fn("mc_api_device", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle QUEUE = fn("mc_api_queue", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle RENDER_ENCODER = fn("mc_api_render_encoder", true, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle VIEWPORT = fn("mc_api_viewport", true, null, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
	private static final MethodHandle TEXTURE_NEW = fn("mc_api_texture_new", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle SAMPLER_NEW = fn("mc_api_sampler_new", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
		JAVA_FLOAT, JAVA_INT);
	private static final MethodHandle MIPMAPS = fn("mc_api_mipmaps", false, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle TEXTURE_WRITE = fn("mc_api_texture_write", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle COMPUTE_NEW = fn("mc_api_compute_new", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle COMPUTE_BEGIN = fn("mc_api_compute_begin", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle COMPUTE_END = fn("mc_api_compute_end", false, null, JAVA_LONG);
	private static final MethodHandle C_PIPELINE = fn("mc_api_c_pipeline", true, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle C_BUFFER = fn("mc_api_c_buffer", true, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle C_BYTES = fn("mc_api_c_bytes", true, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle C_TEXTURE = fn("mc_api_c_texture", true, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle C_DISPATCH = fn("mc_api_c_dispatch", true, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle C_DISPATCH_INDIRECT = fn("mc_api_c_dispatch_indirect", true, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle R_STAGE_TEXTURE = fn("mc_api_r_stage_texture", true, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_STAGE_BUFFER = fn("mc_api_r_stage_buffer", true, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_STAGE_BYTES = fn("mc_api_r_stage_bytes", true, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);

	private ApiNative() {
	}

	private static MethodHandle fn(String name, boolean critical, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		var symbol = Native.lookup().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return critical ? LINKER.downcallHandle(symbol, d, Linker.Option.critical(false)) : LINKER.downcallHandle(symbol, d);
	}

	static long device(long ctx) { try { return (long) DEVICE.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); } }
	static long queue(long ctx) { try { return (long) QUEUE.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); } }
	static long renderEncoder(long enc) { try { return (long) RENDER_ENCODER.invokeExact(enc); } catch (Throwable t) { throw rethrow(t); } }
	static void viewport(long enc, float x, float y, float w, float h) { try { VIEWPORT.invokeExact(enc, x, y, w, h); } catch (Throwable t) { throw rethrow(t); } }
	static long textureNew(long ctx, int type, int format, int w, int h, int d, int mips, int usage) { try { return (long) TEXTURE_NEW.invokeExact(ctx, type, format, w, h, d, mips, usage); } catch (Throwable t) { throw rethrow(t); } }
	static long samplerNew(long ctx, int u, int v, int w, int min, int mag, int mip, int aniso, float maxLod, int compare) { try { return (long) SAMPLER_NEW.invokeExact(ctx, u, v, w, min, mag, mip, aniso, maxLod, compare); } catch (Throwable t) { throw rethrow(t); } }
	static void mipmaps(long enc, long texture) { try { MIPMAPS.invokeExact(enc, texture); } catch (Throwable t) { throw rethrow(t); } }
	static void textureWrite(long enc, long texture, long data, int bytesPerRow, int bytesPerImage) { try { TEXTURE_WRITE.invokeExact(enc, texture, data, bytesPerRow, bytesPerImage); } catch (Throwable t) { throw rethrow(t); } }
	static long computeNew(long ctx, long library, long name, long err, int errCap) { try { return (long) COMPUTE_NEW.invokeExact(ctx, library, name, err, errCap); } catch (Throwable t) { throw rethrow(t); } }
	static long computeBegin(long enc) { try { return (long) COMPUTE_BEGIN.invokeExact(enc); } catch (Throwable t) { throw rethrow(t); } }
	static void computeEnd(long c) { try { COMPUTE_END.invokeExact(c); } catch (Throwable t) { throw rethrow(t); } }
	static void cPipeline(long c, long pso) { try { C_PIPELINE.invokeExact(c, pso); } catch (Throwable t) { throw rethrow(t); } }
	static void cBuffer(long c, int index, long buffer, long offset) { try { C_BUFFER.invokeExact(c, index, buffer, offset); } catch (Throwable t) { throw rethrow(t); } }
	static void cBytes(long c, int index, long bytes, int length) { try { C_BYTES.invokeExact(c, index, bytes, length); } catch (Throwable t) { throw rethrow(t); } }
	static void cTexture(long c, int index, long texture, long sampler) { try { C_TEXTURE.invokeExact(c, index, texture, sampler); } catch (Throwable t) { throw rethrow(t); } }
	static void cDispatch(long c, int x, int y, int z, int lx, int ly, int lz) { try { C_DISPATCH.invokeExact(c, x, y, z, lx, ly, lz); } catch (Throwable t) { throw rethrow(t); } }
	static void cDispatchIndirect(long c, long buffer, long offset, int lx, int ly, int lz) { try { C_DISPATCH_INDIRECT.invokeExact(c, buffer, offset, lx, ly, lz); } catch (Throwable t) { throw rethrow(t); } }
	static void stageTexture(long enc, int stages, int index, long texture, long sampler) { try { R_STAGE_TEXTURE.invokeExact(enc, stages, index, texture, sampler); } catch (Throwable t) { throw rethrow(t); } }
	static void stageBuffer(long enc, int stages, int index, long buffer, long offset) { try { R_STAGE_BUFFER.invokeExact(enc, stages, index, buffer, offset); } catch (Throwable t) { throw rethrow(t); } }
	static void stageBytes(long enc, int stages, int index, long bytes, int length) { try { R_STAGE_BYTES.invokeExact(enc, stages, index, bytes, length); } catch (Throwable t) { throw rethrow(t); } }

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}
}
