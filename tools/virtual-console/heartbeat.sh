#!/bin/zsh
# Keeps the Virtual Console server active the way an open console page does (1 Hz session POST).
# Without a live session the server reports emulator_active=false and drops writes.
# Prerequisite: adb forward tcp:18088 tcp:8088
while true; do curl -s -m 2 -X POST "http://localhost:18088/browser/session?client=whitebox-spike" >/dev/null; sleep 1; done
