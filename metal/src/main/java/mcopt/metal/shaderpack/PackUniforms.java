package mcopt.metal.shaderpack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class PackUniforms {
	public static final class Value {
		public final double[] v = new double[16];
		public int size = 1;
		public boolean set;

		public Value put(double x) {
			this.v[0] = x;
			this.size = 1;
			this.set = true;
			return this;
		}

		public Value put(double x, double y) {
			this.v[0] = x;
			this.v[1] = y;
			this.size = 2;
			this.set = true;
			return this;
		}

		public Value put(double x, double y, double z) {
			this.v[0] = x;
			this.v[1] = y;
			this.v[2] = z;
			this.size = 3;
			this.set = true;
			return this;
		}

		public Value put(double x, double y, double z, double w) {
			this.v[0] = x;
			this.v[1] = y;
			this.v[2] = z;
			this.v[3] = w;
			this.size = 4;
			this.set = true;
			return this;
		}

		public Value matrix(float[] f) {
			for (int i = 0; i < 16; i++) this.v[i] = f[i];
			this.size = 16;
			this.set = true;
			return this;
		}

		public double[] array() {
			double[] out = new double[this.size];
			System.arraycopy(this.v, 0, out, 0, this.size);
			return out;
		}
	}

	private record Write(int offset, Value value, int base, int vector, int columns, int arrayStride, int matrixStride) {
	}

	public static final class Block {
		private final List<Write> writes;
		private final int size;
		private final ByteBuffer defaults;

		private Block(List<Write> writes, int size, ByteBuffer defaults) {
			this.writes = writes;
			this.size = size;
			this.defaults = defaults;
		}

		public int size() {
			return this.size;
		}

		public void write(long address) {
			MemoryUtil.memCopy(MemoryUtil.memAddress(this.defaults), address, this.size);
			for (Write w : this.writes) {
				if (!w.value.set) continue;
				long at = address + w.offset;
				double[] v = w.value.v;
				if (w.columns > 1) {
					int stride = w.matrixStride == 0 ? 16 : w.matrixStride;
					for (int c = 0; c < w.columns; c++) {
						for (int r = 0; r < w.vector; r++) {
							int src = w.value.size == 16 ? c * 4 + r : c * w.vector + r;
							MemoryUtil.memPutFloat(at + (long) c * stride + r * 4L, (float) v[Math.min(src, 15)]);
						}
					}
					continue;
				}
				for (int i = 0; i < w.vector; i++) {
					double x = v[Math.min(i, Math.max(0, w.value.size - 1))];
					switch (w.base) {
						case 2 -> MemoryUtil.memPutInt(at + i * 4L, x != 0 ? 1 : 0);
						case 7 -> MemoryUtil.memPutInt(at + i * 4L, (int) Math.floor(x + 1e-9));
						case 8 -> MemoryUtil.memPutInt(at + i * 4L, (int) (long) Math.floor(x + 1e-9));
						default -> MemoryUtil.memPutFloat(at + i * 4L, (float) x);
					}
				}
			}
		}
	}

	private final Map<String, Value> values = new ConcurrentHashMap<>();
	private final List<PackProperties.CustomUniform> custom;
	private final Map<String, PackExpr> parsed = new HashMap<>();
	private final Map<String, double[]> smooth = new HashMap<>();
	private final PackExpr.Env env;
	private double frameTime;

	public PackUniforms(List<PackProperties.CustomUniform> custom) {
		this.custom = custom;
		for (PackProperties.CustomUniform u : custom) {
			try {
				this.parsed.put(u.name(), PackExpr.parse(u.expression()));
			} catch (RuntimeException e) {
				System.out.println("[mcopt] shaderpack: custom uniform " + u.name() + ": " + e.getMessage());
			}
		}
		this.env = new PackExpr.Env() {
			@Override
			public double @Nullable [] value(String name) {
				Value v = PackUniforms.this.values.get(name);
				return v == null || !v.set ? null : v.array();
			}

		};
	}

	public Value get(String name) {
		return this.values.computeIfAbsent(name, n -> new Value());
	}

	public @Nullable Value find(String name) {
		return this.values.get(name);
	}

	public void frameTime(double seconds) {
		this.frameTime = seconds;
	}

	public void evaluateCustom() {
		for (PackProperties.CustomUniform u : this.custom) {
			PackExpr e = this.parsed.get(u.name());
			if (e == null) continue;
			double[] r;
			try {
				r = smoothAware(u.name(), e);
			} catch (RuntimeException ex) {
				continue;
			}
			Value v = get(u.name());
			int n = switch (u.type()) {
				case "vec2", "ivec2" -> 2;
				case "vec3", "ivec3" -> 3;
				case "vec4", "ivec4" -> 4;
				default -> 1;
			};
			for (int i = 0; i < n; i++) v.v[i] = r[Math.min(i, r.length - 1)];
			if (u.type().equals("int")) v.v[0] = (long) v.v[0];
			if (u.type().equals("bool")) v.v[0] = v.v[0] != 0 ? 1 : 0;
			v.size = n;
			v.set = true;
		}
	}

	private double[] smoothAware(String name, PackExpr e) {
		return e.eval(new PackExpr.Env() {
			private int index;

			@Override
			public double @Nullable [] value(String n) {
				return PackUniforms.this.env.value(n);
			}

			@Override
			public double @Nullable [] call(String fn, double[][] args) {
				if (!fn.equals("smooth") || args.length < 2) return null;
				String key = name + "#" + this.index++;
				double[] target = args.length == 4 ? args[1] : args[0];
				double up = args.length == 4 ? args[2][0] : args[1][0];
				double down = args.length == 4 ? args[3][0] : args.length == 3 ? args[2][0] : up;
				double[] prev = PackUniforms.this.smooth.get(key);
				if (prev == null || prev.length != target.length) {
					PackUniforms.this.smooth.put(key, target.clone());
					return target;
				}
				double[] out = new double[target.length];
				for (int i = 0; i < out.length; i++) {
					double t = target[i] > prev[i] ? up : down;
					double k = t <= 0 ? 1 : 1 - Math.exp(-PackUniforms.this.frameTime * Math.log(2) / t);
					out[i] = prev[i] + (target[i] - prev[i]) * k;
				}
				PackUniforms.this.smooth.put(key, out);
				return out;
			}
		});
	}

	public Block block(ShaderCompiler.Reflection reflection, Map<String, String> defaults) {
		List<Write> writes = new ArrayList<>();
		ByteBuffer init = MemoryUtil.memCalloc(Math.max(16, reflection.uniformSize()));
		for (ShaderCompiler.Member m : reflection.uniforms()) {
			Value v = get(m.name());
			writes.add(new Write(m.offset(), v, m.base(), m.vector(), m.columns(), m.arrayStride(), m.matrixStride()));
			String d = defaults.get(m.name());
			if (d != null && m.columns() <= 1) {
				try {
					double[] x = PackExpr.parse(d.replaceAll("\\b(vec[234]|ivec[234])\\b", "$1")).eval(name -> null);
					for (int i = 0; i < m.vector(); i++) {
						double val = x[Math.min(i, x.length - 1)];
						long at = MemoryUtil.memAddress(init) + m.offset() + i * 4L;
						if (m.base() == 13) MemoryUtil.memPutFloat(at, (float) val);
						else MemoryUtil.memPutInt(at, (int) val);
					}
				} catch (RuntimeException ignored) {
				}
			}
		}
		return new Block(writes, Math.max(16, reflection.uniformSize()), init);
	}
}
