# Recorded chain (provenance)

A real White Box chain recorded on the Pleos Connect emulator v2.1.0 (2026-10-30), copied off the device with
`adb shell run-as` and kept as evidence of what the app actually wrote. The directory holds the two files the app keeps
per chain: `chain.log` (one hash-linked record per line) and `seals.log` (signed capsule seals).

| Directory | What happened | Capsule |
|---|---|---|
| `live-run2-demo/` | A live Virtual Console drive (`tools/virtual-console/demo.py`): pull away, lane changes, ordinary brake, hard brake (ALERT), impact 50 → 25 km/h (TRIGGERED, primary ΔV), stillness → final seal. **Trimmed after record #999** for size (the app kept recording at 10 Hz while parked); a prefix of an append-only chain verifies unchanged. | `C720 DELTA_V #0–#861`, final |

Verify it, and see a one-byte change fail:

    tools/tamper-demo/run.sh docs/artifacts/live-run2-demo

Coordinates are the emulator's default GPS position, not a real person's location.
