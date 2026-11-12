package dev.spotifylyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CoverArtManager {
	private static final Identifier TEX_ID = Identifier.fromNamespaceAndPath(SpotifyLyricsMod.MOD_ID, "album_cover");
	private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "spotifylyrics-cover");
		t.setDaemon(true);
		return t;
	});

	private static volatile String loadedTrackId = "";
	private static volatile boolean ready = false;
	private static DynamicTexture texture;

	private CoverArtManager() {}

	public static Identifier textureIfReady(String trackId) {
		if (trackId == null || trackId.isBlank()) return null;
		if (!trackId.equals(loadedTrackId)) {
			loadedTrackId = trackId;
			ready = false;
			SpotifyApi.Sample s = SpotifyLyricsMod.tracker() != null ? SpotifyLyricsMod.tracker().sample() : null;
			if (s != null) EXEC.execute(() -> loadAsync(s));
		}
		return ready ? TEX_ID : null;
	}

	private static void loadAsync(SpotifyApi.Sample s) {
		try {
			byte[] raw = null;
			if (s.artBase64() != null && !s.artBase64().isBlank()) {
				raw = Base64.getDecoder().decode(s.artBase64());
			}
			if (raw == null || raw.length < 64) {
				raw = fetchItunesArt(s.title(), s.artist());
			}
			if (raw == null || raw.length < 64) return;

			BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
			if (src == null) return;
			BufferedImage scaled = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2 = scaled.createGraphics();
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2.drawImage(src, 0, 0, 128, 128, null);
			g2.dispose();

			ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
			ImageIO.write(scaled, "PNG", pngOut);
			byte[] pngBytes = pngOut.toByteArray();

			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> {
				try {
					if (!s.trackId().equals(loadedTrackId)) return;
					NativeImage img = NativeImage.read(new ByteArrayInputStream(pngBytes));
					if (texture != null) texture.close();
					texture = new DynamicTexture(() -> "spotifylyrics_cover", img);
					mc.getTextureManager().register(TEX_ID, texture);
					ready = true;
				} catch (Exception ignored) {}
			});
		} catch (Exception ignored) {}
	}

	private static byte[] fetchItunesArt(String title, String artist) {
		try {
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
			String q = URLEncoder.encode(artist + " " + title, StandardCharsets.UTF_8);
			HttpRequest req = HttpRequest.newBuilder(URI.create("https://apple.com" + q + "&entity=song&limit=1"))
					.timeout(Duration.ofSeconds(6)).GET().build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) return null;
			JsonArray results = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("results");
			if (results == null || results.isEmpty()) return null;
			JsonObject first = results.get(0).getAsJsonObject();
			if (!first.has("artworkUrl100")) return null;
			String artUrl = first.get("artworkUrl100").getAsString().replace("100x100bb", "300x300bb");
			HttpResponse<byte[]> imgRes = http.send(HttpRequest.newBuilder(URI.create(artUrl)).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
			return imgRes.statusCode() == 200 ? imgRes.body() : null;
		} catch (Exception e) {
			return null;
		}
	}
}
