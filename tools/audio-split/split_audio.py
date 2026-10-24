# -*- coding: utf-8 -*-
"""Split the music out of a video's audio. Drag a file onto SPLIT AUDIO.bat.

    py split_audio.py "C:\\clips\\myclip.mp4"

Made 2026-09-10: the owner was recording, had music playing by accident, and
needed the voice without it. DaVinci Resolve can do this - Fairlight page,
Voice Isolation - but only in the paid Studio build.

WHAT COMES OUT
  <name>_VOICE.wav   the voice with the music taken out
  <name>_MUSIC.wav   the music with the voice taken out
Both are 48 kHz stereo WAV, which is what Resolve wants on a timeline; a
re-encoded mp3 would add a second generation of loss to audio that has just
been through a separation model.

WHY DEMUCS AND NOT A FILTER
  There is no EQ or noise gate that separates music from speech - they occupy
  the same frequencies, which is the entire difficulty. Demucs is a trained
  source-separation model: it was shown millions of songs already split into
  their parts and learned what a voice looks like inside a mix. That is a
  different kind of tool from anything in a normal audio effects rack.

  `--two-stems=vocals` is deliberate. The full model outputs four stems
  (drums, bass, other, vocals) and takes about twice as long; everything that
  is not the voice is going in the bin here, so splitting it four ways and
  then re-adding three of them is work nobody uses.

THE VENV IS NOT OPTIONAL
  This installs into its own environment with --system-site-packages, so it
  borrows the CUDA torch that ComfyUI/SIGNAL already has (2.5 GB not
  re-downloaded) while keeping every new package out of that shared env.
  Installing demucs directly into the 3.10 env would have been one pip command
  and could have moved numpy under ComfyUI's feet - the same trap that made
  rembg a --no-deps install in SIGNAL.
"""
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PY = os.path.join(HERE, ".venv", "Scripts", "python.exe")
FFMPEG = shutil.which("ffmpeg") or r"C:\Users\you\Documents\FFmpeg\bin\ffmpeg.exe"
WORK = os.path.join(HERE, "_work")


def run(cmd, **kw):
    return subprocess.run(cmd, **kw)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        print("Drag a video or audio file onto SPLIT AUDIO.bat")
        return 1
    src = os.path.abspath(sys.argv[1])
    if not os.path.exists(src):
        print("No such file: %s" % src)
        return 1

    # Only the part that is actually ruined. An hour of video where ten
    # minutes have music underneath is ten minutes of work, not sixty - and
    # more importantly, running the model over clean speech is not free:
    # separation always leaves some artefact, so audio that was already fine
    # comes back very slightly worse for no reason.
    start = sys.argv[2] if len(sys.argv) > 2 else ""
    end = sys.argv[3] if len(sys.argv) > 3 else ""
    span = []
    if start.strip():
        span += ["-ss", start.strip()]
    if end.strip():
        span += ["-to", end.strip()]

    stem = os.path.splitext(os.path.basename(src))[0]
    if span:
        stem += "_%s-%s" % (start.strip().replace(":", "") or "0",
                            end.strip().replace(":", "") or "end")
    outdir = os.path.dirname(src)
    if os.path.isdir(WORK):
        shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(WORK, exist_ok=True)

    # 1. Pull the audio out at full quality. -vn drops video, and 48k stereo
    #    float is what the model wants; feeding it a 128 kbps mp3 would bake
    #    compression artefacts into both stems.
    wav = os.path.join(WORK, "input.wav")
    print("extracting audio%s..." % (
        " from %s to %s" % (start or "start", end or "end") if span else ""))
    # -ss BEFORE -i seeks by keyframe and is instant even on a 1-hour file;
    # after -i it decodes everything up to that point. Both are accurate here
    # because the audio is re-encoded to PCM either way.
    r = run([FFMPEG, "-y"] + span + ["-i", src, "-vn", "-ac", "2", "-ar", "48000",
             "-c:a", "pcm_s16le", wav],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if r.returncode != 0 or not os.path.exists(wav):
        print("ffmpeg could not read that file:")
        print((r.stderr or b"").decode(errors="replace")[-500:])
        return 1

    # 2. Separate. First run downloads the model (~80 MB) and caches it.
    print("separating voice from music (first run downloads the model)...")
    t0 = time.time()
    r = run([PY, "-m", "demucs", "--two-stems=vocals",
             "-n", "htdemucs", "-o", WORK, wav])
    if r.returncode != 0:
        print("demucs failed - see the output above")
        return 1

    got = os.path.join(WORK, "htdemucs", "input")
    voc = os.path.join(got, "vocals.wav")
    inst = os.path.join(got, "no_vocals.wav")
    if not (os.path.exists(voc) and os.path.exists(inst)):
        print("separation produced nothing at %s" % got)
        return 1

    a = os.path.join(outdir, stem + "_VOICE.wav")
    b = os.path.join(outdir, stem + "_MUSIC.wav")
    shutil.copy2(voc, a)
    shutil.copy2(inst, b)
    shutil.rmtree(WORK, ignore_errors=True)

    print()
    print("done in %.0fs" % (time.time() - t0))
    print("   %s" % a)
    print("   %s" % b)
    print()
    if span:
        print("In Resolve: put %s on a new audio track starting at %s,"
              % (os.path.basename(a), start or "00:00"))
        print("then mute the original audio for that stretch only. The rest of")
        print("the video keeps its untouched audio.")
    else:
        print("In Resolve: drop the _VOICE file on the timeline and mute or")
        print("delete the original clip's audio track.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
