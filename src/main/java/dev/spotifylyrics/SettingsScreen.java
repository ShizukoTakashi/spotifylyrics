package dev.spotifylyrics;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.awt.Color;

public final class SettingsScreen extends Screen {
	private static final int WIN_W = 356;
	private static final int WIN_H = 248;
	private static final int PANEL_W = 332;
	private static final String[] PANEL_NAMES = {"None", "Soft", "Solid"};
	private static int lastTab = 0;

	private final Screen parent;
	private int tab = lastTab;
	private int winX, winY, left;
	private EditBox searchBox;
	private boolean editingOtherColor = false;
	private float wheelHue = 0.75f, wheelSat = 0.45f, wheelVal = 1.0f;

	public SettingsScreen(Screen parent) {
		super(Component.literal("Spotify Lyrics"));
		this.parent = parent;
		syncWheelFromConfig();
	}

	private void syncWheelFromConfig() {
		Config cfg = SpotifyLyricsMod.config();
		int rgb = editingOtherColor ? cfg.otherColor : cfg.activeColor;
		float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
		wheelHue = hsb;
		wheelSat = hsb;
		wheelVal = hsb;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		SpotifyLyricsMod.config().save();
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	protected void init() {
		winX = (this.width - WIN_W) / 2;
		winY = (this.height - WIN_H) / 2;
		left = winX + 12;

		String[] names = {"Player", "Appearance", "Sky", "Account"};
		int tabW = (PANEL_W - 12) / 4;
		for (int i = 0; i < names.length; i++) {
			final int idx = i;
			Button b = Button.builder(Component.literal(names[i]), btn -> {
				tab = idx;
				lastTab = idx;
				SpotifyLyricsMod.config().save();
				this.rebuildWidgets();
			}).bounds(left + i * (tabW + 4), winY + 24, tabW, 18).build();
			b.active = tab != i;
			addRenderableWidget(b);
		}

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(left, winY + WIN_H - 26, PANEL_W, 18).build());

		switch (tab) {
			case 0 -> initPlayer();
			case 1 -> initAppearance();
			case 2 -> initSky();
			default -> initAccount();
		}
	}

	private void initPlayer() {
		PlaybackTracker tr = SpotifyLyricsMod.tracker();
		SpotifyApi api = SpotifyLyricsMod.api();
		int y = winY + 96;
		addRenderableWidget(Button.builder(Component.literal("-10s"), b -> seekBy(-10_000))
				.bounds(left, y, 48, 20).build());
		addRenderableWidget(Button.builder(Component.literal("⏮ Prev"), b -> tr.act(api::previous))
				.bounds(left + 52, y, 62, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Play / Pause"), b -> {
			SpotifyApi.Sample s = tr.sample();
			if (s != null && s.playing()) tr.act(api::pause);
			else tr.act(api::resume);
		}).bounds(left + 118, y, 96, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Next ⏭"), b -> tr.act(api::next))
				.bounds(left + 218, y, 62, 20).build());
		addRenderableWidget(Button.builder(Component.literal("+10s"), b -> seekBy(10_000))
				.bounds(left + 284, y, 48, 20).build());

		SpotifyApi.Sample s = tr.sample();
		double vol = s != null && s.volumePercent() >= 0 ? s.volumePercent() / 100.0 : 0.5;
		addRenderableWidget(new Slider(left, winY + 122, PANEL_W, 20, vol, v -> "Spotify Volume: " + Math.round(v * 100) + "%",
				v -> tr.setVolumeDebounced((int) Math.round(v * 100))));

		searchBox = new EditBox(this.font, left, winY + 148, 248, 20, Component.literal("Search"));
		searchBox.setMaxLength(100);
		searchBox.setHint(Component.literal("Search song or artist..."));
		addRenderableWidget(searchBox);
		addRenderableWidget(Button.builder(Component.literal("Play"), b -> {
			String q = searchBox.getValue().trim();
			if (!q.isEmpty()) tr.act(() -> api.searchAndPlay(q));
		}).bounds(left + 252, winY + 148, 80, 20).build());
	}

	private void seekBy(long deltaMs) {
		SpotifyApi.Sample s = SpotifyLyricsMod.tracker().sample();
		if (s == null) return;
		long target = Math.max(0, s.estimatedProgressMs() + deltaMs);
		SpotifyLyricsMod.tracker().act(() -> SpotifyLyricsMod.api().seek(target));
	}

	private void initAppearance() {
		Config cfg = SpotifyLyricsMod.config();
		int y = winY + 48;

		Fonts.FontChoice fc = Fonts.byId(cfg.font);
		addRenderableWidget(Button.builder(LyricsRenderer.styled("Font: " + fc.label() + "  (click)", fc, false), b -> {
			cfg.font = Fonts.next(cfg.font).id();
			cfg.save();
			this.rebuildWidgets();
		}).bounds(left, y, PANEL_W, 18).build());
		y += 22;

		int ctrlX = left + 76;
		int ctrlW = PANEL_W - 76;
		addRenderableWidget(Button.builder(Component.literal("Target: " + (editingOtherColor ? "Other Lines" : "Active Line")), b -> {
			editingOtherColor = !editingOtherColor;
			syncWheelFromConfig();
			this.rebuildWidgets();
		}).bounds(ctrlX, y, ctrlW, 18).build());

		addRenderableWidget(new Slider(ctrlX, y + 21, ctrlW, 18, wheelVal,
				v -> "Brightness: " + Math.round(v * 100) + "%", v -> {
			wheelVal = (float) v;
			applyWheelColor();
		}));

		addRenderableWidget(new Slider(ctrlX, y + 42, ctrlW / 2 - 2, 18, (cfg.scale - 0.6) / 1.9,
				v -> "Size: " + String.format("%.1fx", 0.6 + v * 1.9), v -> cfg.scale = (float) (0.6 + v * 1.9)));
		addRenderableWidget(new Slider(ctrlX + ctrlW / 2 + 2, y + 42, ctrlW / 2 - 2, 18, cfg.hudBottomOffset / 300.0,
				v -> "Y: " + Math.round(v * 300) + "px", v -> cfg.hudBottomOffset = (int) Math.round(v * 300)));
		y += 66;

		addRenderableWidget(Button.builder(Component.literal("Layout: " + (cfg.scatteredMode ? "Scattered" : "Centered")), b -> {
			cfg.scatteredMode = !cfg.scatteredMode;
			this.rebuildWidgets();
		}).bounds(left, y, 164, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Sky Horizon: " + (cfg.skyHorizonMode ? "On" : "Off")), b -> {
			cfg.skyHorizonMode = !cfg.skyHorizonMode;
			this.rebuildWidgets();
		}).bounds(left + 168, y, 164, 18).build());
		y += 21;

		addRenderableWidget(Button.builder(Component.literal("Cover Art: " + (cfg.showCoverArt ? "On" : "Off")), b -> {
			cfg.showCoverArt = !cfg.showCoverArt;
			this.rebuildWidgets();
		}).bounds(left, y, 108, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Lines: " + cfg.linesShown), b -> {
			cfg.linesShown = cfg.linesShown == 1 ? 3 : cfg.linesShown == 3 ? 5 : 1;
			this.rebuildWidgets();
		}).bounds(left + 112, y, 108, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Panel: " + PANEL_NAMES[cfg.panelStyle]), b -> {
			cfg.panelStyle = (cfg.panelStyle + 1) % 3;
			this.rebuildWidgets();
		}).bounds(left + 224, y, 108, 18).build());
		y += 21;

		addRenderableWidget(new Slider(left, y, 164, 18, (cfg.lyricsOffsetMs + 2000) / 4000.0,
				v -> "Sync: " + (Math.round(v * 4000) - 2000) + "ms", v -> cfg.lyricsOffsetMs = (int) Math.round(v * 4000) - 2000));
		addRenderableWidget(Button.builder(Component.literal("Bold: " + (cfg.boldActive ? "On" : "Off")), b -> {
			cfg.boldActive = !cfg.boldActive;
			this.rebuildWidgets();
		}).bounds(left + 168, y, 80, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Lyrics: " + (cfg.lyricsEnabled ? "On" : "Off")), b -> {
			cfg.lyricsEnabled = !cfg.lyricsEnabled;
			this.rebuildWidgets();
		}).bounds(left + 252, y, 80, 18).build());
	}
	private void initSky() {
		Config cfg = SpotifyLyricsMod.config();
		int y = winY + 54;
		for (int i = 0; i < CustomSkyRenderer.SKY_IDS.length; i++) {
			final String id = CustomSkyRenderer.SKY_IDS[i];
			String name = CustomSkyRenderer.SKY_NAMES[i];
			int col = i % 2;
			int row = i / 2;
			Button b = Button.builder(Component.literal((id.equals(cfg.skyTheme) ? "✦ " : "") + name), btn -> {
				cfg.skyTheme = id;
				cfg.save();
				this.rebuildWidgets();
			}).bounds(left + col * 168, y + row * 26, 164, 22).build();
			b.active = !id.equals(cfg.skyTheme);
			addRenderableWidget(b);
		}
		addRenderableWidget(Button.builder(Component.literal("Sky Horizon Lyrics: " + (cfg.skyHorizonMode ? "On" : "Off")), b -> {
			cfg.skyHorizonMode = !cfg.skyHorizonMode;
			cfg.save();
			this.rebuildWidgets();
		}).bounds(left, y + 86, PANEL_W, 20).build());
	}

	private void initAccount() {
		addRenderableWidget(Button.builder(Component.literal("Refresh Windows Media Connection"), b -> {
			SpotifyLyricsMod.tracker().pollSoon();
			SpotifyLyricsMod.say("Connected via Windows Media (Free Mode Active).");
		}).bounds(left, winY + 96, PANEL_W, 20).build());
	}

	private void applyWheelColor() {
		int rgb = Color.HSBtoRGB(wheelHue, wheelSat, wheelVal) & 0xFFFFFF;
		Config cfg = SpotifyLyricsMod.config();
		if (editingOtherColor) cfg.otherColor = rgb;
		else cfg.activeColor = rgb;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (tab == 1 && button == 0 && handleWheelInput(mouseX, mouseY)) return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (tab == 1 && button == 0 && handleWheelInput(mouseX, mouseY)) return true;
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	private boolean handleWheelInput(double mx, double my) {
		int cx = left + 34;
		int cy = winY + 104;
		int radius = 32;
		double dx = mx - cx;
		double dy = my - cy;
		double dist = Math.sqrt(dx * dx + dy * dy);
		if (dist <= radius + 3) {
			wheelSat = (float) Mth.clamp(dist / radius, 0.0, 1.0);
			wheelHue = (float) ((Math.atan2(dy, dx) / (Math.PI * 2.0) + 1.0) % 1.0);
			applyWheelColor();
			return true;
		}
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(winX - 2, winY - 2, winX + WIN_W + 2, winY + WIN_H + 2, 0xFF8A5CF6);
		g.fill(winX, winY, winX + WIN_W, winY + WIN_H, 0xFF170D2E);
		g.fill(winX + 4, winY + 4, winX + WIN_W - 4, winY + 20, 0xFF26154A);
		g.text(this.font, "♪  Spotify Lyrics Studio", winX + 10, winY + 8, 0xFFE9DDFF, true);

		super.extractRenderState(g, mouseX, mouseY, delta);

		Config cfg = SpotifyLyricsMod.config();
		if (tab == 0) {
			drawPlayerInfo(g);
		} else if (tab == 1) {
			drawColorWheel(g, left + 34, winY + 104, 32);
			int previewCol = editingOtherColor ? cfg.otherColor : cfg.activeColor;
			g.fill(left + 2, winY + 140, left + 66, winY + 146, 0xFF000000 | previewCol);
		} else if (tab == 2) {
			g.text(this.font, "Choose a custom sky backdrop (replaces vanilla sky when looking up):",
					left, winY + 168, 0xFFCBB8F5, true);
		} else {
			g.text(this.font, "Status: Windows Media Auto-Sync Active (No Premium Needed)",
					left, winY + 60, 0xFF55FF99, true);
			g.text(this.font, "Just play any song in your Spotify desktop app.",
					left, winY + 76, 0xFFD8C8FF, true);
		}
	}

	private void drawColorWheel(GuiGraphicsExtractor g, int cx, int cy, int radius) {
		for (int dy = -radius; dy <= radius; dy += 2) {
			for (int dx = -radius; dx <= radius; dx += 2) {
				double dist = Math.sqrt(dx * dx + dy * dy);
				if (dist <= radius) {
					float sat = (float) (dist / radius);
					float hue = (float) ((Math.atan2(dy, dx) / (Math.PI * 2.0) + 1.0) % 1.0);
					int rgb = Color.HSBtoRGB(hue, sat, wheelVal);
					g.fill(cx + dx, cy + dy, cx + dx + 2, cy + dy + 2, 0xFF000000 | rgb);
				}
			}
		}
		double angle = wheelHue * Math.PI * 2.0;
		int px = cx + (int) Math.round(Math.cos(angle) * wheelSat * radius);
		int py = cy + (int) Math.round(Math.sin(angle) * wheelSat * radius);
		g.fill(px - 2, py - 2, px + 3, py + 3, 0xFFFFFFFF);
		g.fill(px - 1, py - 1, px + 2, py + 2, 0xFF000000);
	}

	private void drawPlayerInfo(GuiGraphicsExtractor g) {
		PlaybackTracker tr = SpotifyLyricsMod.tracker();
		SpotifyApi.Sample s = tr.sample();
		g.fill(left, winY + 46, left + PANEL_W, winY + 88, 0xFF221342);
		if (s == null) {
			g.text(this.font, "Nothing playing. Start a song in the Spotify app.", left + 8, winY + 56, 0xFFFFB86C, true);
		} else {
			g.text(this.font, s.title(), left + 8, winY + 52, 0xFFFFFFFF, true);
			g.text(this.font, s.artist() + (s.playing() ? "" : "  (paused)"), left + 8, winY + 64, 0xFFC9B6F2, true);
			float p = s.durationMs() > 0 ? (float) s.estimatedProgressMs() / s.durationMs() : 0f;
			g.fill(left + 8, winY + 78, left + PANEL_W - 8, winY + 82, 0x50FFFFFF);
			g.fill(left + 8, winY + 78, left + 8 + Math.round((PANEL_W - 16) * Math.max(0f, Math.min(1f, p))), winY + 82,
					0xFF000000 | SpotifyLyricsMod.config().activeColor);
		}
		String msg = tr.message();
		if (!msg.isEmpty()) g.text(this.font, msg, left, winY + 176, 0xFFD8C8FF, true);
	}

	private static final class Slider extends AbstractSliderButton {
		private final java.util.function.DoubleFunction<String> label;
		private final java.util.function.DoubleConsumer onChange;

		Slider(int x, int y, int w, int h, double initial,
			   java.util.function.DoubleFunction<String> label, java.util.function.DoubleConsumer onChange) {
			super(x, y, w, h, Component.empty(), Math.max(0, Math.min(1, initial)));
			this.label = label;
			this.onChange = onChange;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label.apply(this.value)));
		}

		@Override
		protected void applyValue() {
			onChange.accept(this.value);
		}
	}
}
