package dev.spotifylyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** Thin wrapper around the Spotify Web API player endpoints (needs Spotify Premium to control playback). */
public final class SpotifyApi {
	private static final String BASE = "https://api.spotify.com/v1";

	public record Result(boolean ok, String message) {
		static Result ok(String m) {
			return new Result(true, m);
		}

		static Result fail(String m) {
			return new Result(false, m);
		}
	}

	/** One reading of the player state. */
	public record Sample(String trackId, String title, String artist, String album,
						 long durationMs, long progressMs, boolean playing, long takenAtNanos,
						 int volumePercent) {
		public long estimatedProgressMs() {
			if (!playing) return progressMs;
			return Math.min(durationMs, progressMs + (System.nanoTime() - takenAtNanos) / 1_000_000L);
		}
	}

	private final SpotifyAuth auth;
	private final HttpClient http;

	public SpotifyApi(SpotifyAuth auth, HttpClient http) {
		this.auth = auth;
		this.http = http;
	}

	/** Empty when nothing is playing / no active device. */
	public Optional<Sample> currentPlayback() throws Exception {
		HttpResponse<String> res = send("GET", "/me/player", null);
		if (res.statusCode() == 204 || res.body() == null || res.body().isBlank()) return Optional.empty();
		if (res.statusCode() / 100 != 2) throw new IllegalStateException(describe(res));
		JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
		JsonElement item = o.get("item");
		if (item == null || item.isJsonNull() || !item.getAsJsonObject().has("id")
				|| item.getAsJsonObject().get("id").isJsonNull()) {
			return Optional.empty(); // ads, podcasts without id, local files
		}
		JsonObject t = item.getAsJsonObject();
		StringBuilder artists = new StringBuilder();
		JsonArray arr = t.has("artists") ? t.getAsJsonArray("artists") : new JsonArray();
		for (JsonElement a : arr) {
			if (artists.length() > 0) artists.append(", ");
			artists.append(a.getAsJsonObject().get("name").getAsString());
		}
		String album = t.has("album") && t.get("album").isJsonObject()
				? t.getAsJsonObject("album").get("name").getAsString() : "";
		long progress = o.has("progress_ms") && !o.get("progress_ms").isJsonNull() ? o.get("progress_ms").getAsLong() : 0;
		boolean playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();
		int volume = -1;
		if (o.has("device") && o.get("device").isJsonObject()) {
			JsonObject d = o.getAsJsonObject("device");
			if (d.has("volume_percent") && !d.get("volume_percent").isJsonNull()) volume = d.get("volume_percent").getAsInt();
		}
		return Optional.of(new Sample(t.get("id").getAsString(), t.get("name").getAsString(), artists.toString(),
				album, t.get("duration_ms").getAsLong(), progress, playing, System.nanoTime(), volume));
	}

	public Result seek(long positionMs) throws Exception {
		return command("PUT", "/me/player/seek?position_ms=" + Math.max(0, positionMs), null, "Seeked");
	}

	public Result pause() throws Exception {
		return command("PUT", "/me/player/pause", null, "Paused");
	}

	public Result resume() throws Exception {
		return command("PUT", "/me/player/play", null, "Resumed");
	}

	public Result next() throws Exception {
		return command("POST", "/me/player/next", null, "Skipped to next track");
	}

	public Result previous() throws Exception {
		return command("POST", "/me/player/previous", null, "Previous track");
	}

	public Result volume(int percent) throws Exception {
		int v = Math.max(0, Math.min(100, percent));
		return command("PUT", "/me/player/volume?volume_percent=" + v, null, "Volume " + v + "%");
	}

	/** Searches for a track and starts playing the best match on the active device. */
	public Result searchAndPlay(String query) throws Exception {
		String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
		HttpResponse<String> res = send("GET", "/search?type=track&limit=1&q=" + q, null);
		if (res.statusCode() / 100 != 2) return Result.fail(describe(res));
		JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
		JsonArray items = o.getAsJsonObject("tracks").getAsJsonArray("items");
		if (items.isEmpty()) return Result.fail("No track found for \"" + query + "\"");
		JsonObject t = items.get(0).getAsJsonObject();
		String uri = t.get("uri").getAsString();
		String name = t.get("name").getAsString();
		String artist = t.getAsJsonArray("artists").get(0).getAsJsonObject().get("name").getAsString();
		JsonObject body = new JsonObject();
		JsonArray uris = new JsonArray();
		uris.add(uri);
		body.add("uris", uris);
		return command("PUT", "/me/player/play", body.toString(), "Now playing: " + name + " - " + artist);
	}

	// ------------------------------------------------------------------ http

	private Result command(String method, String path, String body, String okMessage) throws Exception {
		HttpResponse<String> res = send(method, path, body);
		if (res.statusCode() / 100 == 2) return Result.ok(okMessage);
		return Result.fail(describe(res));
	}

	private HttpResponse<String> send(String method, String path, String body) throws Exception {
		HttpResponse<String> res = once(method, path, body, auth.accessToken());
		if (res.statusCode() == 401) {
			auth.forceRefresh();
			res = once(method, path, body, auth.accessToken());
		}
		if (res.statusCode() == 429) {
			SpotifyLyricsMod.LOGGER.warn("Spotify rate limit hit (Retry-After {}s)",
					res.headers().firstValue("Retry-After").orElse("?"));
		}
		return res;
	}

	private HttpResponse<String> once(String method, String path, String body, String token) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Bearer " + token);
		if (body != null) {
			b.header("Content-Type", "application/json");
			b.method(method, HttpRequest.BodyPublishers.ofString(body));
		} else {
			b.method(method, HttpRequest.BodyPublishers.noBody());
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String describe(HttpResponse<String> res) {
		int code = res.statusCode();
		String detail = "";
		try {
			JsonObject err = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("error");
			if (err != null && err.has("message")) detail = err.get("message").getAsString();
			if (err != null && err.has("reason")) detail = err.get("reason").getAsString();
		} catch (Exception ignored) {
		}
		if (code == 404 || "NO_ACTIVE_DEVICE".equals(detail)) {
			return "No active Spotify device. Open Spotify on your PC/phone and press play once, then try again.";
		}
		if (code == 403) {
			return "Spotify refused this (" + (detail.isBlank() ? "403" : detail)
					+ "). Playback control needs Premium, and in Development Mode your account must be added under User Management.";
		}
		if (code == 429) return "Spotify rate limit hit. Wait a few seconds.";
		return "Spotify error " + code + (detail.isBlank() ? "" : ": " + detail);
	}
}
