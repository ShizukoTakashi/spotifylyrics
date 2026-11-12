package dev.spotifylyrics;

import java.net.http.HttpClient;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Starts with the game and polls Spotify in the background, so lyrics show up by themselves whenever
 * a song plays. Polls faster while music is playing and slowly when idle. Between polls the HUD
 * interpolates the playback position locally.
 */
public final class PlaybackTracker {
	private final SpotifyAuth auth;
	private final SpotifyApi api;
	private final HttpClient http;
	private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2, r -> {
		Thread t = new Thread(r, "spotifylyrics-worker");
		t.setDaemon(true);
		return t;
	});

	private volatile SpotifyApi.Sample sample;
	private volatile Lyrics lyrics;
	private volatile String lastError;
	private volatile String message = "";
	private volatile long messageAtMs;
	private volatile long backoffUntilMs;
	private ScheduledFuture<?> volumeTask;

	public PlaybackTracker(SpotifyAuth auth, SpotifyApi api, HttpClient http) {
		this.auth = auth;
		this.api = api;
		this.http = http;
	}

	public void start() {
		exec.schedule(this::loop, 1, TimeUnit.SECONDS);
	}

	public void stop() {
		exec.shutdownNow();
	}

	/** Refresh shortly after the player pressed something. */
	public void pollSoon() {
		exec.schedule(this::poll, 450, TimeUnit.MILLISECONDS);
	}

	public SpotifyApi.Sample sample() {
		return sample;
	}

	public Lyrics lyrics() {
		return lyrics;
	}

	public String lastError() {
		return lastError;
	}

	/** Last result message from a button press (shown in the settings screen). */
	public String message() {
		return System.currentTimeMillis() - messageAtMs < 6000 ? message : "";
	}

	/** Runs an API action off the game thread and remembers its result message. */
	public void act(Callable<SpotifyApi.Result> action) {
		if (!auth.isLoggedIn()) {
			setMessage("Not logged in. Open the Account tab.");
			return;
		}
		exec.execute(() -> {
			try {
				SpotifyApi.Result r = action.call();
				setMessage(r.message());
				if (r.ok()) pollSoon();
			} catch (Exception e) {
				setMessage(e.getMessage() == null ? e.toString() : e.getMessage());
			}
		});
	}

	/** Slider-friendly: only sends the last value after the player stops dragging. */
	public synchronized void setVolumeDebounced(int percent) {
		if (volumeTask != null) volumeTask.cancel(false);
		volumeTask = exec.schedule(() -> act(() -> api.volume(percent)), 350, TimeUnit.MILLISECONDS);
	}

	private void setMessage(String m) {
		message = m;
		messageAtMs = System.currentTimeMillis();
	}

	private void loop() {
		try {
			poll();
		} finally {
			SpotifyApi.Sample s = sample;
			long delay = (s != null && s.playing()) ? 1500 : 5000;
			if (!exec.isShutdown()) exec.schedule(this::loop, delay, TimeUnit.MILLISECONDS);
		}
	}

	private void poll() {
		try {
			if (!auth.isLoggedIn() || System.currentTimeMillis() < backoffUntilMs) return;
			Optional<SpotifyApi.Sample> now = api.currentPlayback();
			lastError = null;
			if (now.isEmpty()) {
				sample = null;
				return;
			}
			SpotifyApi.Sample s = now.get();
			sample = s;
			Lyrics current = lyrics;
			if (current == null || !current.trackId.equals(s.trackId())) {
				lyrics = Lyrics.loading(s.trackId());
				exec.execute(() -> {
					Lyrics fetched = Lyrics.fetch(http, s);
					SpotifyApi.Sample latest = sample;
					if (latest != null && latest.trackId().equals(s.trackId())) lyrics = fetched;
				});
			}
		} catch (Exception e) {
			lastError = e.getMessage();
			backoffUntilMs = System.currentTimeMillis() + 10_000;
			SpotifyLyricsMod.LOGGER.debug("Spotify poll failed", e);
		}
	}
}
