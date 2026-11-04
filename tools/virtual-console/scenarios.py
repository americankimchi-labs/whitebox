"""The demo video's scenarios, live through the Virtual Console, each timed to its stand-in dashcam clip.

    adb forward tcp:18088 tcp:8088
    tools/virtual-console/heartbeat.sh &          # keeps console writes live
    adb shell am start --user 10 -n com.americankimchilabs.whitebox/.MainActivity --es source LIVE --es mirror local
    python3 -I tools/virtual-console/scenarios.py cut-in
    python3 -I tools/virtual-console/dashcam.py make cut-in --data server-data

    cut-in       city traffic under an overpass: a car cuts across in front, the driver brakes and swerves a little,
                 contact (충돌); then Park: the car speaks its post-crash guidance. The evidence summary shows the
                 brake 1.2 s before contact.
    pile-up      highway at 100 km/h, a pile-up ahead: a hard brake that avoids it, then driving on slowly, so the car
                 asks "방금 상황 보관할까요?".
    garage       crawling in a parking garage, a stop, then a sudden lurch (급발진 pattern); then say
                 "방금 저장해" (the emulator's microphone is the computer's: `adb emu avd hostmicon`).
    rear-end     highway at 110 km/h, a slow truck in the lane, no brake: the impact at 108 km/h; on a test build an
                 airbag signal follows (a 큰 사고, marked as a test), then the car loses power: the mirror seals what
                 it received (10 s) and records it for the emergency contacts' page (60 s). Release builds have no
                 test airbag: it stays a 충돌.
    night        a hired driver at night (대리 운전 모드 on first): a car crosses, a hard brake, driving on.

The car plays its dashcam sync chirp as it leaves Park and every 15 s after; the scenarios with real dashcam footage
(cut-in, pile-up, rear-end; dashcam.py) put their moment where the clip has it, with a chirp inside the clip, and follow
the clip's own speeds. Speeds are scripted writes (integer km/h), so each
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


def cut_in():
    leave_park()
    speed(7, to=35, step=0.2, accelerator=20)                        # city traffic under the overpass
    at(35.8, "a car cuts across in front: brake (1.2 s before contact)")
    controls(brake=40, accelerator=0)
    speed(0.5, to=31, step=0.1)
    at(36.3, "swerve a little to the left")
    controls(steering=10)
    speed(0.7, to=28, step=0.1)
    say("t+37.0s  contact: 28 -> 8 km/h in one sample (충돌)")
    controls(speed=8, steering=0)
    time.sleep(0.1)
    speed(1.5, to=0, brake=100)
    speed(12, to=0, step=0.5)
    gear("Park")
    say("Park: the car speaks its post-crash guidance")


def pile_up():
    leave_park()
    speed(16, to=100, step=0.2, accelerator=30)                      # highway speed
    at(33.0, "a pile-up ahead: hard brake 100 -> 78 km/h in 1 s")
    controls(brake=50, accelerator=0)
    speed(1.0, to=78, step=0.1)
    controls(brake=20)
    speed(3.0, to=50, step=0.2)
    controls(brake=0, accelerator=10)
    say("driving on past it (the keep question comes 5 s after the brake; answer within 10 s, or stay silent to keep it)")
    speed(20, to=45, step=0.2)
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
    speed(18, to=113, step=0.2, accelerator=40)                      # onto the highway
    at(30.0, "cruising, easing off: 113 -> 108 km/h, no brake")
    controls(accelerator=0)
    speed(9.8, to=108, step=0.2)
    at(40.0, "a slow truck in the lane: impact at 108 km/h, 108 -> 86 in one sample, no brake")
    controls(speed=86)
    time.sleep(0.15)
    # On a test build the emulator's missing airbag is injected (recorded as a test); a release build ignores it. A
    # broadcast, so whatever is on the car's screen stays there.
    subprocess.run(["adb", "shell", "am", "broadcast", "--user", "10", "-a", f"{PKG}.TEST_AIRBAG", "-p", PKG],
                   check=False, capture_output=True)
    say("airbag signal (test build), the seal and its keys go out first")
    speed(2.2, to=57, step=0.2)
    controls(brake=30)
    speed(0.6, to=50, step=0.2)
    subprocess.run(["adb", "shell", "am", "force-stop", "--user", "10", PKG], check=False)
    say("POWER LOSS: the car stops sending; watch the dashboard (server seal +10 s, family page +60 s)")
    speed(5, to=0, step=0.5)
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


SCENARIOS = {"cut-in": cut_in, "pile-up": pile_up, "garage": garage, "rear-end": rear_end, "night": night}

if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in SCENARIOS:
        sys.exit("usage: scenarios.py " + "|".join(SCENARIOS))
    SCENARIOS[sys.argv[1]]()
    if sys.argv[1] in ("cut-in", "pile-up", "rear-end"):
        say(f"done: python3 -I tools/virtual-console/dashcam.py make {sys.argv[1]} --data <server data>")
    else:
        say("done")
