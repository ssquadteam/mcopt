package mcopt.metal.shaderpack;

import java.util.ArrayList;
import java.util.List;

public final class GlslLexer {
	public enum Kind {
		IDENT, NUMBER, PUNCT, SPACE
	}

	public static final class Tok {
		public final Kind kind;
		public String text;

		public Tok(Kind kind, String text) {
			this.kind = kind;
			this.text = text;
		}

		public boolean is(String s) {
			return this.text.equals(s);
		}

		public boolean ident() {
			return this.kind == Kind.IDENT;
		}

		public boolean space() {
			return this.kind == Kind.SPACE;
		}

		@Override
		public String toString() {
			return this.text;
		}
	}

	private static final String[] OPERATORS = {"<<=", ">>=", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "^=", "|=", "==", "!=", "<=", ">=", "&&", "||", "^^", "<<",
		">>"};

	private GlslLexer() {
	}

	public static List<Tok> lex(String s) {
		List<Tok> out = new ArrayList<>(s.length() / 3);
		int i = 0, n = s.length();
		while (i < n) {
			char c = s.charAt(i);
			int start = i;
			if (Character.isWhitespace(c)) {
				while (i < n && Character.isWhitespace(s.charAt(i))) i++;
				out.add(new Tok(Kind.SPACE, s.substring(start, i)));
			} else if (Character.isLetter(c) || c == '_') {
				while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
				out.add(new Tok(Kind.IDENT, s.substring(start, i)));
			} else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
				if (c == '0' && i + 1 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
					i += 2;
					while (i < n && (Character.digit(s.charAt(i), 16) >= 0)) i++;
				} else {
					while (i < n) {
						char d = s.charAt(i);
						if (Character.isDigit(d) || d == '.') {
							i++;
						} else if ((d == 'e' || d == 'E') && i + 1 < n && (Character.isDigit(s.charAt(i + 1)) || s.charAt(i + 1) == '-' || s.charAt(i + 1) == '+')) {
							i += 2;
						} else {
							break;
						}
					}
				}
				while (i < n && "uUfFlLhH".indexOf(s.charAt(i)) >= 0) i++;
				out.add(new Tok(Kind.NUMBER, s.substring(start, i)));
			} else {
				String op = null;
				for (String o : OPERATORS) {
					if (s.startsWith(o, i)) {
						op = o;
						break;
					}
				}
				if (op == null) op = String.valueOf(c);
				i += op.length();
				out.add(new Tok(Kind.PUNCT, op));
			}
		}
		return out;
	}

	public static String join(List<Tok> toks) {
		StringBuilder b = new StringBuilder(toks.size() * 4);
		for (Tok t : toks) b.append(t.text);
		return b.toString();
	}

	public static int next(List<Tok> toks, int i) {
		int k = i + 1;
		while (k < toks.size() && toks.get(k).space()) k++;
		return k;
	}

	public static int prev(List<Tok> toks, int i) {
		int k = i - 1;
		while (k >= 0 && toks.get(k).space()) k--;
		return k;
	}

	public static int match(List<Tok> toks, int open) {
		String o = toks.get(open).text;
		String c = switch (o) {
			case "(" -> ")";
			case "[" -> "]";
			case "{" -> "}";
			default -> throw new IllegalArgumentException(o);
		};
		int depth = 0;
		for (int k = open; k < toks.size(); k++) {
			Tok t = toks.get(k);
			if (t.kind != Kind.PUNCT) continue;
			if (t.is(o)) depth++;
			else if (t.is(c) && --depth == 0) return k;
		}
		return toks.size() - 1;
	}
}
