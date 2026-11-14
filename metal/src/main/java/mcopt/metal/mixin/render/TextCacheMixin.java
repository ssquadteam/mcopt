package mcopt.metal.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcopt.metal.render.TextCache;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Font.class)
abstract class TextCacheMixin {
	@WrapMethod(method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;")
	private Font.PreparedText mcopt$cached(FormattedCharSequence text, float x, float y, int color, boolean shadow, boolean includeEmpty, int background,
		Operation<Font.PreparedText> original) {
		return TextCache.prepare((Font) (Object) this, text, x, y, color, shadow, includeEmpty, background, original);
	}
}
