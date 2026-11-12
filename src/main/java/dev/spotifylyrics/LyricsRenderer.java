package dev.spotifylyrics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class LyricsRenderer {
	public record Model(String trackId, String header, List<String> before, String current, List<String> after,
						float progress, float anim) {
	}

	private record Row(Component text, int width, int rgb, float alpha, float yShift, boolean current) {
	}

	private static final int LINE_H = 13;
	private static final int PAD_X = 12;
	private static final int PAD_Y = 6;

	private LyricsRenderer() {}

	public static Component styled(String text, Fonts.FontChoice fc, boolean bold) {
		String transformed = fc != null ? Fonts.applyStyle(text, fc.transform()) : text;
		MutableComponent c = Component.literal(transformed);
		Style st = Style.EMPTY;
		if (fc != null && fc.fontId() != null) st = st.withFont(new FontDescription.Resource(fc.fontId()));
		if (bold) st = st.withBold(true);
		return c.withStyle(st);
	}

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

	private static void roundedRect(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
		g.fill(x1 + 2, y1, x2 - 2, y1 + 1, color);
		g.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, color);
		g.fill(x1, y1 + 2, x2, y2 - 2, color);
		g.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, color);
		g.fill(x1 + 2, y2 - 1, x2 - 2, y2, color);
	}

	public static void draw(GuiGraphicsExtractor g, Font font, Config cfg, int screenW, int screenH,
							int centerX, int bottomY, Model m) {
		float scale = cfg.scale;
		Fonts.FontChoice fc = Fonts.byId(cfg.font);
		int maxW = (int) Math.min((screenW - 40) / scale, 340);

		// Sky Horizon Mode: anchored at eye/horizon height in front of player; disappears when looking down
		if (cfg.skyHorizonMode) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				float pitch = mc.player.getXRot(); // 0 = looking straight at horizon, +90 = looking straight down
				if (pitch > 18f) return; // Hidden when looking down!
				bottomY = Math.round(screenH * (0.34f + (pitch / 90f) * 0.65f));
				bottomY = Mth.clamp(bottomY, 45, screenH - 20);
			}
		}

		List<Row> rows = new ArrayList<>();
		if (cfg.showTitle && m.header() != null && !m.header().isEmpty()) {
			Component c = fit(font, m.header(), null, false, maxW);
			rows.add(new Row(c, font.width(c), 0xD0C4FF, 0.85f, 0, false));
		}
		int ctx = (cfg.linesShown - 1) / 2;
		List<String> before = m.before();
		for (int i = Math.max(0, before.size() - ctx); i < before.size(); i++) {
			String s = before.get(i);
			if (s.isBlank()) continue;
			float a = i == before.size() - 1 ? 0.65f : 0.4f;
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
			float a = i == 0 ? 0.65f : 0.4f;
			Component c = fit(font, s, fc, false, maxW);
			rows.add(new Row(c, font.width(c), cfg.otherColor, a, 0, false));
		}

		// Scattered Mode: places each lyric line across varied screen coordinates
		if (cfg.scatteredMode) {
			for (int i = 0; i < rows.size(); i++) {
				Row r = rows.get(i);
				Random rnd = new Random((r.text().getString() + i).hashCode());
				int sx = Math.round(screenW * (0.22f + rnd.nextFloat() * 0.56f));
				int sy = Math.round(screenH * (0.16f + rnd.nextFloat() * 0.58f));
				if (cfg.skyHorizonMode) sy = Math.min(sy, Math.round(screenH * 0.45f));
				g.pose().pushMatrix();
				g.pose().translate((float) sx, (float) sy);
				g.pose().scale(scale, scale);
				if (cfg.panelStyle > 0) {
					roundedRect(g, -r.width() / 2 - 6, -3, r.width() / 2 + 6, LINE_H + 3,
							cfg.panelStyle == 1 ? 0x65160D2B : 0xD8160D2B);
				}
				g.text(font, r.text(), -r.width() / 2, 0, argb(r.rgb(), r.alpha()), true);
				g.pose().popMatrix();
			}
			return;
		}

		Identifier coverTex = cfg.showCoverArt ? CoverArtManager.textureIfReady(m.trackId()) : null;
		int coverSize = coverTex != null ? 36 : 0;
		int coverGap = coverTex != null ? 8 : 0;

		int widest = 100;
		int textH = 0;
		for (Row r : rows) {
			widest = Math.max(widest, r.width());
			textH += r.current() ? LINE_H + 4 : LINE_H;
		}
		boolean bar = cfg.showProgress && m.progress() >= 0f;
		int w = widest + PAD_X * 2 + coverSize + coverGap;
		int h = Math.max(textH, coverSize) + PAD_Y * 2 + (bar ? 5 : 0);

		g.pose().pushMatrix();
		g.pose().translate((float) centerX, (float) bottomY);
		g.pose().scale(scale, scale);

		int x1 = -w / 2, y1 = -h, x2 = x1 + w, y2 = 0;
		if (cfg.panelStyle == 1) roundedRect(g, x1, y1, x2, y2, 0x78140B29);
		else if (cfg.panelStyle == 2) roundedRect(g, x1, y1, x2, y2, 0xE5140B29);

		if (coverTex != null) {
			int cx = x1 + PAD_X;
			int cy = y1 + (h - (bar ? 5 : 0) - coverSize) / 2;
			g.fill(cx - 1, cy - 1, cx + coverSize + 1, cy + coverSize + 1, 0x90B388FF);
			g.blit(coverTex, cx, cy, 0, 0, coverSize, coverSize, coverSize, coverSize);
		}

		int textCenterX = (coverSize + coverGap) / 2;
		int y = y1 + PAD_Y;
		for (Row r : rows) {
			int rowH = r.current() ? LINE_H + 4 : LINE_H;
			int ty = y + (r.current() ? 2 : 0) + Math.round(r.yShift());
			g.text(font, r.text(), textCenterX - r.width() / 2, ty, argb(r.rgb(), r.alpha()), true);
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
