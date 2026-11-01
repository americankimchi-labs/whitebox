"""The demo drive, live through the Virtual Console (same story and timing as DemoDrive.kt in :vehicle-fake).

    adb forward tcp:18088 tcp:8088
    tools/virtual-console/heartbeat.sh &          # keeps console writes live
    adb shell am start --user 10 -n com.americankimchilabs.whitebox/.MainActivity --es source LIVE
    python3 -I tools/virtual-console/demo.py
    python3 -I tools/virtual-console/demo.py --power-loss   # the app is stopped 0.3 s after the impact

With --power-loss the recorder stops (adb force-stop) right after the crash, standing in for a head unit that loses
power: a mirror server running on this computer then seals what it received and issues the emergency notice.

Pull out of Park → lane change left with the turn signal → ordinary brake → lane change right → hard brake
(ALERT) → crash-profile speed drop 50 → 25 km/h in one write (TRIGGERED, primary ΔV) → stillness 12 s
(capsule seals) → Park. Speed is written every 100 ms around the crash so the drop lands in one VHAL sample;
the console's own pedal physics can't reach the trigger.
Steering: the console sign is the negative of what the SDK reports, so "+12" here shows as −12 in the app,
matching DemoDrive's left lane change.
"""
import math
import subprocess
import sys
import time
import json
import urllib.request

BASE = "http://localhost:18088"
state = {"speed": 0, "accelerator": 0, "brake": 0, "steering": 0}
t0 = time.time()


def put(path):
    urllib.request.urlopen(urllib.request.Request(BASE + path, method="PUT"), timeout=5).read()


def say(msg):
    print(f"{time.time() - t0:6.1f}s  {msg}", flush=True)


def controls(**kw):
    state.update(kw)
    put("/vehicle/controls?" + "&".join(f"{k}={v}" for k, v in state.items()))


def gear(g):
    put(f"/vehicle/gear?gear={g}")
    say(f"gear {g}")
    if g == "Park" and state.get("brake", 0) > 0:
        # In P a driver lets go of the brake; a held pedal would show 밟음 on a parked car.
        time.sleep(0.5)
        controls(brake=0)


def turn(mode):
    put(f"/vehicle/turn-signal?mode={mode}")
    say(f"turn signal {mode}")


def speed(seconds, to=None, step=0.1, **kw):
    """Linear speed change to `to` (km/h) over `seconds`, one write per `step`."""
    start = state["speed"]
    to = start if to is None else to
    n = max(1, round(seconds / step))
    for i in range(1, n + 1):
        controls(speed=round(start + (to - start) * i / n), **kw)  # the console accepts integer km/h only
        time.sleep(step)


def steer(seconds, peak):
    n = round(seconds / 0.1)
    for i in range(1, n + 1):
        controls(steering=round(peak * math.sin(math.pi * i / n)))
        time.sleep(0.1)
    controls(steering=0)


def reset():
    """Bring the console to rest physically: a jump from a previous run's speed to 0 (or one made while braking) is
    exactly what the trigger conditions look for. Ramp down at 10 km/h/s with the brake off, then wait out stillness."""
    now = json.load(urllib.request.urlopen(BASE + "/vehicle/state", timeout=5))["controls"]
    state.update(speed=int(now["speed_kph"]), accelerator=0, brake=0, steering=0)
    controls()
    if state["speed"] > 0:
        say(f"resetting from {state['speed']} km/h")
        speed(state["speed"] / 10, to=0)
        time.sleep(11)


def main():
    reset()
    say("parked")
    gear("Park")
    controls(brake=100)
    turn("Off")
    time.sleep(3)
    gear("Drive")
    time.sleep(1)
    controls(brake=0)
    say("pull away")
    speed(8, to=50, step=0.2, accelerator=20)

    turn("BlinkLeft")
    speed(2, step=0.2)
    say("lane change left")
    steer(2, 12)
    turn("Off")
    speed(6, step=0.2)

    say("ordinary brake")
    speed(2, to=40, step=0.2, brake=20, accelerator=0)
    controls(brake=0)
    speed(5, to=60, step=0.2, accelerator=20)

    turn("BlinkRight")
    speed(2, step=0.2)
    say("lane change right")
    steer(2, -12)
    turn("Off")
    speed(18.6, step=0.2)

    say("hard brake (ALERT)")
    speed(0.5, to=50, brake=30, accelerator=0)
    speed(0.3)
    say("impact: 50 -> 25 km/h")
    controls(speed=25)
    time.sleep(0.1)
    if "--power-loss" in sys.argv:
        time.sleep(0.2)
        subprocess.run(["adb", "shell", "am", "force-stop", "--user", "10", "com.americankimchilabs.whitebox"], check=False)
        say("POWER LOSS: the app was stopped; watch the mirror dashboard (server seal at +10 s, notice at +60 s)")
        speed(2, to=0, brake=100)
        gear("Park")
        return
    speed(2, to=0, brake=100)
    say("stillness (capsule seals after 10 s)")
    speed(12, to=0, step=0.5)
    gear("Park")
    say("done")


if __name__ == "__main__":
    sys.exit(main())
