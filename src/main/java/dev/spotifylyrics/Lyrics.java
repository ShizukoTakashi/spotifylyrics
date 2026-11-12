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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-synced lyrics.
 * <p>
 * Spotify has no public lyrics endpoint, so lyrics come from LRCLIB (https://lrclib.net),
 * a free community database that needs no API key. Songs missing from it simply show no lyrics.
 */
public final class Lyrics {
	public record Line(long timeMs, String text) {
	}

	public enum Status {LOADING, SYNCED, NOT_FOUND, INSTRUMENTAL, ERROR}

	public final String trackId;
	public final Status status;
	public final List<Line> lines;

	private Lyrics(String trackId, Status status, List<Line> lines) {
		this.trackId = trackId;
		this.status = status;
		this.lines = lines;
	}

	public static Lyrics loading(String trackId) {
		return new Lyrics(trackId, Status.LOADING, List.of());
	}

	/** Index of the line being sung at posMs, or -1 before the first line. */
	public int indexAt(long posMs) {
		int lo = 0, hi = lines.size() - 1, ans = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (lines.get(mid).timeMs() <= posMs) {
				ans = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return ans;
	}

	// ------------------------------------------------------------- fetching

	private static final Pattern TS = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");

	public static Lyrics fetch(HttpClient http, SpotifyApi.Sample s) {
		try {
			// 1) exact match using title/artist/album/duration
			String url = "https://lrclib.net/api/get?track_name=" + enc(s.title())
					+ "&artist_name=" + enc(firstArtist(s.artist()))
					+ "&album_name=" + enc(s.album())
					+ "&duration=" + Math.round(s.durationMs() / 1000.0);
			HttpResponse<String> res = get(http, url);
			if (res.statusCode() == 200) {
				Lyrics l = fromJson(s.trackId(), JsonParser.parseString(res.body()).getAsJsonObject());
				if (l != null) return l;
			}

			// 2) looser search, take first result that has synced lyrics
			String surl = "https://lrclib.net/api/search?track_name=" + enc(s.title())
					+ "&artist_name=" + enc(firstArtist(s.artist()));
			res = get(http, surl);
			if (res.statusCode() == 200) {
				JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
				Lyrics best = null;
				long bestDiff = Long.MAX_VALUE;
				for (JsonElement e : arr) {
					JsonObject o = e.getAsJsonObject();
					Lyrics l = fromJson(s.trackId(), o);
					if (l == null || l.status != Status.SYNCED) continue;
					double d = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble() : 0;
					long diff = Math.abs(Math.round(d * 1000) - s.durationMs());
					if (diff < bestDiff) {
						bestDiff = diff;
						best = l;
					}
				}
				if (best != null && bestDiff < 10_000) return best;
			}
			return new Lyrics(s.trackId(), Status.NOT_FOUND, List.of());
		} catch (Exception e) {
			SpotifyLyricsMod.LOGGER.warn("Lyrics lookup failed", e);
			return new Lyrics(s.trackId(), Status.ERROR, List.of());
		}
	}

	private static Lyrics fromJson(String trackId, JsonObject o) {
		if (o.has("instrumental") && o.get("instrumental").getAsBoolean()) {
			return new Lyrics(trackId, Status.INSTRUMENTAL, List.of());
		}
		if (!o.has("syncedLyrics") || o.get("syncedLyrics").isJsonNull()) return null;
		List<Line> lines = parseLrc(o.get("syncedLyrics").getAsString());
		if (lines.isEmpty()) return null;
		return new Lyrics(trackId, Status.SYNCED, lines);
	}

	static List<Line> parseLrc(String lrc) {
		List<Line> out = new ArrayList<>();
		for (String raw : lrc.split("\\R")) {
			Matcher m = TS.matcher(raw);
			List<Long> times = new ArrayList<>();
			int end = 0;
			while (m.find() && m.start() == end) {
				long min = Long.parseLong(m.group(1));
				long sec = Long.parseLong(m.group(2));
				long ms = 0;
				if (m.group(3) != null) {
					String f = m.group(3);
					ms = f.length() == 1 ? Long.parseLong(f) * 100
							: f.length() == 2 ? Long.parseLong(f) * 10 : Long.parseLong(f);
				}
				times.add(min * 60_000 + sec * 1000 + ms);
				end = m.end();
			}
			if (times.isEmpty()) continue;
			String text = raw.substring(end).trim();
			for (long t : times) out.add(new Line(t, text));
		}
		out.sort(Comparator.comparingLong(Line::timeMs));
		return out;
	}

	private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", "SpotifyLyricsHUD/0.1 (Minecraft Fabric mod)")
				.GET().build();
		return http.send(req, HttpResponse.BodyHandlers.ofString());
	}

	private static String firstArtist(String artists) {
		int i = artists.indexOf(',');
		return i < 0 ? artists : artists.substring(0, i);
	}

	private static String enc(String s) {
		return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
	}
}
