package dev.spotifylyrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Reads currently playing Spotify track and controls playback directly from Windows Media Session (100% free). */
public final class SpotifyApi {
	public record Result(boolean ok, String message) {
		static Result ok(String m) {
			return new Result(true, m);
		}

		static Result fail(String m) {
			return new Result(false, m);
		}
	}

	public record Sample(String trackId, String title, String artist, String album,
						 long durationMs, long progressMs, boolean playing, long takenAtNanos,
						 int volumePercent) {
		public long estimatedProgressMs() {
			if (!playing) return progressMs;
			return Math.min(durationMs, progressMs + (System.nanoTime() - takenAtNanos) / 1_000_000L);
		}
	}

	private static final String PS_QUERY = """
			Add-Type -AssemblyName System.Runtime.WindowsRuntime
			$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
			Function Await($WinRtTask, $ResultType) {
			    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
			    $netTask = $asTask.Invoke($null, @($WinRtTask))
			    $netTask.Wait(-1) | Out-Null
			    $netTask.Result
			}
			[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null
			$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
			$sessions = $mgr.GetSessions()
			$target = $null
			foreach ($s in $sessions) {
			    if ($s.SourceAppUserModelId -match 'Spotify') { $target = $s; break }
			}
			if ($null -eq $target) { $target = $mgr.GetCurrentSession() }
			if ($null -eq $target) { exit 0 }
			$info = Await ($target.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
			$pb = $target.GetPlaybackInfo()
			$tl = $target.GetTimelineProperties()
			$out = @{
			    title = $info.Title
			    artist = $info.Artist
			    album = $info.AlbumTitle
			    playing = ($pb.PlaybackStatus -eq 'Playing')
			    posMs = [long]$tl.Position.TotalMilliseconds
			    durMs = [long]$tl.EndTime.TotalMilliseconds
			}
			$out | ConvertTo-Json -Compress
			""";

	public SpotifyApi(SpotifyAuth auth, HttpClient http) {
	}

	public Optional<Sample> currentPlayback() {
		try {
			String json = runPowerShell(PS_QUERY);
			if (json == null || json.isBlank()) return Optional.empty();
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			String title = o.has("title") && !o.get("title").isJsonNull() ? o.get("title").getAsString() : "";
			String artist = o.has("artist") && !o.get("artist").isJsonNull() ? o.get("artist").getAsString() : "";
			if (title.isBlank()) return Optional.empty();
			String album = o.has("album") && !o.get("album").isJsonNull() ? o.get("album").getAsString() : "";
			boolean playing = o.has("playing") && !o.get("playing").isJsonNull() && o.get("playing").getAsBoolean();
			long posMs = o.has("posMs") && !o.get("posMs").isJsonNull() ? o.get("posMs").getAsLong() : 0;
			long durMs = o.has("durMs") && !o.get("durMs").isJsonNull() ? o.get("durMs").getAsLong() : 0;
			String trackId = (artist + " - " + title).toLowerCase();
			return Optional.of(new Sample(trackId, title, artist, album, durMs, posMs, playing, System.nanoTime(), 100));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	public Result seek(long positionMs) {
		long ticks = Math.max(0, positionMs) * 10_000L;
		return mediaCommand("$target.TryChangePlaybackPositionAsync(" + ticks + ") | Out-Null", "Seeked");
	}

	public Result pause() {
		return mediaCommand("$target.TryPauseAsync() | Out-Null", "Paused");
	}

	public Result resume() {
		return mediaCommand("$target.TryPlayAsync() | Out-Null", "Resumed");
	}

	public Result next() {
		return mediaCommand("$target.TrySkipNextAsync() | Out-Null", "Skipped to next track");
	}

	public Result previous() {
		return mediaCommand("$target.TrySkipPreviousAsync() | Out-Null", "Previous track");
	}

	public Result volume(int percent) {
		return Result.fail("Use Windows volume or Spotify app to adjust volume.");
	}

	public Result searchAndPlay(String query) {
		return Result.fail("Play songs directly in the Spotify desktop app.");
	}

	private Result mediaCommand(String actionLine, String okMessage) {
		String script = """
				Add-Type -AssemblyName System.Runtime.WindowsRuntime
				$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
				Function Await($WinRtTask, $ResultType) {
				    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
				    $netTask = $asTask.Invoke($null, @($WinRtTask))
				    $netTask.Wait(-1) | Out-Null
				    $netTask.Result
				}
				[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null
				$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
				$target = $null
				foreach ($s in $mgr.GetSessions()) {
				    if ($s.SourceAppUserModelId -match 'Spotify') { $target = $s; break }
				}
				if ($null -eq $target) { $target = $mgr.GetCurrentSession() }
				if ($null -ne $target) {
				""" + actionLine + "\n}";
		try {
			runPowerShell(script);
			return Result.ok(okMessage);
		} catch (Exception e) {
			return Result.fail("Could not send media command");
		}
	}

	private static String runPowerShell(String script) throws Exception {
		String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
		Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
				.redirectErrorStream(true)
				.start();
		StringBuilder sb = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) sb.append(line);
		}
		p.waitFor(4, TimeUnit.SECONDS);
		return sb.toString().trim();
	}
}
