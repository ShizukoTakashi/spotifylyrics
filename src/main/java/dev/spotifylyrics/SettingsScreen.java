package dev.spotifylyrics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** In-game settings: transport controls, fonts, colours and the Spotify account. Opened with the K key. */
public final class SettingsScreen extends Screen {
	private static final int PANEL_W = 300;
	private static final int[] PALETTE = {
			0x1ED760, 0xFFFFFF, 0xFFD93B, 0xFF8A3D, 0xFF4D4D, 0xFF7AC6,
			0xB388FF, 0x4DA3FF, 0x3DDCFF, 0x2EE6A6, 0xC8C8C8
	};
	private static final String[] PANEL_NAMES = {"None", "Soft", "Solid"};
	private static int lastTab = 0;

	private final Screen parent;
	private int tab = lastTab;
	private int left;
	private final List<int[]> swatches = new ArrayList<>(); // x, y, rgb
	private EditBox searchBox;
	private Button playPause;

	public SettingsScreen(Screen parent) {
		super(Component.literal("Spotify Lyrics"));
		this.parent = parent;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		SpotifyLyricsMod.config().save();
		this.minecraft.setScreen(parent);
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		swatches.clear();
		left = (this.width - PANEL_W) / 2;

		String[] names = {"Player", "Appearance", "Account"};
		for (int i = 0; i < names.length; i++) {
			final int idx = i;
			Button b = Button.builder(Component.literal(names[i]), btn -> {
				tab = idx;
				lastTab = idx;
				SpotifyLyricsMod.config().save();
				this.rebuildWidgets();
			}).bounds(left + i * 102, 10, 96, 20).build();
			b.active = tab != i;
			addRenderableWidget(b);
		}

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(left, this.height - 26, PANEL_W, 20).build());

		switch (tab) {
			case 0 -> initPlayer();
			case 1 -> initAppearance();
			default -> initAccount();
		}
	}

	private void initPlayer() {
		PlaybackTracker tr = SpotifyLyricsMod.tracker();
		SpotifyApi api = SpotifyLyricsMod.api();
		int y = 86;
		addRenderableWidget(Button.builder(Component.literal("-10s"), b -> seekBy(-10_000))
				.bounds(left, y, 44, 20).build());
		addRenderableWidget(Button.builder(Component.literal("⏮ Prev"), b -> tr.act(api::previous))
				.bounds(left + 48, y, 56, 20).build());
		playPause = Button.builder(Component.literal("Play / Pause"), b -> {
			SpotifyApi.Sample s = tr.sample();
			if (s != null && s.playing()) tr.act(api::pause);
			else tr.act(api::resume);
		}).bounds(left + 108, y, 84, 20).build();
		addRenderableWidget(playPause);
		addRenderableWidget(Button.builder(Component.literal("Next ⏭"), b -> tr.act(api::next))
				.bounds(left + 196, y, 56, 20).build());
		addRenderableWidget(Button.builder(Component.literal("+10s"), b -> seekBy(10_000))
				.bounds(left + 256, y, 44, 20).build());

		SpotifyApi.Sample s = tr.sample();
		double vol = s != null && s.volumePercent() >= 0 ? s.volumePercent() / 100.0 : 0.5;
		addRenderableWidget(new Slider(left, 114, PANEL_W, 20, vol, v -> "Spotify volume: " + Math.round(v * 100) + "%",
				v -> tr.setVolumeDebounced((int) Math.round(v * 100))));

		searchBox = new EditBox(this.font, left, 146, 220, 20, Component.literal("Search"));
		searchBox.setMaxLength(100);
		searchBox.setHint(Component.literal("Search a song or artist..."));
		addRenderableWidget(searchBox);
		addRenderableWidget(Button.builder(Component.literal("Play"), b -> {
			String q = searchBox.getValue().trim();
			if (!q.isEmpty()) tr.act(() -> api.searchAndPlay(q));
		}).bounds(left + 224, 146, 76, 20).build());
	}

	private void seekBy(long deltaMs) {
		SpotifyApi.Sample s = SpotifyLyricsMod.tracker().sample();
		if (s == null) return;
		long target = Math.max(0, Math.min(s.durationMs() - 500, s.estimatedProgressMs() + deltaMs));
		SpotifyLyricsMod.tracker().act(() -> SpotifyLyricsMod.api().seek(target));
	}

	private void initAppearance() {
		Config cfg = SpotifyLyricsMod.config();
		int y = 40;

		// font cycle (the label is drawn in the chosen font so it doubles as a preview)
		Fonts.FontChoice fc = Fonts.byId(cfg.font);
		addRenderableWidget(Button.builder(LyricsRenderer.styled("Font: " + fc.label() + "  (click for next)", fc, false), b -> {
			cfg.font = Fonts.next(cfg.font).id();
			cfg.save();
			this.rebuildWidgets();
		}).bounds(left, y, PANEL_W, 20).build());
		y += 30;

		y = colourRow(y, cfg.activeColor, c -> cfg.activeColor = c);
		y = colourRow(y, cfg.otherColor, c -> cfg.otherColor = c);

		addRenderableWidget(new Slider(left, y, 148, 20, (cfg.scale - 0.6) / 1.9,
				v -> "Text size: " + String.format("%.2fx", 0.6 + v * 1.9), v -> cfg.scale = (float) (0.6 + v * 1.9)));
		addRenderableWidget(new Slider(left + 152, y, 148, 20, cfg.hudBottomOffset / 300.0,
				v -> "Height: " + Math.round(v * 300) + "px", v -> cfg.hudBottomOffset = (int) Math.round(v * 300)));
		y += 24;

		addRenderableWidget(new Slider(left, y, 148, 20, (cfg.lyricsOffsetMs + 2000) / 4000.0,
				v -> "Timing: " + (Math.round(v * 4000) - 2000) + "ms", v -> cfg.lyricsOffsetMs = (int) Math.round(v * 4000) - 2000));
		addRenderableWidget(Button.builder(Component.literal("Lines shown: " + cfg.linesShown), b -> {
			cfg.linesShown = cfg.linesShown == 1 ? 3 : cfg.linesShown == 3 ? 5 : 1;
			this.rebuildWidgets();
		}).bounds(left + 152, y, 148, 20).build());
		y += 24;

		addRenderableWidget(Button.builder(Component.literal("Panel: " + PANEL_NAMES[cfg.panelStyle]), b -> {
			cfg.panelStyle = (cfg.panelStyle + 1) % 3;
			this.rebuildWidgets();
		}).bounds(left, y, 148, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Bold current: " + (cfg.boldActive ? "On" : "Off")), b -> {
			cfg.boldActive = !cfg.boldActive;
			this.rebuildWidgets();
		}).bounds(left + 152, y, 148, 20).build());
		y += 24;

		addRenderableWidget(Button.builder(Component.literal("Title: " + (cfg.showTitle ? "On" : "Off")), b -> {
			cfg.showTitle = !cfg.showTitle;
			this.rebuildWidgets();
		}).bounds(left, y, 98, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Progress: " + (cfg.showProgress ? "On" : "Off")), b -> {
			cfg.showProgress = !cfg.showProgress;
			this.rebuildWidgets();
		}).bounds(left + 101, y, 98, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Lyrics: " + (cfg.lyricsEnabled ? "On" : "Off")), b -> {
			cfg.lyricsEnabled = !cfg.lyricsEnabled;
			this.rebuildWidgets();
		}).bounds(left + 202, y, 98, 20).build());
	}

	/** A row of colour swatches plus a hex box. Returns the y for the next row. */
	private int colourRow(int y, int current, Consumer<Integer> setter) {
		int sy = y + 11;
		for (int i = 0; i < PALETTE.length; i++) {
			final int rgb = PALETTE[i];
			int sx = left + i * 16;
			addRenderableWidget(Button.builder(Component.empty(), b -> {
				setter.accept(rgb);
				SpotifyLyricsMod.config().save();
				this.rebuildWidgets();
			}).bounds(sx, sy, 14, 14).build());
			swatches.add(new int[]{sx, sy, rgb});
		}
		EditBox hex = new EditBox(this.font, left + 182, sy - 3, 62, 20, Component.literal("hex"));
		hex.setMaxLength(7);
		hex.setValue(String.format("#%06X", current & 0xFFFFFF));
		hex.setResponder(text -> {
			String t = text.startsWith("#") ? text.substring(1) : text;
			if (t.length() == 6) {
				try {
					setter.accept(Integer.parseInt(t, 16));
				} catch (NumberFormatException ignored) {
				}
			}
		});
		addRenderableWidget(hex);
		return y + 34;
	}

	private void initAccount() {
		Config cfg = SpotifyLyricsMod.config();
		EditBox id = new EditBox(this.font, left, 54, PANEL_W, 20, Component.literal("Client ID"));
		id.setMaxLength(64);
		id.setHint(Component.literal("Paste your Spotify Client ID here"));
		id.setValue(cfg.clientId == null ? "" : cfg.clientId);
		id.setResponder(t -> {
			cfg.clientId = t.trim();
			cfg.save();
		});
		addRenderableWidget(id);

		addRenderableWidget(Button.builder(Component.literal("Log in"), b -> {
			this.minecraft.setScreen(null);
			SpotifyLyricsMod.startLogin();
		}).bounds(left, 96, 148, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Log out"), b -> {
			SpotifyLyricsMod.auth().logout();
			this.rebuildWidgets();
		}).bounds(left + 152, 96, 148, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Copy redirect URI to clipboard"), b ->
				this.minecraft.keyboardHandler.setClipboard(SpotifyAuth.REDIRECT_URI))
				.bounds(left, 204, PANEL_W, 20).build());
	}

	// --------------------------------------------------------------- rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);

		for (int[] sw : swatches) {
			g.fill(sw[0] + 2, sw[1] + 2, sw[0] + 12, sw[1] + 12, 0xFF000000 | sw[2]);
		}

		Config cfg = SpotifyLyricsMod.config();
		if (tab == 0) {
			drawPlayerInfo(g);
		} else if (tab == 1) {
			g.text(this.font, "Line being sung", left, 71, 0xFF000000 | cfg.activeColor, true);
			g.text(this.font, "Previous / next lines", left, 105, 0xFF000000 | cfg.otherColor, true);
		} else {
			drawAccountInfo(g);
		}
	}

	private void drawPlayerInfo(GuiGraphicsExtractor g) {
		PlaybackTracker tr = SpotifyLyricsMod.tracker();
		SpotifyApi.Sample s = tr.sample();
		if (s == null) {
			String err = tr.lastError();
			g.text(this.font, SpotifyLyricsMod.auth().isLoggedIn()
					? (err != null ? err : "Nothing playing. Start a song in the Spotify app.")
					: "Not connected. Open the Account tab.", left, 44, 0xFFFFAA55, true);
		} else {
			g.text(this.font, s.title(), left, 42, 0xFFFFFFFF, true);
			g.text(this.font, s.artist() + (s.playing() ? "" : "  (paused)"), left, 54, 0xFFA0A0A0, true);
			float p = s.durationMs() > 0 ? (float) s.estimatedProgressMs() / s.durationMs() : 0f;
			g.fill(left, 70, left + PANEL_W, 74, 0x60FFFFFF);
			g.fill(left, 70, left + Math.round(PANEL_W * Math.max(0f, Math.min(1f, p))), 74,
					0xFF000000 | SpotifyLyricsMod.config().activeColor);
		}
		String msg = tr.message();
		if (!msg.isEmpty()) g.text(this.font, msg, left, 176, 0xFFAAAAAA, true);
		g.text(this.font, "Playback control needs Spotify Premium.", left, 190, 0xFF808080, true);
	}

	private void drawAccountInfo(GuiGraphicsExtractor g) {
		boolean in = SpotifyLyricsMod.auth().isLoggedIn();
		g.text(this.font, "Spotify Client ID", left, 42, 0xFFFFFFFF, true);
		g.text(this.font, in ? "Status: connected. Lyrics start by themselves whenever a song plays."
				: "Status: not connected", left, 82, in ? 0xFF1ED760 : 0xFFFFAA55, true);
		String[] help = {
				"1. developer.spotify.com/dashboard > Create app (Web API)",
				"2. Redirect URI: " + SpotifyAuth.REDIRECT_URI,
				"3. Paste its Client ID above, press Log in",
				"4. Approve in your browser. You only do this once."
		};
		for (int i = 0; i < help.length; i++) {
			g.text(this.font, help[i], left, 126 + i * 12, 0xFFA0A0A0, true);
		}
		g.text(this.font, "Dev Mode: add your own email under User Management.", left, 178, 0xFF808080, true);
	}

	// ------------------------------------------------------------------ widgets

	/** Slider that reports its value while dragging. */
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
