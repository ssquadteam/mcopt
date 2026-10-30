package mcopt.metal.shaderpack.render;

import java.io.IOException;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

public final class ShaderPackScreen extends Screen {
	private final @Nullable Screen parent;
	private @Nullable PackList list;

	public ShaderPackScreen(@Nullable Screen parent) {
		super(Component.translatable("mcopt.pack.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.list = this.addRenderableWidget(new PackList(this.minecraft, this.width, this.height - 96, 32));
		int y = this.height - 52;
		this.addRenderableWidget(Button.builder(Component.translatable("mcopt.pack.apply"), b -> this.apply()).bounds(this.width / 2 - 154, y, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("mcopt.pack.folder"), b -> this.openFolder()).bounds(this.width / 2 - 50, y, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose()).bounds(this.width / 2 + 54, y, 100, 20).build());
	}

	private void apply() {
		PackList l = this.list;
		if (l == null || l.getSelected() == null) return;
		ShaderPacks.select(l.getSelected().pack);
	}

	private void openFolder() {
		try {
			new ProcessBuilder("open", PackRuntime.directory().toString()).start();
		} catch (IOException e) {
			System.out.println("[mcopt] shaderpacks: can't open " + PackRuntime.directory() + ": " + e);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
		String status = PackRuntime.status();
		if (!status.isEmpty()) g.centeredText(this.font, Component.literal(status), this.width / 2, this.height - 26, 0xFFA0A0A0);
	}

	private final class PackList extends ObjectSelectionList<PackEntry> {
		PackList(Minecraft minecraft, int width, int height, int top) {
			super(minecraft, width, height, top, 18);
			PackEntry off = new PackEntry("");
			this.addEntry(off);
			PackEntry current = off;
			for (String pack : ShaderPacks.available()) {
				PackEntry entry = new PackEntry(pack);
				this.addEntry(entry);
				if (pack.equals(ShaderPacks.selected())) current = entry;
			}
			this.setSelected(current);
		}

		@Override
		public int getRowWidth() {
			return Math.min(this.width - 40, 320);
		}
	}

	private final class PackEntry extends ObjectSelectionList.Entry<PackEntry> {
		final String pack;

		PackEntry(String pack) {
			this.pack = pack;
		}

		private Component label() {
			Component name = this.pack.isEmpty() ? Component.translatable("mcopt.pack.off") : Component.literal(this.pack.replaceFirst("\\.zip$", ""));
			return Objects.equals(this.pack, ShaderPacks.selected()) ? Component.translatable("mcopt.pack.active", name) : name;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta) {
			g.text(ShaderPackScreen.this.font, this.label(), this.getContentX() + 4, this.getContentY() + 2, 0xFFFFFFFF);
		}

		@Override
		public Component getNarration() {
			return this.label();
		}
	}
}
