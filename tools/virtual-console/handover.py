"""A handover (대리 운전) trip, live through the Virtual Console: arm it, drive it, read back what the car recorded.

    adb forward tcp:18088 tcp:8088
    tools/virtual-console/heartbeat.sh &          # keeps console writes live
    adb shell am start --user 10 -n com.americankimchilabs.whitebox/.MainActivity --es source LIVE
    python3 -I tools/virtual-console/handover.py

First turn handover mode on: on the car screen (parked: 대리 운전 모드 → 켜기) or on the owner's phone page; the
script waits until the car is armed. Then it drives: pull away, a hard acceleration (+15 km/h in one second), a
cruise, a hard brake to a stop, Park. It prints every handover entry the car wrote. On a real car the trip then ends
when the engine is switched off (final 10 minutes later unless the car drives off again); the emulator's ignition
cannot be changed from outside, so end it from the owner's phone (지금 끝내기) or leave it parked for 10 minutes.
Not on the emulator: speeding at enforcement cameras and leaving the route (the navigation's position does not follow
the console), so those appear only in the simulated car's run (`./gradlew :server:demoCar --args="--handover"`).
"""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from demo import controls, gear, reset, say, speed  # noqa: E402

PKG = "com.americankimchilabs.whitebox"
CHAIN = "files/chain/live/chain.log"
STATE = "files/chain/live/handover"


def car_file(path, *grep):
    cmd = ["adb", "exec-out", "run-as", PKG, "--user", "10"] + (["grep", "-a", *grep, path] if grep else ["cat", path])
    return subprocess.run(cmd, capture_output=True, text=True).stdout


def phase():
    return (car_file(STATE).split("|") or ["OFF"])[0].strip() or "OFF"


def handover_entries(after_seq=-1):
    entries = []
    for line in car_file(CHAIN, "-F", "H|").splitlines():
        fields = line.split("\t")
        if len(fields) >= 3 and fields[2].startswith("H|") and int(fields[0]) > after_seq:
            entries.append((int(fields[0]), "|".join(fields[2].split("|")[2:])))
    return entries


def main():
    reset()
    gear("Park")
    before = max([seq for seq, _ in handover_entries()] or [-1])
    if phase() != "ARMED":
        say("turn handover mode on (car screen: 대리 운전 모드 → 켜기, or the owner's phone); waiting…")
        while phase() != "ARMED":
            time.sleep(1)
    say("armed")
    time.sleep(2)
    gear("Drive")
    say("pull away to 30 km/h")
    speed(5, to=30, step=0.2, accelerator=20)
    speed(3, step=0.2)
    say("hard acceleration: +15 km/h in one second")
    speed(1, to=45, step=0.25, accelerator=80)
    speed(4, step=0.2, accelerator=20)
    say("hard brake to a stop at 20 km/h/s")
    controls(brake=60, accelerator=0)
    speed(2.25, to=0)
    speed(12, to=0, step=0.5, brake=100)
    gear("Park")
    time.sleep(2)
    say(f"handover now {phase()}; recorded:")
    for seq, entry in handover_entries(before):
        say(f"    #{seq} {entry}")


if __name__ == "__main__":
    main()
