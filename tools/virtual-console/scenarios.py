"""The demo video's scenarios, live through the Virtual Console, each timed to its stand-in dashcam clip.

    adb forward tcp:18088 tcp:8088
    tools/virtual-console/heartbeat.sh &          # keeps console writes live
    adb shell am start --user 10 -n com.americankimchilabs.whitebox/.MainActivity --es source LIVE --es mirror local
    python3 -I tools/virtual-console/scenarios.py cut-in
    python3 -I tools/virtual-console/dashcam.py make cut-in --data server-data

    lane-change  a lane change to the left with the signal on; contact mid-change (충돌), then Park: the car speaks
                 its post-crash guidance. The evidence summary shows the signal 4.2 s and the brake 1.4 s before.
    cut-in       highway, a car cuts in: a hard brake, then driving on, so the car asks "방금 상황 보관할까요?".
    garage       crawling in a parking garage, a stop, then a sudden lurch (급발진 pattern); then say
                 "방금 저장해" (the emulator's microphone is the computer's: `adb emu avd hostmicon`).
    rear-end     slow traffic, a rear-end crash; on a test build an airbag signal follows (a 큰 사고, marked
                 as a test), then the car loses power: the mirror seals what it received (10 s) and records it for
                 the emergency contacts' page (60 s). Release builds have no test airbag: it stays a 충돌.
    night        a hired driver at night (대리 운전 모드 on first): a car crosses, a hard brake, driving on.

The car plays its dashcam sync chirp as it leaves Park and every 15 s after; each scenario puts its moment where the
dashcam clip has it (dashcam.py) with a chirp inside the clip. Speeds are scripted writes (integer km/h), so each
pattern lands exactly; each console write reaches the car about 0.2 s later, so the moments are scripted that much
early. A crash is a one-sample drop with the brake at most part-way down: a full brake hands the speed to the
console's own pedal physics, which ramps it instead. Steering: the console's sign is the opposite of the SDK's, so a positive value is a left turn.
"""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from demo import controls, gear, reset, say, speed, state, turn  # noqa: E402

PKG = "com.americankimchilabs.whitebox"
clock = {"drive": None}


def leave_park():
    """From rest in Park into Drive: the car's chirp schedule starts here (t = 0 for `at`)."""
    reset()
    gear("Park")
    controls(brake=100, accelerator=0, steering=0)
    turn("Off")
    time.sleep(3)
    gear("Drive")
    clock["drive"] = time.time()
    controls(brake=0)


def at(t, what=None):
    """Waits until `t` seconds after leaving Park (keeping the current speed, written every 0.2 s)."""
    while True:
        left = clock["drive"] + t - time.time()
        if left <= 0:
            break
        speed(min(0.2, left), step=min(0.2, left))
    if what:
        say(f"t+{t:.1f}s  {what}")


def settle_and_park(rate=8):
    say("ordinary stop, stillness (the capsule seals 10 s later), Park")
    controls(brake=20, accelerator=0)
    speed(max(0.5, state["speed"] / rate), to=0, step=0.2)
    speed(12, to=0, step=0.5, brake=100)
    gear("Park")


def lane_change():
    leave_park()
    speed(10, to=60, step=0.2, accelerator=25)                       # pull away, 6 km/h/s
    at(31.0, "left turn signal (4.2 s before contact)")
    turn("BlinkLeft")
    at(33.4, "steer left into the next lane")
    controls(steering=8)
    at(33.8, "brake (1.4 s before contact)")
    controls(brake=25, accelerator=0)
    speed(1.2, to=55, step=0.1, steering=12)
    say("t+35.0s  contact: 55 -> 30 km/h in one sample (충돌)")
    controls(speed=30, steering=0)
    time.sleep(0.1)
    turn("Off")
    speed(2, to=0, brake=100)
    speed(12, to=0, step=0.5)
    gear("Park")
    say("Park: the car speaks its post-crash guidance")


def cut_in():
    leave_park()
    speed(16, to=100, step=0.2, accelerator=30)                      # highway speed
    at(33.0, "a car cuts in: hard brake 100 -> 78 km/h in 1 s")
    controls(brake=50, accelerator=0)
    speed(1.0, to=78, step=0.1)
    controls(brake=0, accelerator=20)
    say("driving on (the keep question comes 5 s after the brake; answer within 10 s, or stay silent to keep it)")
    speed(20, step=0.2)
    settle_and_park()


def garage():
    leave_park()
    speed(2, to=10, step=0.2, accelerator=10)                        # crawl through the garage
    at(12.0, "slow to a stop")
    controls(brake=30, accelerator=0)
    speed(2.5, to=0, step=0.25)
    at(17.0, "standing still... then a sudden lurch 0 -> 18 km/h (급발진 pattern)")
    controls(brake=0, accelerator=90)
    speed(0.5, to=18, step=0.1)
    controls(brake=100, accelerator=0)
    speed(1.2, to=0, step=0.1)
    say("now say \"방금 저장해\" (voice bookmark); waiting 15 s")
    speed(15, to=0, step=0.5)
    gear("Park")


def rear_end():
    leave_park()
    speed(5, to=20, step=0.2, accelerator=15)                        # slow traffic
    for t, kmh in ((12, 12), (18, 22), (24, 15), (30, 20), (34, 24)):
        steps = max(1, round((t - (time.time() - clock["drive"])) / 0.2))
        speed(steps * 0.2, to=kmh, step=0.2)
    at(34.6, "the driver brakes too late")
    controls(brake=30, accelerator=0)
    at(35.0, "rear-end: 24 -> 0 km/h in one sample")
    controls(speed=0)  # a full brake would hand the speed to the console's own pedal physics, which ramps it down
    time.sleep(0.15)
    # On a test build the emulator's missing airbag is injected (recorded as a test); a release build ignores it.
    subprocess.run(["adb", "shell", "am", "start", "--user", "10", "-n", f"{PKG}/.MainActivity", "--ez", "test_airbag", "true"],
                   check=False, capture_output=True)
    say("airbag signal (test build), the seal and its keys go out first")
    time.sleep(1.5)
    subprocess.run(["adb", "shell", "am", "force-stop", "--user", "10", PKG], check=False)
    say("POWER LOSS: the car stops sending; watch the dashboard (server seal +10 s, family page +60 s)")
    time.sleep(2)
    gear("Park")


def night():
    from handover import phase
    reset()
    gear("Park")
    if phase() != "ARMED":
        say("turn 대리 운전 모드 on (car screen, parked, or the owner's phone); waiting…")
        while phase() != "ARMED":
            time.sleep(1)
    say("armed")
    leave_park()
    speed(7, to=40, step=0.2, accelerator=20)
    at(13.0, "a car crosses: hard brake 40 -> 18 km/h in 1 s")
    controls(brake=50, accelerator=0)
    speed(1.0, to=18, step=0.1)
    controls(brake=0, accelerator=20)
    speed(4, to=35, step=0.2)
    speed(8, step=0.2)
    settle_and_park()
    say("end the trip from the owner's phone (지금 끝내기), or leave it parked for 10 minutes")


SCENARIOS = {"lane-change": lane_change, "cut-in": cut_in, "garage": garage, "rear-end": rear_end, "night": night}

if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in SCENARIOS:
        sys.exit("usage: scenarios.py " + "|".join(SCENARIOS))
    SCENARIOS[sys.argv[1]]()
    say(f"done: python3 -I tools/virtual-console/dashcam.py make {sys.argv[1]} --data <server data>")
