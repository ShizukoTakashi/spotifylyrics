package dev.spotifylyrics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Spotify "Authorization Code with PKCE" login using a loopback redirect.
 * No client secret is needed or stored. Uses a plain ServerSocket instead of
 * com.sun.net.httpserver because Minecraft's bundled Java runtime may not include it.
 */
public final class SpotifyAuth {
	public static final int PORT = 8888;
	public static final String REDIRECT_URI = "http://127.0.0.1:" + PORT + "/callback";
	private static final String SCOPES = "user-read-playback-state user-modify-playback-state";
	private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";

	private final Config config;
	private final HttpClient http;
	private final Gson gson = new Gson();

	private String accessToken;
	private String refreshToken;
	private long expiresAtMs;

	public SpotifyAuth(Config config, HttpClient http) {
		this.config = config;
		this.http = http;
		loadTokens();
	}

	public synchronized boolean isLoggedIn() {
		return refreshToken != null && !refreshToken.isBlank();
	}

	public synchronized void logout() {
		accessToken = null;
		refreshToken = null;
		expiresAtMs = 0;
		try {
			Files.deleteIfExists(tokenPath());
		} catch (Exception ignored) {
		}
	}

	/** Returns a valid access token, refreshing it if needed. Throws if not logged in. */
	public synchronized String accessToken() throws Exception {
		if (!isLoggedIn()) throw new IllegalStateException("Not logged in. Use /spotify login");
		if (accessToken == null || System.currentTimeMillis() > expiresAtMs - 30_000) {
			refresh();
		}
		return accessToken;
	}

	public synchronized void forceRefresh() throws Exception {
		refresh();
	}

	// ---------------------------------------------------------------- login

	/**
	 * Blocks (call off the main thread). Calls onUrl with the page the player must open,
	 * then waits up to 3 minutes for Spotify to redirect back to the loopback address.
	 */
	public void login(Consumer<URI> onUrl) throws Exception {
		String clientId = config.clientId;
		if (clientId == null || clientId.isBlank()) {
			throw new IllegalStateException("Set your Client ID first: /spotify clientid <id>");
		}

		SecureRandom rnd = new SecureRandom();
		byte[] vb = new byte[64];
		rnd.nextBytes(vb);
		String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(vb);
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
		byte[] sb = new byte[16];
		rnd.nextBytes(sb);
		String state = Base64.getUrlEncoder().withoutPadding().encodeToString(sb);

		URI authUri = URI.create("https://accounts.spotify.com/authorize"
				+ "?client_id=" + enc(clientId)
				+ "&response_type=code"
				+ "&redirect_uri=" + enc(REDIRECT_URI)
				+ "&scope=" + enc(SCOPES)
				+ "&code_challenge_method=S256"
				+ "&code_challenge=" + challenge
				+ "&state=" + state);

		try (ServerSocket server = new ServerSocket(PORT, 5, InetAddress.getByName("127.0.0.1"))) {
			server.setSoTimeout(5_000);
			onUrl.accept(authUri);

			long deadline = System.currentTimeMillis() + 180_000;
			String code = null;
			while (code == null) {
				if (System.currentTimeMillis() > deadline) {
					throw new IllegalStateException("Login timed out. Run /spotify login again.");
				}
				Socket s;
				try {
					s = server.accept();
				} catch (java.net.SocketTimeoutException e) {
					continue;
				}
				try (s) {
					s.setSoTimeout(5_000);
					BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
					String requestLine = in.readLine();
					if (requestLine == null) continue;
					String[] parts = requestLine.split(" ");
					String target = parts.length > 1 ? parts[1] : "";
					if (!target.startsWith("/callback")) {
						respond(s, 404, "Not found");
						continue;
					}
					Map<String, String> q = parseQuery(target);
					if (q.containsKey("error")) {
						respond(s, 400, "Spotify login was cancelled. You can close this tab.");
						throw new IllegalStateException("Spotify login failed: " + q.get("error"));
					}
					if (!state.equals(q.get("state")) || q.get("code") == null) {
						respond(s, 400, "Invalid login response. You can close this tab.");
						throw new IllegalStateException("Login response did not match (state check failed).");
					}
					code = q.get("code");
					respond(s, 200, "Spotify is connected to Minecraft. You can close this tab and go back to the game.");
				}
			}

			Map<String, String> form = new HashMap<>();
			form.put("grant_type", "authorization_code");
			form.put("code", code);
			form.put("redirect_uri", REDIRECT_URI);
			form.put("client_id", clientId);
			form.put("code_verifier", verifier);
			applyTokenResponse(postForm(form));
		}
	}

	// -------------------------------------------------------------- internals

	private void refresh() throws Exception {
		Map<String, String> form = new HashMap<>();
		form.put("grant_type", "refresh_token");
		form.put("refresh_token", refreshToken);
		form.put("client_id", config.clientId);
		applyTokenResponse(postForm(form));
	}

	private JsonObject postForm(Map<String, String> form) throws Exception {
		StringBuilder body = new StringBuilder();
		for (Map.Entry<String, String> e : form.entrySet()) {
			if (body.length() > 0) body.append('&');
			body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
		}
		HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() / 100 != 2) {
			String msg = "Spotify token request failed (" + res.statusCode() + ")";
			try {
				JsonObject err = JsonParser.parseString(res.body()).getAsJsonObject();
				if (err.has("error_description")) msg += ": " + err.get("error_description").getAsString();
				if (res.statusCode() == 400 && err.has("error") && "invalid_grant".equals(err.get("error").getAsString())) {
					logout();
				}
			} catch (Exception ignored) {
			}
			throw new IllegalStateException(msg);
		}
		return JsonParser.parseString(res.body()).getAsJsonObject();
	}

	private synchronized void applyTokenResponse(JsonObject o) {
		accessToken = o.get("access_token").getAsString();
		expiresAtMs = System.currentTimeMillis() + o.get("expires_in").getAsLong() * 1000L;
		if (o.has("refresh_token") && !o.get("refresh_token").isJsonNull()) {
			refreshToken = o.get("refresh_token").getAsString();
		}
		saveTokens();
	}

	private static Path tokenPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("spotifylyrics-tokens.json");
	}

	private void saveTokens() {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("refresh_token", refreshToken);
			Path p = tokenPath();
			Files.writeString(p, gson.toJson(o), StandardCharsets.UTF_8);
			try {
				p.toFile().setReadable(false, false);
				p.toFile().setReadable(true, true);
				p.toFile().setWritable(false, false);
				p.toFile().setWritable(true, true);
			} catch (Exception ignored) {
			}
		} catch (Exception e) {
			SpotifyLyricsMod.LOGGER.warn("Could not save Spotify tokens", e);
		}
	}

	private void loadTokens() {
		try {
			Path p = tokenPath();
			if (Files.exists(p)) {
				JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
				if (o.has("refresh_token")) refreshToken = o.get("refresh_token").getAsString();
			}
		} catch (Exception e) {
			SpotifyLyricsMod.LOGGER.warn("Could not read Spotify tokens", e);
		}
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static Map<String, String> parseQuery(String target) {
		Map<String, String> out = new HashMap<>();
		int i = target.indexOf('?');
		if (i < 0) return out;
		for (String pair : target.substring(i + 1).split("&")) {
			int eq = pair.indexOf('=');
			if (eq < 0) continue;
			out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return out;
	}

	private static void respond(Socket s, int status, String message) throws Exception {
		String html = "<!doctype html><meta charset=utf-8><title>Spotify Lyrics HUD</title>"
				+ "<body style=\"font-family:sans-serif;text-align:center;margin-top:20vh\"><h2>" + message + "</h2>";
		byte[] body = html.getBytes(StandardCharsets.UTF_8);
		String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\n"
				+ "Content-Type: text/html; charset=utf-8\r\n"
				+ "Content-Length: " + body.length + "\r\n"
				+ "Connection: close\r\n\r\n";
		OutputStream out = s.getOutputStream();
		out.write(head.getBytes(StandardCharsets.US_ASCII));
		out.write(body);
		out.flush();
	}
}
