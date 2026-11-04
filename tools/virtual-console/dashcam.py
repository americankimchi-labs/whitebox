"""A stand-in dashcam video for the demo scenarios that have real footage: a real dashcam clip of the same kind of
moment, with the sound a dashcam in the car would have recorded of the drive the scenario script just ran (road noise,
and the car's inaudible sync chirp at the exact moments the car played it).

    python3 -I tools/virtual-console/dashcam.py fetch                 # download and prepare the clips (once, ≈ 15 MB)
    python3 -I tools/virtual-console/dashcam.py make cut-in           # after `scenarios.py cut-in`
    python3 -I tools/virtual-console/dashcam.py make rear-end --data server-data --out ~/Desktop

`make` reads what the mirror server received of the car's live stream (`--data`, the server's data directory; the
newest car unless `--device` names one): the chirps the car played and the moment the scenario's detection fired.
It places the clip so that moment falls on the clip's own event (the impact, the hard brake), puts each chirp that
falls inside the clip into its sound, names the file the way dashcams do (REC_<local date>_<time>) and stamps the same
start time in its header. Pick the file on the capsule's phone page under 블랙박스 영상과 맞춰 보기: the phone finds
the chirp and places the video to the millisecond (by the file's clock alone, to about a second).

The clips are real dashcam recordings from Wikimedia Commons, public domain there (automated footage, no human
author); they are not part of this repository. `fetch` downloads Commons' MP4 copy of each and keeps only the part
the scenario needs, without the source dashcam's own date and ID strip (it would contradict the demo's clock). The
garage and night scenarios have no matching free footage, so they get no video rather than a misleading one.
Preparing and building the videos needs macOS (AVFoundation, via dashcam_prep.swift and dashcam_mux.swift) or ffmpeg.
"""
import argparse
import datetime
import math
import os
import random
import re
import shutil
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
CACHE = os.path.join(HERE, ".dashcam")

CRASH = ("DELTA_V", "SHORT_DELTA_V", "BRAKED_SPIKE", "REAR_SHOVE")

# source: Commons' MP4 copy; start/seconds: the part kept; top/bottom: pixels cropped off (the source's date strip);
# event: seconds into the kept part where the scenario's detection falls; anchor: the detections the clip is placed on
# (the newest one in the stream); watch: the part worth playing when showing it (from about two seconds before the
# moment to where nothing new happens).
SCENARIOS = {
    "cut-in": dict(
        clip="cut-in.mp4", start=0.0, seconds=14.0, top=0, bottom=22, event=4.0, anchor=CRASH, watch=(2.0, 6.5),
        url="https://upload.wikimedia.org/wikipedia/commons/transcoded/7/79/The_van_in_front_suddenly_changed_lanes_and_caused_the_vehicle_to_rear-end_collision.webm/"
            "The_van_in_front_suddenly_changed_lanes_and_caused_the_vehicle_to_rear-end_collision.webm.360p.mpeg4.mov",
        credit="Wikimedia Commons \"The van in front suddenly changed lanes and caused the vehicle to rear-end "
               "collision\" (public domain)",
        story="a car cuts across in front in city traffic; a brake, then contact (충돌)"),
    "pile-up": dict(
        clip="pile-up.mp4", start=8.0, seconds=22.0, top=0, bottom=20, event=10.5, anchor=("HARD_BRAKE",), watch=(8.5, 16.0),
        url="https://upload.wikimedia.org/wikipedia/commons/transcoded/b/b1/Multiple_cars_rear-end_collision_on_highway.webm/"
            "Multiple_cars_rear-end_collision_on_highway.webm.360p.mpeg4.mov",
        credit="Wikimedia Commons \"Multiple cars rear-end collision on highway\" (public domain)",
        story="a pile-up ahead on the highway; a hard brake that avoids it, then driving on"),
    "rear-end": dict(
        clip="rear-end.mp4", start=8.0, seconds=22.0, top=21, bottom=0, event=13.0, anchor=CRASH, watch=(11.0, 16.0),
        url="https://upload.wikimedia.org/wikipedia/commons/transcoded/b/ba/The_speeding_car_rear-ended_into_the_truck_in_front%2C_and_the_front_hood_was_blown_open.webm/"
            "The_speeding_car_rear-ended_into_the_truck_in_front%2C_and_the_front_hood_was_blown_open.webm.360p.mpeg4.mov",
        credit="Wikimedia Commons \"The speeding car rear-ended into the truck in front, and the front hood was blown "
               "open\" (public domain)",
        story="at 108 km/h on the highway, a slow truck in the lane; no brake before the impact"),
}

RATE = 48000
CHIRP_LOW_HZ, CHIRP_HIGH_HZ, CHIRP_MS, CHIRP_FADE_MS, CHIRP_AMPLITUDE = 21000, 22000, 100, 15, 0.3


def chirp():
    """The car's sync sweep, generated exactly as the car and the phone generate it."""
    n = RATE * CHIRP_MS // 1000
    fade = RATE * CHIRP_FADE_MS // 1000
    k = (CHIRP_HIGH_HZ - CHIRP_LOW_HZ) / (CHIRP_MS / 1000)
    out = []
    for i in range(n):
        t = i / RATE
        env = 0.5 * (1 - math.cos(math.pi * i / fade)) if i < fade else \
            0.5 * (1 - math.cos(math.pi * (n - 1 - i) / fade)) if i >= n - fade else 1.0
        out.append(CHIRP_AMPLITUDE * env * math.sin(2 * math.pi * (CHIRP_LOW_HZ * t + k * t * t / 2)))
    return out


def cabin_sound(seconds, chirps_at, seed):
    """Road rumble and engine hum as a cabin microphone hears them, with each chirp at its offset (seconds)."""
    rng = random.Random(seed)
    n = int(seconds * RATE)
    pcm = [0.0] * n
    rumble = 0.0
    for i in range(n):
        rumble = 0.995 * rumble + 0.02 * rng.gauss(0, 1)  # brown-ish road noise
        hum = 0.03 * math.sin(2 * math.pi * 42 * i / RATE) + 0.015 * math.sin(2 * math.pi * 84 * i / RATE)
        pcm[i] = 0.25 * rumble + hum + 0.004 * rng.gauss(0, 1)
    tpl = chirp()
    for at in chirps_at:
        start = int(round(at * RATE))
        for j, v in enumerate(tpl):
            if 0 <= start + j < n:
                pcm[start + j] += 0.5 * v
    return pcm


def write_wav(path, pcm):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, x)) * 32767)) for x in pcm))


def prepare(source, out, s):
    """Keeps the scenario's part of the source and crops its date strip: H.264 video, no sound."""
    if sys.platform == "darwin" and shutil.which("swift"):
        cmd = ["swift", os.path.join(HERE, "dashcam_prep.swift"), source, out,
               str(s["start"]), str(s["seconds"]), str(s["top"]), str(s["bottom"])]
    elif shutil.which("ffmpeg"):
        crop = f"crop=iw:ih-{s['top'] + s['bottom']}:0:{s['top']}"
        cmd = ["ffmpeg", "-loglevel", "error", "-y", "-ss", str(s["start"]), "-t", str(s["seconds"]), "-i", source,
               "-vf", crop, "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", out]
    else:
        sys.exit("preparing the clips needs macOS (swift) or ffmpeg")
    subprocess.run(cmd, check=True)


def download(url, path, tries=6):
    """Commons answers bursts with 429: wait as asked (or 15 s) and try again."""
    req = urllib.request.Request(url, headers={"User-Agent": "WhiteBoxDemo/1.0 (dashcam stand-in)"})
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(req, timeout=60) as r, open(path + ".part", "wb") as f:
                shutil.copyfileobj(r, f)
            os.replace(path + ".part", path)
            return
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == tries - 1:
                raise
            wait = int(e.headers.get("Retry-After") or 15)
            print(f"{'':12} busy, retrying in {wait} s")
            time.sleep(wait)


def fetch(_args):
    os.makedirs(CACHE, exist_ok=True)
    for name, s in SCENARIOS.items():
        path = os.path.join(CACHE, s["clip"])
        if os.path.exists(path):
            print(f"{name:12} already here: {path}")
            continue
        print(f"{name:12} {s['credit']}\n{'':12} {s['url']}")
        source = path[:-4] + ".source.mov"
        download(s["url"], source)
        prepare(source, path, s)
        os.remove(source)


def stream_dir(data, device):
    """The car's live stream as the server stored it: the named device, or the most recently written one."""
    root = os.path.join(data, "streams")
    if not os.path.isdir(root):
        sys.exit(f"no mirrored streams under {root} (is --data the server's data directory?)")
    dirs = [os.path.join(root, d, "live") for d in os.listdir(root) if (device is None or d.startswith(device))]
    dirs = [d for d in dirs if os.path.exists(os.path.join(d, "records.log"))]
    if not dirs:
        sys.exit("no live stream found" + (f" for device {device}" if device else ""))
    return max(dirs, key=lambda d: os.path.getmtime(os.path.join(d, "records.log")))


def wall_of(entry):
    """The wall-clock time (ms) of a stored record: every record carries `elapsedNanos;wallMillis` in the clear."""
    for field in entry.split("|")[1:]:
        if re.fullmatch(r"\d+;\d+", field):
            return int(field.split(";")[1])
    return None


def read_stream(path):
    """(entries by seq, chirps as (id, played wall ms), detections as (seq, trigger, cause seq))."""
    by_seq, chirps, triggers = {}, [], []
    with open(os.path.join(path, "records.log"), encoding="utf-8", errors="replace") as f:
        for line in f:
            cols = line.rstrip("\n").split("\t")
            if len(cols) < 5:
                continue
            seq, entry = int(cols[1]), cols[3]
            by_seq[seq] = entry
            fields = entry.split("|")
            if fields[0] == "A" and len(fields) > 4 and fields[2] == "BEACON":
                chirps.append((int(fields[3]), int(fields[4].split(";")[1])))
            elif fields[0] == "E" and len(fields) > 4 and fields[2] == "TRIGGER":
                triggers.append((seq, fields[3], int(fields[4])))
    return by_seq, chirps, triggers


def mux(video, wav, out, seconds):
    if sys.platform == "darwin" and shutil.which("swift"):
        cmd = ["swift", os.path.join(HERE, "dashcam_mux.swift"), video, wav, out, f"{seconds:.3f}"]
    elif shutil.which("ffmpeg"):
        cmd = ["ffmpeg", "-loglevel", "error", "-y", "-i", video, "-i", wav, "-t", f"{seconds:.3f}",
               "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "pcm_s16le", out]
    else:
        sys.exit("building the video needs macOS (swift) or ffmpeg")
    subprocess.run(cmd, check=True)


def stamp_header(path, start_ms):
    """Writes the start time into the movie header (mvhd creation/modification, seconds since 1904, UTC)."""
    secs = int(start_ms // 1000) + 2082844800
    with open(path, "r+b") as f:
        data = f.read()
        i = data.find(b"mvhd")
        if i < 0:
            return
        version = data[i + 4]
        if version == 1:
            f.seek(i + 8)
            f.write(struct.pack(">QQ", secs, secs))
        else:
            f.seek(i + 8)
            f.write(struct.pack(">II", secs, secs))


def make(args):
    s = SCENARIOS[args.scenario]
    clip = os.path.join(CACHE, s["clip"])
    if not os.path.exists(clip):
        sys.exit(f"{clip} is missing: run `dashcam.py fetch` first")
    path = stream_dir(os.path.abspath(args.data), args.device)
    by_seq, chirps, triggers = read_stream(path)
    found = [t for t in triggers if t[1] in s["anchor"]]
    if not found:
        sys.exit(f"no {'/'.join(s['anchor'])} detection in {path}: run `scenarios.py {args.scenario}` first")
    seq, trigger, cause = found[-1]
    event_ms = wall_of(by_seq.get(cause, "")) or wall_of(by_seq[seq])
    length = clip_seconds(clip)
    start_ms = event_ms - int(s["event"] * 1000)
    inside = [(cid, (at - start_ms) / 1000) for cid, at in chirps if start_ms <= at <= start_ms + length * 1000 - CHIRP_MS]
    when = datetime.datetime.fromtimestamp(start_ms / 1000)
    out = os.path.join(os.path.expanduser(args.out), f"REC_{when:%Y%m%d_%H%M%S}_{args.scenario}.mov")
    wav = out[:-4] + ".wav"
    write_wav(wav, cabin_sound(length, [at for _, at in inside], seed=seq))
    try:
        mux(clip, wav, out, length)
    finally:
        os.remove(wav)
    stamp_header(out, start_ms)
    print(f"{args.scenario}: {s['story']}")
    print(f"  detection   #{seq} {trigger} at {datetime.datetime.fromtimestamp(event_ms / 1000):%H:%M:%S.%f}"[:-3]
          + f", {s['event']:.1f} s into the clip")
    print(f"  video       starts {when:%H:%M:%S}, {length:.1f} s")
    if inside:
        for cid, at in inside:
            print(f"  chirp #{cid}    at {at:6.3f} s in the video")
    else:
        print("  no chirp fell inside this clip: the phone will place it by the file's clock (about a second)")
    print(f"  written     {out}\n  clip        {s['credit']}\n  watch       {s['watch'][0]:.1f} {s['watch'][1]:.1f} s")


def clip_seconds(path):
    """Duration of an MP4 from its movie header (timescale and duration)."""
    with open(path, "rb") as f:
        data = f.read()
    i = data.find(b"mvhd")
    version = data[i + 4]
    if version == 1:
        timescale, duration = struct.unpack(">IQ", data[i + 24:i + 36])
    else:
        timescale, duration = struct.unpack(">II", data[i + 16:i + 24])
    return duration / timescale


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("fetch", help="download and prepare the clips").set_defaults(run=fetch)
    m = sub.add_parser("make", help="build the dashcam video for a scenario just driven")
    m.add_argument("scenario", choices=sorted(SCENARIOS))
    m.add_argument("--data", default=os.path.join(REPO, "server-data"), help="the mirror server's data directory")
    m.add_argument("--device", help="device id (prefix) of the car; default: the most recently written")
    m.add_argument("--out", default=".", help="directory for the video")
    m.set_defaults(run=make)
    args = p.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
