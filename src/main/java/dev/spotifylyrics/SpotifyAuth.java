package dev.spotifylyrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.function.Consumer;

/** Local Windows media mode: no Spotify login or Premium required. */
public final class SpotifyAuth {
	public static final String REDIRECT_URI = "http://127.0.0";

	public SpotifyAuth(Config config, HttpClient http) {
	}

	public boolean isLoggedIn() {
		return true;
	}

	public void logout() {
	}

	public String accessToken() {
		return "";
	}

	public void forceRefresh() {
	}

	public void login(Consumer<URI> onUrl) {
		SpotifyLyricsMod.say("Connected automatically via Windows Media! Just play any song on Spotify.");
	}
}
