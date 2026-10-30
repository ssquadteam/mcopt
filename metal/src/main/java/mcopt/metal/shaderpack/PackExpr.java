package mcopt.metal.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public abstract class PackExpr {
	public interface Env {
		double @Nullable [] value(String name);

		default double @Nullable [] call(String name, double[][] args) {
			return null;
		}

		default boolean defined(String name) {
			return value(name) != null;
		}
	}

	public static final class ParseException extends RuntimeException {
		public ParseException(String message) {
			super(message);
		}
	}

	private static final double[] ZERO = {0};
	private static final double[] ONE = {1};

	public abstract double[] eval(Env env);

	public abstract void names(Set<String> out);

	public double scalar(Env env) {
		return eval(env)[0];
	}

	public boolean truth(Env env) {
		return eval(env)[0] != 0;
	}

	public static PackExpr parse(String source) {
		Parser p = new Parser(source);
		PackExpr e = p.ternary();
		p.skip();
		if (p.at < p.s.length()) throw new ParseException("unexpected '" + p.s.substring(p.at) + "' in " + source);
		return e;
	}

	public static double[] of(double v) {
		return new double[] {v};
	}

	public static double[] bool(boolean v) {
		return v ? ONE : ZERO;
	}

	private static final class Literal extends PackExpr {
		final double[] v;

		Literal(double[] v) {
			this.v = v;
		}

		@Override
		public double[] eval(Env env) {
			return this.v;
		}

		@Override
		public void names(Set<String> out) {
		}
	}

	private static final class Name extends PackExpr {
		final String name;

		Name(String name) {
			this.name = name;
		}

		@Override
		public double[] eval(Env env) {
			double[] v = env.value(this.name);
			if (v != null) return v;
			return switch (this.name) {
				case "true" -> ONE;
				case "false" -> ZERO;
				case "pi", "PI" -> of(Math.PI);
				default -> ZERO;
			};
		}

		@Override
		public void names(Set<String> out) {
			out.add(this.name);
		}
	}

	private static final class Defined extends PackExpr {
		final String name;

		Defined(String name) {
			this.name = name;
		}

		@Override
		public double[] eval(Env env) {
			return bool(env.defined(this.name));
		}

		@Override
		public void names(Set<String> out) {
			out.add(this.name);
		}
	}

	private static final class Element extends PackExpr {
		final PackExpr base;
		final int index;

		Element(PackExpr base, int index) {
			this.base = base;
			this.index = index;
		}

		@Override
		public double[] eval(Env env) {
			double[] v = this.base.eval(env);
			if (v.length == 16) return java.util.Arrays.copyOfRange(v, this.index * 4, this.index * 4 + 4);
			return of(this.index < v.length ? v[this.index] : 0);
		}

		@Override
		public void names(Set<String> out) {
			this.base.names(out);
		}
	}

	private static final class Member extends PackExpr {
		final PackExpr base;
		final int[] lanes;

		Member(PackExpr base, int[] lanes) {
			this.base = base;
			this.lanes = lanes;
		}

		@Override
		public double[] eval(Env env) {
			double[] v = this.base.eval(env);
			double[] r = new double[this.lanes.length];
			for (int i = 0; i < r.length; i++) r[i] = this.lanes[i] < v.length ? v[this.lanes[i]] : 0;
			return r;
		}

		@Override
		public void names(Set<String> out) {
			this.base.names(out);
		}
	}

	private static final class Unary extends PackExpr {
		final char op;
		final PackExpr a;

		Unary(char op, PackExpr a) {
			this.op = op;
			this.a = a;
		}

		@Override
		public double[] eval(Env env) {
			double[] v = this.a.eval(env);
			if (this.op == '!') return bool(v[0] == 0);
			if (this.op == '~') return of(~(long) v[0]);
			double[] r = new double[v.length];
			for (int i = 0; i < r.length; i++) r[i] = -v[i];
			return r;
		}

		@Override
		public void names(Set<String> out) {
			this.a.names(out);
		}
	}

	private static final class Binary extends PackExpr {
		final String op;
		final PackExpr a, b;

		Binary(String op, PackExpr a, PackExpr b) {
			this.op = op;
			this.a = a;
			this.b = b;
		}

		@Override
		public double[] eval(Env env) {
			switch (this.op) {
				case "&&":
					return bool(this.a.truth(env) && this.b.truth(env));
				case "||":
					return bool(this.a.truth(env) || this.b.truth(env));
				default:
			}
			double[] x = this.a.eval(env), y = this.b.eval(env);
			switch (this.op) {
				case "==":
					return bool(equal(x, y));
				case "!=":
					return bool(!equal(x, y));
				case "<":
					return bool(x[0] < y[0]);
				case "<=":
					return bool(x[0] <= y[0]);
				case ">":
					return bool(x[0] > y[0]);
				case ">=":
					return bool(x[0] >= y[0]);
				case "&":
					return of((long) x[0] & (long) y[0]);
				case "|":
					return of((long) x[0] | (long) y[0]);
				case "^":
					return of((long) x[0] ^ (long) y[0]);
				case "<<":
					return of((long) x[0] << (long) y[0]);
				case ">>":
					return of((long) x[0] >> (long) y[0]);
				default:
			}
			int n = Math.max(x.length, y.length);
			double[] r = new double[n];
			for (int i = 0; i < n; i++) {
				double p = x[Math.min(i, x.length - 1)], q = y[Math.min(i, y.length - 1)];
				r[i] = switch (this.op) {
					case "+" -> p + q;
					case "-" -> p - q;
					case "*" -> p * q;
					case "/" -> q == 0 ? 0 : p / q;
					case "%" -> q == 0 ? 0 : p % q;
					default -> throw new ParseException("bad operator " + this.op);
				};
			}
			return r;
		}

		@Override
		public void names(Set<String> out) {
			this.a.names(out);
			this.b.names(out);
		}

		private static boolean equal(double[] x, double[] y) {
			if (x.length != y.length) return x[0] == y[0];
			for (int i = 0; i < x.length; i++) if (x[i] != y[i]) return false;
			return true;
		}
	}

	private static final class Ternary extends PackExpr {
		final PackExpr c, a, b;

		Ternary(PackExpr c, PackExpr a, PackExpr b) {
			this.c = c;
			this.a = a;
			this.b = b;
		}

		@Override
		public double[] eval(Env env) {
			return this.c.truth(env) ? this.a.eval(env) : this.b.eval(env);
		}

		@Override
		public void names(Set<String> out) {
			this.c.names(out);
			this.a.names(out);
			this.b.names(out);
		}
	}

	private static final class Call extends PackExpr {
		final String name;
		final PackExpr[] args;

		Call(String name, PackExpr[] args) {
			this.name = name;
			this.args = args;
		}

		@Override
		public double[] eval(Env env) {
			switch (this.name) {
				case "if": {
					for (int i = 0; i + 1 < this.args.length; i += 2) if (this.args[i].truth(env)) return this.args[i + 1].eval(env);
					return this.args.length % 2 == 1 ? this.args[this.args.length - 1].eval(env) : ZERO;
				}
				case "defined":
					return bool(this.args.length == 1 && this.args[0] instanceof Name n && env.defined(n.name));
				default:
			}
			double[][] v = new double[this.args.length][];
			for (int i = 0; i < v.length; i++) v[i] = this.args[i].eval(env);
			double[] custom = env.call(this.name, v);
			if (custom != null) return custom;
			return builtin(this.name, v);
		}

		@Override
		public void names(Set<String> out) {
			for (PackExpr a : this.args) a.names(out);
		}
	}

	static double[] builtin(String name, double[][] v) {
		switch (name) {
			case "vec2", "vec3", "vec4": {
				int n = name.charAt(3) - '0';
				double[] r = new double[n];
				int k = 0;
				for (double[] a : v) for (double x : a) if (k < n) r[k++] = x;
				if (v.length == 1 && v[0].length == 1) for (int i = 1; i < n; i++) r[i] = v[0][0];
				return r;
			}
			case "min":
				return lanes(v, (a) -> Math.min(a[0], a[1]));
			case "max":
				return lanes(v, (a) -> Math.max(a[0], a[1]));
			case "clamp":
				return lanes(v, (a) -> Math.max(a[1], Math.min(a[2], a[0])));
			case "pow":
				return lanes(v, (a) -> Math.pow(a[0], a[1]));
			case "atan2":
				return lanes(v, (a) -> Math.atan2(a[0], a[1]));
			case "fmod":
				return lanes(v, (a) -> a[0] - a[1] * Math.floor(a[0] / a[1]));
			case "mix":
				return lanes(v, (a) -> a[0] + (a[1] - a[0]) * a[2]);
			case "step":
				return lanes(v, (a) -> a[1] < a[0] ? 0 : 1);
			case "smoothstep":
				return lanes(v, (a) -> {
					double t = Math.max(0, Math.min(1, (a[2] - a[0]) / (a[1] - a[0])));
					return t * t * (3 - 2 * t);
				});
			case "between":
				return bool(v[0][0] >= v[1][0] && v[0][0] <= v[2][0]);
			case "equals":
				return bool(Math.abs(v[0][0] - v[1][0]) <= (v.length > 2 ? v[2][0] : 0));
			case "in": {
				for (int i = 1; i < v.length; i++) if (v[0][0] == v[i][0]) return ONE;
				return ZERO;
			}
			case "random":
				return of(Math.random());
			case "length": {
				double s = 0;
				for (double x : v[0]) s += x * x;
				return of(Math.sqrt(s));
			}
			case "distance": {
				double s = 0;
				for (int i = 0; i < v[0].length; i++) {
					double d = v[0][i] - v[1][Math.min(i, v[1].length - 1)];
					s += d * d;
				}
				return of(Math.sqrt(s));
			}
			case "dot": {
				double s = 0;
				for (int i = 0; i < v[0].length; i++) s += v[0][i] * v[1][Math.min(i, v[1].length - 1)];
				return of(s);
			}
			case "normalize": {
				double s = 0;
				for (double x : v[0]) s += x * x;
				double l = Math.sqrt(s);
				double[] r = v[0].clone();
				if (l > 0) for (int i = 0; i < r.length; i++) r[i] /= l;
				return r;
			}
			case "cross":
				return new double[] {v[0][1] * v[1][2] - v[0][2] * v[1][1], v[0][2] * v[1][0] - v[0][0] * v[1][2], v[0][0] * v[1][1] - v[0][1] * v[1][0]};
			default:
		}
		Function<Double, Double> f = switch (name) {
			case "sin" -> Math::sin;
			case "cos" -> Math::cos;
			case "tan" -> Math::tan;
			case "asin" -> Math::asin;
			case "acos" -> Math::acos;
			case "atan" -> Math::atan;
			case "torad", "radians" -> Math::toRadians;
			case "todeg", "degrees" -> Math::toDegrees;
			case "abs" -> Math::abs;
			case "floor" -> Math::floor;
			case "ceil" -> Math::ceil;
			case "exp" -> Math::exp;
			case "exp2" -> x -> Math.pow(2, x);
			case "frac", "fract" -> x -> x - Math.floor(x);
			case "log" -> Math::log;
			case "log2" -> x -> Math.log(x) / Math.log(2);
			case "round" -> x -> (double) Math.round(x);
			case "signum", "sign" -> Math::signum;
			case "sqrt" -> Math::sqrt;
			case "int" -> x -> (double) (long) (double) x;
			case "float" -> x -> x;
			case "bool" -> x -> x != 0 ? 1.0 : 0.0;
			default -> throw new ParseException("unknown function " + name);
		};
		double[] r = v.length == 0 ? new double[1] : v[0].clone();
		for (int i = 0; i < r.length; i++) r[i] = f.apply(r[i]);
		return r;
	}

	private interface Lanes {
		double apply(double[] a);
	}

	private static double[] lanes(double[][] v, Lanes f) {
		int n = 1;
		for (double[] a : v) n = Math.max(n, a.length);
		double[] r = new double[n];
		double[] at = new double[v.length];
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < v.length; k++) at[k] = v[k][Math.min(i, v[k].length - 1)];
			r[i] = f.apply(at);
		}
		return r;
	}

	private static final class Parser {
		final String s;
		int at;

		Parser(String s) {
			this.s = s;
		}

		void skip() {
			while (this.at < this.s.length() && Character.isWhitespace(this.s.charAt(this.at))) this.at++;
		}

		boolean eat(String token) {
			skip();
			if (this.s.startsWith(token, this.at)) {
				if (token.length() == 1 && this.at + 1 < this.s.length()) {
					char c = token.charAt(0), n = this.s.charAt(this.at + 1);
					if ((c == '&' && n == '&') || (c == '|' && n == '|') || (c == '<' && (n == '=' || n == '<')) || (c == '>' && (n == '=' || n == '>'))
						|| (c == '!' && n == '=') || (c == '=' && n == '=')) {
						return false;
					}
				}
				this.at += token.length();
				return true;
			}
			return false;
		}

		PackExpr ternary() {
			PackExpr c = binary(0);
			if (eat("?")) {
				PackExpr a = ternary();
				if (!eat(":")) throw new ParseException("expected ':' in " + this.s);
				return new Ternary(c, a, ternary());
			}
			return c;
		}

		private static final String[][] LEVELS = {{"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<=", ">=", "<", ">"}, {"<<", ">>"}, {"+", "-"},
			{"*", "/", "%"}};

		PackExpr binary(int level) {
			if (level == LEVELS.length) return unary();
			PackExpr left = binary(level + 1);
			outer:
			while (true) {
				for (String op : LEVELS[level]) {
					if (eat(op)) {
						left = new Binary(op, left, binary(level + 1));
						continue outer;
					}
				}
				return left;
			}
		}

		PackExpr unary() {
			if (eat("!")) return new Unary('!', unary());
			if (eat("-")) return new Unary('-', unary());
			if (eat("+")) return unary();
			if (eat("~")) return new Unary('~', unary());
			return postfix(primary());
		}

		PackExpr postfix(PackExpr e) {
			while (true) {
				skip();
				if (this.at < this.s.length() && this.s.charAt(this.at) == '.' && this.at + 1 < this.s.length() && Character.isLetter(this.s.charAt(this.at + 1))) {
					this.at++;
					String m = ident();
					int[] lanes = new int[m.length()];
					for (int i = 0; i < lanes.length; i++) lanes[i] = "xyzw".indexOf(m.charAt(i)) >= 0 ? "xyzw".indexOf(m.charAt(i)) : "rgba".indexOf(m.charAt(i));
					e = new Member(e, lanes);
				} else if (!(e instanceof Literal) && this.at < this.s.length() && this.s.charAt(this.at) == '.' && this.at + 1 < this.s.length()
					&& Character.isDigit(this.s.charAt(this.at + 1))) {
					this.at++;
					e = new Element(e, Integer.parseInt(ident()));
				} else {
					return e;
				}
			}
		}

		String ident() {
			int start = this.at;
			while (this.at < this.s.length() && (Character.isLetterOrDigit(this.s.charAt(this.at)) || this.s.charAt(this.at) == '_')) this.at++;
			return this.s.substring(start, this.at);
		}

		PackExpr primary() {
			skip();
			if (this.at >= this.s.length()) throw new ParseException("unexpected end of " + this.s);
			char c = this.s.charAt(this.at);
			if (c == '(') {
				this.at++;
				PackExpr e = ternary();
				if (!eat(")")) throw new ParseException("expected ')' in " + this.s);
				return e;
			}
			if (Character.isDigit(c) || c == '.') return new Literal(of(number()));
			if (Character.isLetter(c) || c == '_') {
				String name = ident();
				if (name.equals("defined")) {
					boolean paren = eat("(");
					skip();
					String n = ident();
					if (paren && !eat(")")) throw new ParseException("expected ')' in " + this.s);
					return new Defined(n);
				}
				if (eat("(")) {
					List<PackExpr> args = new ArrayList<>();
					if (!eat(")")) {
						do {
							args.add(ternary());
						} while (eat(","));
						if (!eat(")")) throw new ParseException("expected ')' in " + this.s);
					}
					return new Call(name, args.toArray(PackExpr[]::new));
				}
				return new Name(name);
			}
			throw new ParseException("unexpected '" + c + "' in " + this.s);
		}

		double number() {
			int start = this.at;
			if (this.s.startsWith("0x", this.at) || this.s.startsWith("0X", this.at)) {
				this.at += 2;
				while (this.at < this.s.length() && Character.digit(this.s.charAt(this.at), 16) >= 0) this.at++;
				double v = Long.parseLong(this.s.substring(start + 2, this.at), 16);
				while (this.at < this.s.length() && "uUlL".indexOf(this.s.charAt(this.at)) >= 0) this.at++;
				return v;
			}
			while (this.at < this.s.length()) {
				char c = this.s.charAt(this.at);
				if (Character.isDigit(c) || c == '.' || ((c == 'e' || c == 'E') && this.at + 1 < this.s.length())) {
					if ((c == 'e' || c == 'E') && (this.s.charAt(this.at + 1) == '-' || this.s.charAt(this.at + 1) == '+')) this.at++;
					this.at++;
				} else {
					break;
				}
			}
			String num = this.s.substring(start, this.at);
			while (this.at < this.s.length() && "fFuUlL".indexOf(this.s.charAt(this.at)) >= 0) this.at++;
			try {
				return Double.parseDouble(num);
			} catch (NumberFormatException e) {
				throw new ParseException("bad number " + num.toLowerCase(Locale.ROOT));
			}
		}
	}
}
