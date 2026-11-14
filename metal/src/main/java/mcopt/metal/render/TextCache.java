package mcopt.metal.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Arrays;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import org.jspecify.annotations.Nullable;

/**
 * -Dmcopt.render.textCache: a laid-out line of text (its glyphs, shadows and effects at their final positions) is kept and
 * handed out again while the same font draws the same characters in the same styles at the same place and colour, which is
 * what the HUD, scoreboard, tab list, chat and name tags do almost every frame. A hit costs one walk over the characters and
 * no glyph lookups or allocations. Obfuscated text and sprite or player-head glyphs are laid out every time as before; any
 * font reload or font option change drops everything kept.
 */
public final class TextCache {
	private static final int SLOTS = 2048;
	private static final int MAX_CHARS = 256;

	private static final class Entry {
		Font font;
		int generation;
		int hash;
		int x, y, color, background;
		boolean shadow, includeEmpty;
		int[] chars;
		Style[] styles;
		Font.PreparedText prepared;
	}

	private static final Entry[] slots = new Entry[SLOTS];
	private static int generation;
	private static @Nullable Thread owner;

	private static int[] chars = new int[64];
	private static Style[] styles = new Style[64];
	private static int length;
	private static boolean cacheable;
	private static int hash;
	private static @Nullable Style lastStyle;

	private static final FormattedCharSink SINK = (position, style, codepoint) -> {
		if (length == MAX_CHARS || style.isObfuscated() || !(style.getFont() instanceof FontDescription.Resource)) {
			cacheable = false;
			return false;
		}
		if (length == chars.length) {
			chars = Arrays.copyOf(chars, length * 2);
			styles = Arrays.copyOf(styles, length * 2);
		}
		if (style != lastStyle) {
			lastStyle = style;
			hash = hash * 31 + style.hashCode() * 17 + style.getFont().hashCode();
		}
		chars[length] = codepoint;
		styles[length] = style;
		length++;
		hash = hash * 31 + codepoint;
		return true;
	};

	private TextCache() {
	}

	public static void invalidate() {
		generation++;
		Arrays.fill(slots, null);
	}

	public static Font.PreparedText prepare(Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow, boolean includeEmpty,
		int background, Operation<Font.PreparedText> original) {
		if (owner == null) owner = Thread.currentThread();
		if (owner != Thread.currentThread()) return original.call(text, x, y, color, shadow, includeEmpty, background);
		length = 0;
		cacheable = true;
		lastStyle = null;
		hash = 0x9E3779B9;
		text.accept(SINK);
		lastStyle = null;
		if (!cacheable) return original.call(text, x, y, color, shadow, includeEmpty, background);
		int xb = Float.floatToRawIntBits(x), yb = Float.floatToRawIntBits(y);
		int h = hash;
		h = h * 31 + xb;
		h = h * 31 + yb;
		h = h * 31 + color;
		h = h * 31 + background;
		h = h * 31 + (shadow ? 1 : 0) + (includeEmpty ? 2 : 0);
		h = h * 31 + System.identityHashCode(font);
		h ^= h >>> 16;
		int slot = h & (SLOTS - 1);
		Entry e = slots[slot];
		if (e != null && e.hash == h && e.font == font && e.generation == generation && e.x == xb && e.y == yb && e.color == color
			&& e.background == background && e.shadow == shadow && e.includeEmpty == includeEmpty && same(e)) {
			Arrays.fill(styles, 0, length, null);
			return e.prepared;
		}
		Font.PreparedText prepared = original.call(text, x, y, color, shadow, includeEmpty, background);
		if (e == null) slots[slot] = e = new Entry();
		e.font = font;
		e.generation = generation;
		e.hash = h;
		e.x = xb;
		e.y = yb;
		e.color = color;
		e.background = background;
		e.shadow = shadow;
		e.includeEmpty = includeEmpty;
		e.chars = Arrays.copyOf(chars, length);
		e.styles = Arrays.copyOf(styles, length);
		e.prepared = prepared;
		Arrays.fill(styles, 0, length, null);
		return prepared;
	}

	private static boolean same(Entry e) {
		if (e.chars.length != length) return false;
		for (int i = 0; i < length; i++) {
			if (e.chars[i] != chars[i]) return false;
		}
		for (int i = 0; i < length; i++) {
			Style kept = e.styles[i], now = styles[i];
			if (kept == now) continue;
			if (i > 0 && now == styles[i - 1] && kept == e.styles[i - 1]) continue;
			if (!kept.equals(now)) return false;
		}
		return true;
	}
}
