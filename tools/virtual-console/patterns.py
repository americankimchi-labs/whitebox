"""Near-miss and 급발진 patterns, live through the Virtual Console: one scenario per detector, each checked in the chain.

    adb forward tcp:18088 tcp:8088
    tools/virtual-console/heartbeat.sh &          # keeps console writes live
    adb shell am start --user 10 -n com.americankimchilabs.whitebox/.MainActivity --es source LIVE
    python3 -I tools/virtual-console/patterns.py              # every scenario
    python3 -I tools/virtual-console/patterns.py evasive lurch

Each scenario starts from rest, drives into one pattern, stops and waits out stillness so its capsule seals before
the next one, then prints the TRIGGER / OFFER entries the recorder wrote meanwhile next to the one it expected.
Speeds are scripted writes (integer km/h), not the console's pedal physics, so each pattern lands exactly.
Not scriptable on the emulator: airbag (absent from the VHAL), brake held without slowing (needs a road grade; the
emulator's accelerometer does not follow the drive), parking brake while moving (the console only lights the
telltale and PARKING_BRAKE_ON stays false; `parking-brake` shows it). OFFER is the keep question, asked when the car is
still driving 5 s after a near-miss.
"""
import math
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from demo import controls, gear, put, reset, say, speed, state, steer, turn  # noqa: E402

PKG = "com.americankimchilabs.whitebox"
CHAIN = "files/chain/live/chain.log"


def chain_events():
    """The engine's TRIGGER and OFFER entries in the live chain, as (seq, kind, detail)."""
    out = subprocess.run(
        ["adb", "exec-out", "run-as", PKG, "--user", "10", "grep", "-a", "-E", r"\|(TRIGGER|OFFER)\|", CHAIN],
        capture_output=True, text=True,
    ).stdout
    events = []
    for line in out.splitlines():
        fields = line.split("\t")
        if len(fields) < 3:
            continue
        parts = fields[2].split("|")  # E|<timestamp>|TRIGGER|<trigger>|<cause>|<measured> or E|<ts>|OFFER|<id>|<trigger>|…
        events.append((int(fields[0]), parts[2], parts[3] if parts[2] == "TRIGGER" else parts[4],
                       parts[5] if parts[2] == "TRIGGER" else ""))
    return events


def body(**kw):
    put("/vehicle/body?" + "&".join(f"{k}={str(v).lower()}" for k, v in kw.items()))


def telltale(kind, on):
    put(f"/vehicle/telltales/simulator?type={kind}&state={'On' if on else 'Off'}&color=Red")


def start_rest():
    reset()
    gear("Drive")
    controls(brake=0, accelerator=0, steering=0)
    time.sleep(2)


def cruise(kmh):
    """Pull away gently (6 km/h/s, no lurch) and settle at `kmh`."""
    say(f"pull away to {kmh} km/h")
    speed(kmh / 6, to=kmh, step=0.2, accelerator=20)
    speed(3, step=0.2)


def stop_and_seal(rate=8):
    """Ordinary stop (`rate` km/h/s, below the hard-brake threshold), then wait out stillness so the capsule seals."""
    say("stop")
    speed(state["speed"] / rate, to=0, step=0.2, brake=20, accelerator=0)
    say("stillness (capsule seals after 10 s)")
    speed(12, to=0, step=0.5, brake=100)


# Scenarios: each returns after its capsule sealed. The docstring's first line is the expected trigger.

def evasive():
    """EVASIVE_STEER, then OFFER (still driving 5 s later)"""
    start_rest()
    cruise(60)
    say("sharp swerve 0 → 150° → 0 in 0.6 s, brake off")
    steer(0.6, 150)
    say("drive on 7 s (keep question at 5 s)")
    speed(7, step=0.2)
    stop_and_seal()


def lane_wheel(seconds, peak):
    """One full sine of the wheel (out, then back past centre, then straight): a lane change's steering."""
    n = round(seconds / 0.1)
    for i in range(1, n + 1):
        controls(steering=round(peak * math.sin(2 * math.pi * i / n)))
        time.sleep(0.1)
    controls(steering=0)


def lane_change():
    """LANE_CHANGE_NO_SIGNAL, then OFFER (still driving 5 s later)"""
    start_rest()
    turn("Off")
    cruise(80)
    say("lane change in 2 s with no turn signal: wheel ±20° (≈ 11°/s heading rate, 3.5 m over)")
    lane_wheel(2, 20)
    say("drive on 7 s (keep question at 5 s)")
    speed(7, step=0.2)
    stop_and_seal()


def lane_signalled():
    """nothing (the same lane change with the turn signal on)"""
    start_rest()
    cruise(80)
    turn("BlinkLeft")
    speed(1, step=0.2)
    lane_wheel(2, 20)
    turn("Off")
    speed(7, step=0.2)
    stop_and_seal()


def brake_swerve():
    """BRAKE_SWERVE, then OFFER"""
    start_rest()
    cruise(60)
    say("brake, then evasive S ±120° over 1.2 s")
    controls(brake=30, accelerator=0)
    speed(0.5, to=58)
    for target in (120, -120):
        steer(0.6, target)
    controls(brake=0)
    stop_and_seal()


def neutral():
    """SHIFT_TO_NEUTRAL, then OFFER"""
    start_rest()
    cruise(50)
    controls(accelerator=0)
    gear("Neutral")
    say("coast in N")
    speed(3, to=44, step=0.2)
    gear("Drive")
    stop_and_seal()


def brake_rise():
    """BRAKE_SPEED_RISE, then OFFER"""
    start_rest()
    cruise(30)
    say("brake held, speed rising 30 → 48 km/h over 4 s")
    controls(brake=40, accelerator=0)
    speed(4, to=48)
    controls(brake=0)
    stop_and_seal()


def pumping():
    """BRAKE_PUMPING (stops within 5 s: no OFFER)"""
    start_rest()
    cruise(50)
    controls(accelerator=0)
    say("brake pumped 3× in 2.4 s")
    for _ in range(3):
        controls(brake=30)
        speed(0.4, to=state["speed"] - 2)
        controls(brake=0)
        speed(0.4, to=state["speed"] - 1)
    stop_and_seal()


def lurch():
    """LURCH, then OFFER"""
    start_rest()
    say("standstill in D, brake off")
    controls(speed=0, brake=0)
    time.sleep(2)
    say("lurch 0 → 20 km/h in 0.8 s")
    speed(0.8, to=20, accelerator=60)
    say("drive on 7 s")
    speed(7, step=0.2, accelerator=10)
    stop_and_seal()


def rear_shove():
    """REAR_SHOVE (severe)"""
    start_rest()
    say("waiting at a light, brake held")
    controls(speed=0, brake=100)
    time.sleep(2)
    say("struck from behind: 0 → 9 km/h in one write")
    controls(speed=9)
    time.sleep(0.3)
    speed(1.5, to=0)
    say("stillness (capsule seals after 10 s)")
    speed(12, to=0, step=0.5)


def exit_after():
    """HARD_BRAKE, then POST_EVENT_EXIT"""
    start_rest()
    cruise(50)
    say("hard brake to a stop at 20 km/h/s")
    controls(brake=60, accelerator=0)
    speed(2.5, to=0)
    time.sleep(2)
    say("driver's door opens")
    body(door_fl=True)
    time.sleep(2)
    body(door_fl=False)
    say("stillness")
    speed(10, to=0, step=0.5, brake=100)


def parking_brake():
    """nothing: the console's parking brake lights the telltale only, PARKING_BRAKE_ON stays false"""
    start_rest()
    cruise(30)
    say("parking brake telltale on while moving")
    telltale("ParkingBrake", True)
    speed(3, step=0.2)
    telltale("ParkingBrake", False)
    stop_and_seal()


SCENARIOS = {
    "evasive": evasive, "brake-swerve": brake_swerve, "lane-change": lane_change, "lane-signalled": lane_signalled, "neutral": neutral, "brake-rise": brake_rise,
    "pumping": pumping, "lurch": lurch, "rear-shove": rear_shove, "exit": exit_after, "parking-brake": parking_brake,
}


def main(names):
    unknown = [n for n in names if n not in SCENARIOS]
    if unknown:
        sys.exit(f"unknown scenario {unknown}; choose from {list(SCENARIOS)}")
    results = []
    for name in names or SCENARIOS:
        fn = SCENARIOS[name]
        before = {e[0] for e in chain_events()}
        say(f"=== {name}: expect {fn.__doc__}")
        fn()
        time.sleep(1)
        got = [e for e in chain_events() if e[0] not in before]
        for seq, kind, what, measured in got:
            say(f"    chain #{seq} {kind} {what} {measured}")
        results.append((name, fn.__doc__, ", ".join(f"{k} {w}" for _, k, w, _ in got) or "nothing"))
    gear("Park")
    print("\nscenario       expected → recorded")
    for name, expected, got in results:
        print(f"{name:14} {expected} → {got}")


if __name__ == "__main__":
    main(sys.argv[1:])
