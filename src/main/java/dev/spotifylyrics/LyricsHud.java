package dev.spotifylyrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/** Fast, persistent Windows Media Session bridge (100% free, zero Premium or login needed). */
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
			long est = progressMs + (System.nanoTime() - takenAtNanos) / 1_000_000L;
			return durationMs > 0 ? Math.min(durationMs, est) : est;
		}
	}

	private static final String WORKER_SCRIPT = """
			$ErrorActionPreference = 'SilentlyContinue'
			Add-Type -AssemblyName System.Runtime.WindowsRuntime
			$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters().ParameterType.Name -eq 'IAsyncOperation`1' })
			Function Await($WinRtTask, $ResultType) {
			    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
			    $netTask = $asTask.Invoke($null, @($WinRtTask))
			    $netTask.Wait(-1) | Out-Null
			    $netTask.Result
			}
			Add-Type -TypeDefinition @"
			using System;
			using System.Diagnostics;
			using System.Runtime.InteropServices;
			public class SpotifyVol {
			    [Guid("BCDE0395-E52F-467C-8E3D-C4579291692E"), ComImport] class MMDeviceEnumerator {}
			    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			    interface IMMDeviceEnumerator { int NotImpl1(); int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppDevice); }
			    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			    interface IMMDevice { int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface); }
			    [Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			    interface IAudioSessionManager2 { int NotImpl1(); int NotImpl2(); int GetSessionEnumerator(out IAudioSessionEnumerator SessionEnum); }
			    [Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			    interface IAudioSessionEnumerator { int GetCount(out int SessionCount); int GetSession(int SessionCount, out IAudioSessionControl Session); }
			    [Guid("F4B1A599-7266-4319-A8CA-E70ACB11E8CD"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			    interface IAudioSessionControl { int NotImpl1(); int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string pRetVal); int NotImpl2(); int NotImpl3(); int NotImpl4(); int NotImpl5(); int NotImpl6(); int NotImpl7(); int NotImpl8(); }
			    [Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			    interface IAudioSessionControl2 { int NotImpl1(); int NotImpl2(); int NotImpl3(); int NotImpl4(); int NotImpl5(); int NotImpl6(); int NotImpl7(); int NotImpl8(); int NotImpl9(); int NotImpl10(); int GetProcessId(out uint pRetVal); }
			    [Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			    interface ISimpleAudioVolume { int SetMasterVolume(float fLevel, ref Guid EventContext); int GetMasterVolume(out float pfLevel); }
			    public static int Volume(int setPercent) {
			        try {
			            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
			            IMMDevice speakers;
			            enumerator.GetDefaultAudioEndpoint(0, 1, out speakers);
			            Guid iid = typeof(IAudioSessionManager2).GUID;
			            object o;
			            speakers.Activate(ref iid, 0, IntPtr.Zero, out o);
			            var mgr = (IAudioSessionManager2)o;
			            IAudioSessionEnumerator sessionEnum;
			            mgr.GetSessionEnumerator(out sessionEnum);
			            int count;
			            sessionEnum.GetCount(out count);
			            Guid empty = Guid.Empty;
			            for (int i = 0; i < count; i++) {
			                IAudioSessionControl ctl;
			                sessionEnum.GetSession(i, out ctl);
			                var ctl2 = ctl as IAudioSessionControl2;
			                if (ctl2 != null) {
			                    uint pid;
			                    ctl2.GetProcessId(out pid);
			                    try {
			                        var proc = Process.GetProcessById((int)pid);
			                        if (proc.ProcessName.Equals("Spotify", StringComparison.OrdinalIgnoreCase)) {
			                            var vol = ctl as ISimpleAudioVolume;
			                            if (vol != null) {
			                                if (setPercent >= 0) vol.SetMasterVolume(Math.Max(0f, Math.Min(1f, setPercent / 100f)), ref empty);
			                                float cur;
			                                vol.GetMasterVolume(out cur);
			                                return (int)Math.Round(cur * 100f);
			                            }
			                        }
			                    } catch {}
			                }
			            }
			        } catch {}
			        return -1;
			    }
			}
			"@
			[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null
			$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
			$lastLine = ""
			$lastPoll = [DateTime]::MinValue
			while ($true) {
			    if ([Console]::In.Peek() -ne -1) {
			        $cmd = [Console]::In.ReadLine()
			        if ($null -eq $cmd -or $cmd -eq "EXIT") { break }
			        $target = $null
			        foreach ($s in $mgr.GetSessions()) { if ($s.SourceAppUserModelId -match 'Spotify') { $target = $s; break } }
			        if ($null -eq $target) { $target = $mgr.GetCurrentSession() }
			        if ($cmd -eq "PLAY" -and $target) { $target.TryPlayAsync() | Out-Null }
			        elseif ($cmd -eq "PAUSE" -and $target) { $target.TryPauseAsync() | Out-Null }
			        elseif ($cmd -eq "NEXT" -and $target) { $target.TrySkipNextAsync() | Out-Null }
			        elseif ($cmd -eq "PREV" -and $target) { $target.TrySkipPreviousAsync() | Out-Null }
			        elseif ($cmd.StartsWith("SEEK:") -and $target) {
			            $ticks = [long]$cmd.Substring(5) * 10000
			            $target.TryChangePlaybackPositionAsync($ticks) | Out-Null
			        }
			        elseif ($cmd.StartsWith("VOL:")) {
			            [SpotifyVol]::Volume([int]$cmd.Substring(4)) | Out-Null
			        }
			        $lastPoll = [DateTime]::MinValue
			    }
			    if (([DateTime]::UtcNow - $lastPoll).TotalMilliseconds -ge 600) {
			        $lastPoll = [DateTime]::UtcNow
			        $target = $null
			        foreach ($s in $mgr.GetSessions()) { if ($s.SourceAppUserModelId -match 'Spotify') { $target = $s; break } }
			        if ($null -eq $target) { $target = $mgr.GetCurrentSession() }
			        if ($null -eq $target) {
			            if ($lastLine -ne "EMPTY") { $lastLine = "EMPTY"; [Console]::Out.WriteLine("EMPTY"); [Console]::Out.Flush() }
			        } else {
			            $info = Await ($target.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
			            $pb = $target.GetPlaybackInfo()
			            $tl = $target.GetTimelineProperties()
			            $dur = [long]($tl.EndTime - $tl.StartTime).TotalMilliseconds
			            $pos = [long]($tl.Position - $tl.StartTime).TotalMilliseconds
			            $vol = [SpotifyVol]::Volume(-1)
			            $out = @{
			                title = $info.Title
			                artist = $info.Artist
			                album = $info.AlbumTitle
			                playing = ($pb.PlaybackStatus -eq 'Playing')
			                posMs = [Math]::Max(0, $pos)
			                durMs = [Math]::Max(0, $dur)
			                vol = $vol
			            } | ConvertTo-Json -Compress
			            if ($out -ne $lastLine) { $lastLine = $out; [Console]::Out.WriteLine($out); [Console]::Out.Flush() }
			        }
			    }
			    Start-Sleep -Milliseconds 80
			}
			""";

	private volatile Sample latestSample;
	private volatile long latestReceivedAtMs;
	private Process worker;
	private BufferedWriter workerIn;

	public SpotifyApi(SpotifyAuth auth, HttpClient http) {
		startWorker();
	}

	private synchronized void startWorker() {
		try {
			if (worker != null && worker.isAlive()) return;
			String encoded = Base64.getEncoder().encodeToString(WORKER_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
			worker = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
					.redirectErrorStream(true)
					.start();
			workerIn = new BufferedWriter(new OutputStreamWriter(worker.getOutputStream(), StandardCharsets.UTF_8));
			Thread reader = new Thread(() -> {
				try (BufferedReader r = new BufferedReader(new InputStreamReader(worker.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = r.readLine()) != null) {
						line = line.trim();
						if (line.equals("EMPTY")) {
							latestSample = null;
							latestReceivedAtMs = System.currentTimeMillis();
						} else if (line.startsWith("{")) {
							parseSample(line);
						}
					}
				} catch (Exception ignored) {
				}
			}, "spotifylyrics-winmedia");
