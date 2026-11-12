package dev.spotifylyrics;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.Random;

/** Renders custom 4K-style space & pink-orange sunset sky backdrops without sun glare. */
public final class CustomSkyRenderer {
	public static final String[] SKY_IDS = {
			"vanilla", "saturn", "black_hole", "planets", "starfield", "pink_sunset"
	};
	public static final String[] SKY_NAMES = {
			"Vanilla Sky", "Saturn Space (4K)", "Black Hole Space", "Planets & Stars", "Deep Starfield", "Pink-Orange Sunset"
	};

	private static final float[] STAR_X = new float[450];
	private static final float[] STAR_Y = new float[450];
	private static final float[] STAR_Z = new float[450];
	private static final int[] STAR_COL = new int[450];
	private static final int[] STAR_SIZE = new int[450];

	static {
		Random r = new Random(42069L);
		int[] tints = {0xFFFFFFFF, 0xFFD8E8FF, 0xFFFFE6CC, 0xFFE5CCFF, 0xFFB8F2FF};
		for (int i = 0; i < STAR_X.length; i++) {
			double theta = r.nextDouble() * Math.PI * 2.0;
			double phi = Math.acos(2.0 * r.nextDouble() - 1.0);
			STAR_X[i] = (float) (Math.sin(phi) * Math.cos(theta));
			STAR_Y[i] = (float) Math.abs(Math.cos(phi)) * 0.96f + 0.04f;
			STAR_Z[i] = (float) (Math.sin(phi) * Math.sin(theta));
			STAR_COL[i] = tints[r.nextInt(tints.length)];
			STAR_SIZE[i] = r.nextFloat() > 0.85f ? 2 : 1;
		}
	}

	private CustomSkyRenderer() {}

	public static String labelFor(String id) {
		for (int i = 0; i < SKY_IDS.length; i++) {
			if (SKY_IDS[i].equals(id)) return SKY_NAMES[i];
		}
		return SKY_NAMES[0];
	}

	public static String nextSky(String id) {
		for (int i = 0; i < SKY_IDS.length; i++) {
			if (SKY_IDS[i].equals(id)) return SKY_IDS[(i + 1) % SKY_IDS.length];
		}
		return SKY_IDS[1];
	}

	/** Draws sky backdrop & horizon lyrics in HUD layer when looking up at the sky. */
	public static void renderSkyOverlay(GuiGraphicsExtractor g, int w, int h) {
		Config cfg = SpotifyLyricsMod.config();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;

		float pitch = mc.player.getXRot(); // -90 = straight up, 0 = horizon, +90 = straight down
		float yaw = Mth.wrapDegrees(mc.player.getYRot());
		String mode = cfg.skyTheme;

		if (!"vanilla".equals(mode) && pitch < 28f) {
			float skyVis = Mth.clamp((28f - pitch) / 32f, 0f, 1f);
			int horizonY = Math.round(h * (0.52f + (pitch / 90f) * 0.65f));
			horizonY = Mth.clamp(horizonY, 0, h);

			if ("pink_sunset".equals(mode)) {
				drawPinkSunset(g, w, h, horizonY, skyVis, yaw);
			} else {
				drawSpaceSky(g, w, h, horizonY, skyVis, yaw, pitch, mode);
			}
		}
	}

	private static void drawPinkSunset(GuiGraphicsExtractor g, int w, int h, int horizonY, float vis, float yaw) {
		int bands = 24;
		int maxY = Math.min(h, horizonY + 30);
		if (maxY <= 0) return;
		for (int i = 0; i < bands; i++) {
			float t = (float) i / bands;
			int y1 = Math.round(t * maxY);
			int y2 = Math.round(((float) (i + 1) / bands) * maxY);
			int col = lerpColor(0x2A1848, 0xFF5E7E, t < 0.55f ? t / 0.55f : 1f);
			if (t >= 0.55f) col = lerpColor(0xFF5E7E, 0xFF9E44, (t - 0.55f) / 0.45f);
			int a = Math.round(vis * 215);
			g.fill(0, y1, w, y2, (a << 24) | (col & 0xFFFFFF));
		}
		// Soft sunset clouds (no sun!)
		long time = System.currentTimeMillis() / 120L;
		for (int c = 0; c < 14; c++) {
			int cx = Math.floorMod((int) (c * 190 - yaw * 4.5f + (time % 2000)), w + 240) - 120;
			int cy = (int) (maxY * (0.28f + (c % 5) * 0.12f));
			int cw = 90 + (c % 4) * 35;
			int ch = 10 + (c % 3) * 4;
			int cloudCol = c % 2 == 0 ? 0xFFFF9EC4 : 0xFFFFB87A;
			int a = Math.round(vis * 95);
			g.fill(cx, cy, cx + cw, cy + ch, (a << 24) | (cloudCol & 0xFFFFFF));
			g.fill(cx + 14, cy - 5, cx + cw - 18, cy, (a << 24) | (cloudCol & 0xFFFFFF));
		}
	}

	private static void drawSpaceSky(GuiGraphicsExtractor g, int w, int h, int horizonY, float vis,
									 float yaw, float pitch, String mode) {
		int maxY = Math.min(h, horizonY + 24);
		if (maxY <= 0) return;
		int topCol = "black_hole".equals(mode) ? 0x05030C : 0x040714;
		int botCol = "saturn".equals(mode) ? 0x161130 : "planets".equals(mode) ? 0x131C3A : 0x0B1026;
		int bands = 18;
		for (int i = 0; i < bands; i++) {
			float t = (float) i / bands;
			int y1 = Math.round(t * maxY);
			int y2 = Math.round(((float) (i + 1) / bands) * maxY);
			int col = lerpColor(topCol, botCol, t);
			int a = Math.round(vis * 235);
			g.fill(0, y1, w, y2, (a << 24) | (col & 0xFFFFFF));
		}

		// Crisp 4K starfield
		float yawRad = (float) Math.toRadians(yaw);
		float cosY = Mth.cos(yawRad), sinY = Mth.sin(yawRad);
		long twinkle = System.currentTimeMillis() / 350L;
		for (int i = 0; i < STAR_X.length; i++) {
			float rx = STAR_X[i] * cosY - STAR_Z[i] * sinY;
			float rz = STAR_X[i] * sinY + STAR_Z[i] * cosY;
			if (rz <= 0.05f) continue;
			int sx = Math.round(w * 0.5f + (rx / rz) * (w * 0.55f));
			int sy = Math.round(maxY * (1f - STAR_Y[i] * 0.92f));
			if (sx < 0 || sx >= w || sy < 0 || sy >= maxY) continue;
			float pulse = ((i + twinkle) % 7 == 0) ? 0.55f : 1f;
			int a = Math.round(vis * 240 * pulse);
			int sz = STAR_SIZE[i];
			g.fill(sx, sy, sx + sz, sy + sz, (a << 24) | (STAR_COL[i] & 0xFFFFFF));
		}

		int objX = Math.floorMod((int) (w * 0.5f - yaw * 5.0f), w + 200) - 100;
		int objY = Math.round(maxY * 0.36f);
		int a = Math.round(vis * 255);

		if ("saturn".equals(mode)) {
			// Saturn planet body + golden-cream concentric rings
			drawDisc(g, objX, objY, 34, (a << 24) | 0xE8C88B);
			drawDisc(g, objX - 4, objY - 4, 28, (a << 24) | 0xF5DCA8);
			for (int r = 42; r <= 68; r += 3) {
				int ringCol = (r == 54 || r == 57) ? 0x40201810 : ((a << 24) | (r % 6 == 0 ? 0xD9B97E : 0xB89860));
				drawEllipseRing(g, objX, objY, r, r / 3, ringCol);
			}
		} else if ("black_hole".equals(mode)) {
			// Glowing orange-gold accretion disk + event horizon shadow
			for (int r = 74; r >= 34; r -= 2) {
				int c = lerpColor(0xFF4500, 0xFFE89E, (74 - r) / 40f);
				drawEllipseRing(g, objX, objY, r, r / 4, (a << 24) | c);
			}
			drawDisc(g, objX, objY, 32, (a << 24) | 0xFF9A3C);
			drawDisc(g, objX, objY, 27, (a << 24) | 0x020105);
		} else if ("planets".equals(mode)) {
			drawDisc(g, objX, objY, 26, (a << 24) | 0x4C8EFF);
			drawDisc(g, objX - 5, objY - 5, 19, (a << 24) | 0x7AB4FF);
			int p2x = (objX + 140) % Math.max(1, w);
			drawDisc(g, p2x, objY + 24, 15, (a << 24) | 0xE06D53);
			int p3x = Math.floorMod(objX - 130, Math.max(1, w));
			drawDisc(g, p3x, objY - 18, 11, (a << 24) | 0xB983FF);
		}
	}

	private static void drawDisc(GuiGraphicsExtractor g, int cx, int cy, int r, int argb) {
		for (int dy = -r; dy <= r; dy++) {
			int dx = (int) Math.sqrt(r * r - dy * dy);
			g.fill(cx - dx, cy + dy, cx + dx, cy + dy + 1, argb);
		}
	}

	private static void drawEllipseRing(GuiGraphicsExtractor g, int cx, int cy, int rx, int ry, int argb) {
		for (int deg = 0; deg < 360; deg += 4) {
			double rad = Math.toRadians(deg);
			int x = cx + (int) Math.round(Math.cos(rad) * rx);
			int y = cy + (int) Math.round(Math.sin(rad) * ry);
			g.fill(x - 1, y - 1, x + 2, y + 1, argb);
		}
	}

	private static int lerpColor(int c1, int c2, float t) {
		t = Mth.clamp(t, 0f, 1f);
		int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
		int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
		int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
		return (r << 16) | (g << 8) | b;
	}
}
