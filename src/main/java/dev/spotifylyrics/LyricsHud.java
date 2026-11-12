package dev.spotifylyrics;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

public final class LyricsHud {
	private static String animTrack = "";
	private static int animIndex = -2;
	private static long animChangedAt;

	private LyricsHud() {}

	public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		int screenW = mc.getWindow().getGuiScaledWidth();
		int screenH = mc.getWindow().getGuiScaledHeight();

		CustomSkyRenderer.renderSkyOverlay(g, screenW, screenH);

		Config cfg = SpotifyLyricsMod.config();
		PlaybackTracker tracker = SpotifyLyricsMod.tracker();
		if (!cfg.lyricsEnabled || tracker == null) return;

		SpotifyApi.Sample s = tracker.sample();
		if (s == null) return;

		String header = s.title() + " – " + s.artist() + (s.playing() ? "" : "  ⏸");
		float progress = s.durationMs() > 0 ? (float) s.estimatedProgressMs() / s.durationMs() : -1f;

		List<String> before = new ArrayList<>();
		List<String> after = new ArrayList<>();
		String current;
		int index = -1;

		Lyrics lyr = tracker.lyrics();
		if (lyr == null || lyr.status == Lyrics.Status.LOADING) {
			current = "Loading lyrics…";
		} else if (lyr.status == Lyrics.Status.SYNCED) {
			long pos = s.estimatedProgressMs() + cfg.lyricsOffsetMs;
			index = lyr.indexAt(pos);
			for (int i = Math.max(0, index - 2); i < index; i++) before.add(lyr.lines.get(i).text());
			current = index >= 0 ? lyr.lines.get(index).text() : "";
			for (int i = index + 1; i < Math.min(lyr.lines.size(), index + 3); i++) after.add(lyr.lines.get(i).text());
		} else if (lyr.status == Lyrics.Status.INSTRUMENTAL) {
			current = "♪ Instrumental ♪";
		} else if (lyr.status == Lyrics.Status.NOT_FOUND) {
			current = "No synced lyrics found for this song";
		} else {
			current = "Lyrics unavailable right now";
		}

		long now = System.currentTimeMillis();
		if (!animTrack.equals(s.trackId()) || animIndex != index) {
			animTrack = s.trackId();
			animIndex = index;
			animChangedAt = now;
		}
		float anim = Math.min(1f, (now - animChangedAt) / 260f);

		LyricsRenderer.Model model = new LyricsRenderer.Model(s.trackId(), header, before, current, after, progress, anim);
		LyricsRenderer.draw(g, mc.font, cfg, screenW, screenH, screenW / 2, screenH - cfg.hudBottomOffset, model);
	}
}
