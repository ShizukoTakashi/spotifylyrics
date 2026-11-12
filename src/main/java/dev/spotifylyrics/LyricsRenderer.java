package dev.spotifylyrics;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

/** Draws the lyric block: optional rounded panel, title, previous / current / next lines and a progress bar. */
public final class LyricsRenderer {
	/** What to show. {@code anim} is 0..1 and drives the fade-in of the current line. */
	public record Model(String header, List<String> before, String current, List<String> after,
						float progress, float anim) {
	}

	private record Row(Component text, int width, int rgb, float alpha, float yShift, boolean current) {
	}

	private static final int LINE_H = 13;
	private static final int PAD_X = 12;
	private static final int PAD_Y = 6;

	private LyricsRenderer() {
	}

	public static Component styled(String text, Fonts.FontChoice fc, boolean bold) {
		MutableComponent c = Component.literal(text);
		Style st = Style.EMPTY;
		if (fc != null && fc.fontId() != null) st = st.withFont(new FontDescription.Resource(fc.fontId()));
		if (bold) st = st.withBold(true);
		return c.withStyle(st);
	}

	/** Shortens text with an ellipsis so it fits in maxW pixels in the chosen font. */
	private static Component fit(Font font, String s, Fonts.FontChoice fc, boolean bold, int maxW) {
		Component full = styled(s, fc, bold);
		if (font.width(full) <= maxW) return full;
		int lo = 0, hi = s.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (font.width(styled(s.substring(0, mid) + "…", fc, bold)) <= maxW) lo = mid;
			else hi = mid - 1;
		}
		return styled(s.substring(0, lo).stripTrailing() + "…", fc, bold);
	}

	private static int argb(int rgb, float alpha) {
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		return (a << 24) | (rgb & 0xFFFFFF);
	}

	/** Pixel-rounded rectangle without overlapping fills (so translucent colours stay even). */
	private static void roundedRect(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
		g.fill(x1 + 2, y1, x2 - 2, y1 + 1, color);
		g.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, color);
		g.fill(x1, y1 + 2, x2, y2 - 2, color);
		g.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, color);
		g.fill(x1 + 2, y2 - 1, x2 - 2, y2, color);
	}

	/**
	 * @param centerX horizontal centre of the block in screen pixels
	 * @param bottomY y of the block's bottom edge in screen pixels
	 */
	public static void draw(GuiGraphicsExtractor g, Font font, Config cfg, int screenW,
							int centerX, int bottomY, Model m) {
		float scale = cfg.scale;
		Fonts.FontChoice fc = Fonts.byId(cfg.font);
		int maxW = (int) Math.min((screenW - 30) / scale, 340);

		List<Row> rows = new ArrayList<>();
		if (cfg.showTitle && m.header() != null && !m.header().isEmpty()) {
			Component c = fit(font, m.header(), null, false, maxW);
			rows.add(new Row(c, font.width(c), 0xB0B0B0, 0.75f, 0, false));
		}
		int ctx = (cfg.linesShown - 1) / 2;
		List<String> before = m.before();
		for (int i = Math.max(0, before.size() - ctx); i < before.size(); i++) {
			String s = before.get(i);
			if (s.isBlank()) continue;
			float a = i == before.size() - 1 ? 0.6f : 0.35f;
			Component c = fit(font, s, fc, false, maxW);
			rows.add(new Row(c, font.width(c), cfg.otherColor, a, 0, false));
		}
		float t = Math.max(0f, Math.min(1f, m.anim()));
		float eased = 1f - (1f - t) * (1f - t);
		{
			String cur = m.current() == null || m.current().isBlank() ? "♪" : m.current();
			Component c = fit(font, cur, fc, cfg.boldActive, maxW);
			rows.add(new Row(c, font.width(c), cfg.activeColor, 0.35f + 0.65f * eased, (1f - eased) * 5f, true));
		}
		List<String> after = m.after();
		for (int i = 0; i < Math.min(ctx, after.size()); i++) {
			String s = after.get(i);
			if (s.isBlank()) continue;
			float a = i == 0 ? 0.6f : 0.35f;
			Component c = fit(font, s, fc, false, maxW);
			rows.add(new Row(c, font.width(c), cfg.otherColor, a, 0, false));
		}

		int widest = 100;
		int textH = 0;
		for (Row r : rows) {
			widest = Math.max(widest, r.width());
			textH += r.current() ? LINE_H + 4 : LINE_H;
		}
		boolean bar = cfg.showProgress && m.progress() >= 0f;
		int w = widest + PAD_X * 2;
		int h = textH + PAD_Y * 2 + (bar ? 5 : 0);

		g.pose().pushMatrix();
		g.pose().translate((float) centerX, (float) bottomY);
		g.pose().scale(scale, scale);

		int x1 = -w / 2, y1 = -h, x2 = x1 + w, y2 = 0;
		if (cfg.panelStyle == 1) roundedRect(g, x1, y1, x2, y2, 0x70000000);
		else if (cfg.panelStyle == 2) roundedRect(g, x1, y1, x2, y2, 0xE0121212);

		int y = y1 + PAD_Y;
		for (Row r : rows) {
			int rowH = r.current() ? LINE_H + 4 : LINE_H;
			int ty = y + (r.current() ? 2 : 0) + Math.round(r.yShift());
			g.text(font, r.text(), -r.width() / 2, ty, argb(r.rgb(), r.alpha()), true);
			y += rowH;
		}

		if (bar) {
			int bx1 = x1 + PAD_X, bx2 = x2 - PAD_X, by = y2 - PAD_Y + 1;
			g.fill(bx1, by, bx2, by + 2, 0x40FFFFFF);
			int fillTo = bx1 + Math.round((bx2 - bx1) * Math.max(0f, Math.min(1f, m.progress())));
			if (fillTo > bx1) g.fill(bx1, by, fillTo, by + 2, argb(cfg.activeColor, 1f));
		}

		g.pose().popMatrix();
	}
}
