# AppBoost — status: it works, and it is at the physical ceiling. Do not restart this.

## READ THIS FIRST — the answer, 2026-07-29

**The boost works. There is almost nothing left to gain on this machine, and that is physics,
not a bug.** Measured at the real hardware endpoint with a steady tone:

```
                    baseline    boosted 4x    ratio
Sonar-Gaming         0.07071  ->   0.27848    3.94x   <- the engine delivers a clean 4x
Speakers             0.36775  ->   0.69650    1.89x   <- the hardware clips it back
```

- Baseline sat at 0.36775 against a full-scale sine of 0.707 — **52%, i.e. 5.7 dB of headroom.**
- The boost delivered **1.89x, i.e. 5.5 dB.** It used essentially *all* the headroom that existed.
- Every Windows render endpoint reads **100% / 0.0 dB** (`probe.exe --volumes`), so there is no
  digital volume left anywhere.
- SteelSeries Sonar applies roughly **2.6x** before the Realtek DAC, which is where the headroom
  went. Normal audio is already near full scale before we touch it.

0 dBFS is the end of the road. Above it, extra gain is clipping, not loudness. **The remaining
lever is analog** — the physical volume control on the speakers/headphones, or Sonar's own
output stage. The user was asked whether to pursue the one software remainder (calibrating the
ceiling to the measured downstream clip point and saturating into it, worth a few dB of density
on music) and **declined**. Do not re-litigate it.

### Do not repeat these dead ends

- A peak limiter after the gain: ~10% louder at 400%, because masters already peak near full scale.
- Swapping it for a compressor: **nothing at all**, because music hits peaks constantly and a
  120 ms release keeps gain reduction permanently engaged, so quiet parts never come up.
- Anything that reacts to level and turns it down loses this fight. The current DSP is gain plus
  soft saturation for that reason.

### A trap that cost real time

Force-killing the app (`Stop-Process -Force`) skips teardown and **leaves the target app parked at
3% volume**, which then looks exactly like "the boost made it quieter". It happened. Check with
`probe.exe <app> --check`, which reports and repairs it read-only. Worth making the app persist
its state and repair on next launch.

---

# Original notes: capture works, design pivoted

Goal: a window listing every app currently making sound, each with a dial up to ~400%.
No virtual cable, no routing, no settings — you just drag Spotify's dial.

## Where it stands (2026-07-29)

- **Process loopback capture works end to end.** Real audio off a live Spotify pid.
- **The design question is answered, and the answer was the bad one.** The tap is *after* the
  app's volume slider *and* after its mute. See below.
- **Boost.cs works around it** and builds clean. Not yet judged by ear.
- No UI yet. `probe/` is still a console harness.

## The GetService blocker — solved, and it was a typo

Everything in the old status doc about vtable slots, marshalling, PROPVARIANT blobs and
apartment threading was chasing a wrong GUID:

```
ours    C8ADBD64-5F26-4B4F-A85D-CE2E1FEB2A8B     <- only the first block was right
real    C8ADBD64-E71E-48A0-A4DE-185C395CD317
```

`E_NOINTERFACE` was the honest answer: the client had genuinely never heard of that interface.

What found it was the control experiment, now `probe/Control.cs` (`probe.exe --control`): run the
*same* `IAudioClient` declaration and the *same* raw slot-14 call against a plain device client
from `IMMDevice::Activate`. It resolved `IAudioClock` and `ISimpleAudioVolume` with S_OK — so the
interop was never the problem — while `IAudioRenderClient` came back `AUDCLNT_E_WRONG_ENDPOINT_TYPE`
and only `IAudioCaptureClient` said `E_NOINTERFACE`. One IID behaving differently from its
neighbours on the same call path can only be the IID. Cross-checked against NAudio's, which
differs, which was the confirmation.

**The lesson worth keeping:** four rounds of ruling things out never questioned a constant that
had been eyeballed once. A control that isolates one variable beat all of it in ten minutes.

## The open question — ANSWERED: capture is POST-volume

Measured by `probe.exe Spotify`, which toggles the session volume 100%/50% twenty times *inside
one continuous capture* and pools each phase:

```
  at 100%:  rms 0.04338  peak 0.11338
  at  50%:  rms 0.02598  peak 0.05774
  peak ratio (50/100) = 0.509
```

The interleaving matters. The first attempt measured 3 s at 100% and then 3 s at 50% — two
different parts of a song, which differ by more than 6 dB on their own — and split the verdict
(0.88 by RMS, 0.53 by peak). Alternating fast makes the content the same in both pools.

`probe.exe Spotify --mute` settles the other half: muting the app captures **digital silence**
(rms 0.00000). Mute is applied at the same point as volume.

**So: the app cannot be silenced and replayed. That plan is dead.**

## What replaced it — attenuate and over-boost (`probe/Boost.cs`)

We cannot silence the app, so we nearly silence it:

```
app session volume := 3%                     what the speakers hear directly
our captured copy  := captured * dial/0.03   what the speakers hear from us
```

The direct path ends up 30 dB under our copy — inaudible, and it is the same signal, so the
worst it can do is a faint comb filter. The 30 dB of attenuation costs nothing: the pipeline is
float32 with ~144 dB of range, so this is spending headroom we already have.

Includes a feedback limiter (2 ms attack / 200 ms release, 0.98 ceiling) because 400% hits the
ceiling constantly and hard clipping that much sounds like tearing paper.

Run it: `probe.exe Spotify --boost 3`

### The trap in that plan, and the fix (this is the important part)

The obvious makeup gain, `dial / Leak`, is **wrong**, and wrong in the worst way: a 4x boost came
out at **0.86x — quieter than not boosting at all**, which is exactly what it sounded like.

The capture does not hand back the app's samples at face value. Measured against `tone/`:

```
generated 0.1  ->  captured 0.021187      ratio 0.21187
generated 0.5  ->  captured 0.105934      ratio 0.21187
```

Constant across amplitude, and identical at 48 kHz and 96 kHz, so it is not conversion. Endpoint
volume was 100% / 0.0 dB, so it is not that either. Session volume is perfectly linear all the
way down to 1% (ratio 1.000 at every step), so the 3% leak is not the problem. It is simply a
fixed gain in the tap, and ignoring it loses 4.72x — which swallowed the whole boost.

**The fix is to measure it, not to hardcode 0.21187.** The session's peak meter
(`AudioMeterInformation.MasterPeakValue`) reports the app's level *before* its volume scalar —
it read a steady 0.100000 while the session volume swept 100% → 1%. So:

```
makeup per unit dial = meter_peak / captured_peak      (both measured at the same volume)
```

Verified end to end by loopback-capturing the real hardware endpoint:

```
                    baseline    boosted 4x    ratio
Sonar-Gaming         0.03536  ->  0.13544     3.83x
Speakers             0.18388  ->  0.69649     3.79x
```

`app/BoostEngine.cs` calibrates once at engage time and then re-trims every ~100 ms, comparing
the meter and the capture over the *same* window (the meter reports its peak since last read, so
sampling it against a single 10 ms packet drifts the gain upward).

### Measuring this at all needed two tools

- **`tone/`** — a 440 Hz generator at a known amplitude. Every earlier measurement was wrecked by
  whether Spotify happened to be playing: twice the capture read digital silence and the
  conclusion drawn from it was wrong. It **always** stops on its own after N seconds, because an
  earlier version ran until killed and `kill` from bash does not reliably stop a native Windows
  process — two orphans ended up humming through the speakers with no obvious source.
- **`probe.exe <app> --boost N --ab`** — loopback-captures the endpoints and reports RMS with the
  boost off, then on. `MasterPeakValue` is useless for this: it read ~0.99 and a pinned 1.000
  whether boosted or not, because Sonar applies a large gain of its own and the meter saturates.

### What still needs judging

- **Does it sound right?** Only ears can answer. Listen for a faint doubling/flanging (the leak),
  pumping (limiter too aggressive), and dropouts.
- **Latency.** Irrelevant for music, but a 60 ms lag is noticeable against video. Tune the
  `WasapiOut` latency and `BufferDuration` if boosting a browser matters.
- **Is 3% the right leak?** Lower is cleaner but closer to whatever the engine's volume
  resolution actually is. 1% and 5% are both worth a listen.

## Machine notes that shaped the code

- Default endpoint here is **SteelSeries Sonar - Gaming**, a virtual device, running at **96 kHz**.
  Boost.cs reads the device mix format rather than assuming 48 kHz for that reason.
- Spotify's audio session is on the Sonar device, not the Realtek speakers.
- Sessions are enumerated across *every* active render endpoint, and the app is matched by the
  session's own pid — Chrome and Spotify render from a child process, so the main-window pid has
  no session at all.
- `Control.cs` and NAudio's `MMDeviceEnumerator` cannot both run in one process: the second
  activation gets back the first's RCW and the cast fails. Hence `--control` is its own mode.

## Next, in order

1. Listen to `--boost 3`. Everything else is wasted if it does not sound clean.
2. Tune leak / limiter / latency from what that reveals.
3. Then the UI: the session list is already there (`FindSession` prints it), so the window is
   that list plus a dial each, and the dial writes `session volume` + `makeup gain`.

## What works TODAY instead

`../Volume Increaser` was updated and builds clean. It no longer needs VB-Audio Virtual Cable:
any playback device can be the source via WASAPI loopback, listed as `[loopback]`.

To boost only one app with it:
1. Windows Settings → System → Sound → Volume mixer → the app → Output device → pick a spare
   output (an unused monitor HDMI endpoint works).
2. In the app: source = that device `[loopback]`, play to = your real speakers, gain to taste.

Same-device selection is refused in code, because that is a feedback loop.

Note: Spotify → Settings → Playback → Normalize volume → **off made it quieter**, not louder.
That means normalization was pushing quiet tracks *up*; leaving it **on** is the better default
here, and it is not a substitute for the boost.
