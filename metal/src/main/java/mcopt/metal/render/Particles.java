package mcopt.metal.render;

import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.caffeinemc.mods.sodium.api.vertex.format.common.ParticleVertex;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mcopt.metal.mixin.render.ParticleStorageAccess;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Particle extraction and vertex building, each behind its own flag (all exact except where noted):
 * <ul>
 * <li>-Dmcopt.render.particleCull: a particle whose bounding box lies only in sections Sodium found hidden this frame is not
 * extracted. Answers are cached per section for the frame; boxes outside the world's height always pass.</li>
 * <li>-Dmcopt.render.particleLight: the light at a block is looked up once per frame however many particles sit in it.</li>
 * <li>-Dmcopt.render.particleExtract: the camera-facing rotation reuses one quaternion instead of allocating one per particle.</li>
 * <li>-Dmcopt.render.particleWrite: a layer's quads go into the vertex buffer in large pushes, and a quad's corner axes are
 * only rotated when its rotation differs from the previous quad's (camera-facing particles all share one).</li>
 * </ul>
 */
public final class Particles {
	private static Thread renderThread;
	private static int frame;
	private static int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;

	private Particles() {
	}

	/** Render thread, before the particle groups are extracted. */
	public static void beginFrame() {
		renderThread = Thread.currentThread();
		frame++;
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null) {
			minY = level.getMinY();
			maxY = level.getMaxY() + 1;
		}
	}

	private static boolean onRenderThread() {
		return Thread.currentThread() == renderThread;
	}

	// Section visibility, direct-mapped per frame.
	private static final int VIS_SIZE = 256;
	private static final long[] visKey = new long[VIS_SIZE];
	private static final int[] visFrame = new int[VIS_SIZE];
	private static final boolean[] visValue = new boolean[VIS_SIZE];

	public static boolean visible(SingleQuadParticle particle) {
		SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
		if (renderer == null || !onRenderThread()) return true;
		AABB b = particle.getBoundingBox();
		if (b.minY < minY || b.maxY >= maxY) return true;
		int sx = Mth.floor(b.minX) >> 4, sy = Mth.floor(b.minY) >> 4, sz = Mth.floor(b.minZ) >> 4;
		if (sx != Mth.floor(b.maxX) >> 4 || sy != Mth.floor(b.maxY) >> 4 || sz != Mth.floor(b.maxZ) >> 4) {
			return renderer.isBoxVisible(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
		}
		long key = (long) (sx & 0x3FFFFF) << 42 | (long) (sy & 0xFFFFF) << 22 | (sz & 0x3FFFFF);
		int slot = (int) (key ^ key >>> 21 ^ key >>> 42) & VIS_SIZE - 1;
		if (visFrame[slot] == frame && visKey[slot] == key) return visValue[slot];
		boolean v = renderer.isBoxVisible(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
		visKey[slot] = key;
		visFrame[slot] = frame;
		visValue[slot] = v;
		return v;
	}

	// Light at a block, direct-mapped per frame.
	private static final int LIGHT_SIZE = 1024;
	private static final long[] lightKey = new long[LIGHT_SIZE];
	private static final int[] lightFrame = new int[LIGHT_SIZE];
	private static final int[] lightValue = new int[LIGHT_SIZE];
	private static final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
	private static ClientLevel lightLevel;

	public static int light(ClientLevel level, double x, double y, double z) {
		int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
		if (!onRenderThread()) return lookup(level, new BlockPos(bx, by, bz));
		if (level != lightLevel) {
			lightLevel = level;
			frame++;
		}
		long key = BlockPos.asLong(bx, by, bz);
		int slot = (int) (key * 0x9E3779B97F4A7C15L >>> 54) & LIGHT_SIZE - 1;
		if (lightFrame[slot] == frame && lightKey[slot] == key) return lightValue[slot];
		int v = lookup(level, lightPos.set(bx, by, bz));
		lightKey[slot] = key;
		lightFrame[slot] = frame;
		lightValue[slot] = v;
		return v;
	}

	private static int lookup(ClientLevel level, BlockPos pos) {
		return level.hasChunkAt(pos) ? LightCoordsUtil.getLightCoords(level, pos) : 15728640;
	}

	private static final Quaternionf ROTATION = new Quaternionf();

	/** The quaternion SingleQuadParticle.extract fills with the camera-facing rotation; consumed before the next particle. */
	public static Quaternionf rotation() {
		return onRenderThread() ? ROTATION.identity() : new Quaternionf();
	}

	// Batched vertex writes.
	private static final int CHUNK = 2048; // particles per push
	private static final int QUAD_BYTES = 4 * 28;
	private static long scratch;
	private static final MethodHandle TRY_OF = writerMethod("tryOf", 1);
	private static final MethodHandle PUSH = writerMethod("push", 4);

	/** Sodium 0.9.3 prefixed its writer methods with sodium$, so either name is accepted. */
	private static MethodHandle writerMethod(String name, int params) {
		for (Method m : VertexBufferWriter.class.getMethods()) {
			if ((m.getName().equals(name) || m.getName().equals("sodium$" + name)) && m.getParameterCount() == params) {
				try {
					return MethodHandles.publicLookup().unreflect(m);
				} catch (IllegalAccessException ignored) {
				}
			}
		}
		return null;
	}

	/** Writes the layer's quads; false when the builder can't take raw vertices (the caller then builds them as usual). */
	public static boolean write(ParticleStorageAccess storage, VertexConsumer builder) {
		int count = storage.mcopt$count();
		if (count == 0) return true;
		if (TRY_OF == null || PUSH == null || !onRenderThread()) return false;
		VertexBufferWriter writer;
		try {
			writer = (VertexBufferWriter) TRY_OF.invoke(builder);
		} catch (Throwable t) {
			return false;
		}
		if (writer == null) return false;
		if (scratch == 0) scratch = MemoryUtil.nmemAllocChecked((long) CHUNK * QUAD_BYTES);
		float[] f = storage.mcopt$floats();
		int[] in = storage.mcopt$ints();
		float qx = Float.NaN, qy = 0, qz = 0, qw = 0;
		float rx = 0, ry = 0, rz = 0, ux = 0, uy = 0, uz = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (int start = 0; start < count; start += CHUNK) {
				int end = Math.min(count, start + CHUNK);
				long ptr = scratch;
				for (int i = start; i < end; i++) {
					int o = i * 12;
					float x = f[o], y = f[o + 1], z = f[o + 2];
					float ax = f[o + 3], ay = f[o + 4], az = f[o + 5], aw = f[o + 6];
					if (ax != qx || ay != qy || az != qz || aw != qw) {
						qx = ax;
						qy = ay;
						qz = az;
						qw = aw;
						// The rotated X and Y axes, as Vector3f.rotate computes them (normalised by the quaternion's length).
						float xx = ax * ax, yy = ay * ay, zz = az * az, ww = aw * aw;
						float xy = ax * ay, xz = ax * az, yz = ay * az, xw = ax * aw, yw = ay * aw, zw = az * aw;
						float k = 1.0F / (xx + yy + zz + ww);
						rx = (xx - yy - zz + ww) * k;
						ry = 2 * (xy + zw) * k;
						rz = 2 * (xz - yw) * k;
						ux = 2 * (xy - zw) * k;
						uy = (yy - xx - zz + ww) * k;
						uz = 2 * (yz + xw) * k;
					}
					float s = f[o + 7];
					float u0 = f[o + 8], u1 = f[o + 9], v0 = f[o + 10], v1 = f[o + 11];
					int color = ColorARGB.toABGR(in[i * 2]), light = in[i * 2 + 1];
					float sxr = rx * s, syr = ry * s, szr = rz * s, sxu = ux * s, syu = uy * s, szu = uz * s;
					ParticleVertex.put(ptr, x + sxr - sxu, y + syr - syu, z + szr - szu, u1, v1, color, light);
					ParticleVertex.put(ptr + 28, x + sxr + sxu, y + syr + syu, z + szr + szu, u1, v0, color, light);
					ParticleVertex.put(ptr + 56, x - sxr + sxu, y - syr + syu, z - szr + szu, u0, v0, color, light);
					ParticleVertex.put(ptr + 84, x - sxr - sxu, y - syr - syu, z - szr - szu, u0, v1, color, light);
					ptr += QUAD_BYTES;
				}
				try {
					PUSH.invoke(writer, stack, scratch, (end - start) * 4, ParticleVertex.FORMAT);
				} catch (Throwable t) {
					throw new IllegalStateException(t);
				}
			}
		}
		return true;
	}
}
