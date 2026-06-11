#!/usr/bin/env python3
"""
Record wake word reference samples for MFCC+DTW detector.

Two modes:
  --glasses  Record from glasses mic via ADB broadcast (default)
  --local    Record from local PC mic (requires sounddevice, soundfile, numpy)

Usage:
    python record_wake_refs.py                  # glasses mic mode
    python record_wake_refs.py --local          # local PC mic mode
    python record_wake_refs.py --clear          # clear all refs on phone
    python record_wake_refs.py --local --push   # push local recordings to phone

Controls:
    Enter  - start/stop recording
    p      - play back last recording (local mode) / pull & play (glasses mode)
    s      - save (local: save to disk; glasses: already saved)
    r      - retry (discard and re-record)
    q      - quit
"""

import argparse
import json
import os
import subprocess
import sys
import time

SAMPLE_RATE = 16000
PKG = "com.repository.listener"
RECEIVER = f"{PKG}/.adb.AdbCommandReceiver"
ACTION = f"{PKG}.ADB_COMMAND"


# --- ADB helpers ---

def adb(cmd, capture=True):
    """Run adb command, return stdout."""
    result = subprocess.run(
        f"adb {cmd}", shell=True, capture_output=capture, text=True
    )
    return result.stdout.strip() if capture else None


def adb_broadcast(cmd_type, command_id, params=None):
    """Send ADB broadcast command to the phone app."""
    params_json = json.dumps(params, separators=(',', ':')) if params else "{}"
    # Escape double quotes for device shell (goes through PC shell + adb shell)
    escaped = params_json.replace('"', '\\"')
    adb(
        f'shell am broadcast -n {RECEIVER} -a {ACTION} '
        f'--es type "{cmd_type}" --es command_id "{command_id}" '
        f'--es params "{escaped}"',
        capture=False
    )


def adb_read_result(command_id):
    """Read ADB command result JSON."""
    raw = adb(f"shell run-as {PKG} cat files/adb_results/{command_id}.json")
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


def get_ref_count():
    """Get current reference count from phone."""
    out = adb(f"shell run-as {PKG} ls files/mfcc_refs/ 2>/dev/null")
    if not out:
        return 0
    return len([f for f in out.split('\n') if f.endswith('.wav')])


# --- Glasses mic recording ---

def record_glasses(duration_ms, command_id):
    """Record from glasses mic via ADB broadcast. Returns result dict or None."""
    adb_broadcast("record_wake_ref", command_id, {"duration_ms": duration_ms})
    print(f"  [RECORDING for {duration_ms / 1000:.1f}s -- SAY THE WAKE WORD!]")

    # Wait for recording to complete
    wait_s = duration_ms / 1000 + 2
    time.sleep(wait_s)

    result = adb_read_result(f"{command_id}_done")
    if result and result.get("status") == "success":
        data = result.get("data", {})
        filename = data.get("filename", "?")
        count = data.get("ref_count", "?")
        print(f"  Saved on phone: {filename} (total: {count} refs)")
        return result
    else:
        error = result.get("error", "Unknown error") if result else "No response"
        print(f"  Recording failed: {error}")
        return None


def pull_and_play_glasses(filename):
    """Pull a WAV from phone and play it locally."""
    try:
        import sounddevice as sd
        import soundfile as sf_mod
    except ImportError:
        print("  Cannot play: install sounddevice, soundfile, numpy")
        return

    tmp = f"/tmp/{filename}"
    result = subprocess.run(
        f"adb exec-out run-as {PKG} cat files/mfcc_refs/{filename}",
        shell=True, capture_output=True
    )
    if result.returncode != 0 or len(result.stdout) < 44:
        print("  Cannot pull file from phone")
        return

    with open(tmp, 'wb') as f:
        f.write(result.stdout)

    audio, sr = sf_mod.read(tmp)
    print("  [Playing...]", end="", flush=True)
    sd.play(audio, samplerate=sr)
    sd.wait()
    print(" done")
    os.remove(tmp)


def delete_last_ref_glasses(filename):
    """Delete a specific reference from phone."""
    adb(f"shell run-as {PKG} rm files/mfcc_refs/{filename}")
    # Also remove cached .mfcc
    base = filename.replace(".wav", "")
    adb(f"shell run-as {PKG} rm files/mfcc_refs/{base}.mfcc 2>/dev/null")
    print(f"  Deleted: {filename}")


def clear_refs_glasses():
    """Clear all references on phone."""
    adb_broadcast("clear_wake_refs", "clr")
    time.sleep(1)
    result = adb_read_result("clr")
    if result:
        print(f"  Cleared. Remaining refs: {result.get('data', {}).get('ref_count', 0)}")
    else:
        print("  Clear command sent.")


# --- Local mic recording ---

def record_local(device=None):
    """Record from local mic until Enter pressed."""
    import sounddevice as sd
    import numpy as np
    import threading

    chunks = []
    recording = threading.Event()
    recording.set()

    def callback(indata, frames, time_info, status):
        if recording.is_set():
            chunks.append(indata.copy())

    stream = sd.InputStream(
        samplerate=SAMPLE_RATE, channels=1, dtype='int16',
        device=device, callback=callback
    )

    print("  [Recording... press Enter to stop]")
    stream.start()
    input()
    recording.clear()
    stream.stop()
    stream.close()

    if not chunks:
        return None

    audio = np.concatenate(chunks, axis=0).flatten()
    duration = len(audio) / SAMPLE_RATE
    print(f"  Recorded {duration:.2f}s ({len(audio)} samples)")
    return audio


def play_local(audio):
    """Play back a local recording."""
    import sounddevice as sd
    print("  [Playing...]", end="", flush=True)
    sd.play(audio, samplerate=SAMPLE_RATE)
    sd.wait()
    print(" done")


def save_local(audio, output_dir, index):
    """Save local recording as WAV."""
    import soundfile as sf_mod
    os.makedirs(output_dir, exist_ok=True)
    filename = f"ref_{index:03d}.wav"
    filepath = os.path.join(output_dir, filename)
    sf_mod.write(filepath, audio, SAMPLE_RATE, subtype='PCM_16')
    print(f"  Saved: {filepath}")
    return filepath


def push_to_phone(output_dir):
    """Push local WAV files to phone."""
    wavs = sorted([f for f in os.listdir(output_dir) if f.endswith(".wav")])
    if not wavs:
        print("No WAV files to push.")
        return

    print(f"\nPushing {len(wavs)} files to phone...")
    adb(f"shell run-as {PKG} mkdir -p files/mfcc_refs")
    for wav in wavs:
        src = os.path.join(output_dir, wav)
        ret = os.system(
            f'adb push "{src}" /sdcard/Download/{wav} > /dev/null && '
            f'adb shell "cat /sdcard/Download/{wav} | run-as {PKG} sh -c \'cat > files/mfcc_refs/{wav}\'" && '
            f'adb shell rm /sdcard/Download/{wav}'
        )
        status = "OK" if ret == 0 else "FAILED"
        print(f"  {status}: {wav}")
    print("Done. Restart the phone app to load new references.")


def get_local_next_index(output_dir):
    """Find next available ref index in local dir."""
    if not os.path.exists(output_dir):
        return 1
    existing = [f for f in os.listdir(output_dir) if f.startswith("ref_") and f.endswith(".wav")]
    if not existing:
        return 1
    indices = []
    for f in existing:
        try:
            indices.append(int(f.replace("ref_", "").replace(".wav", "")))
        except ValueError:
            pass
    return max(indices) + 1 if indices else 1


# --- Main flows ---

def glasses_flow(duration_ms):
    """Interactive glasses mic recording session."""
    ref_count = get_ref_count()
    print(f"Glasses mic recording mode")
    print(f"Existing references on phone: {ref_count}")
    print(f"Recording duration: {duration_ms / 1000:.1f}s (first 1s discarded for BT flush)")
    print(f"Say the wake word ~2s after pressing Enter")
    print(f"Target: 5-8 samples for good detection")
    print("=" * 50)

    rec_index = 0
    last_filename = None

    while True:
        rec_index += 1
        print(f"\n--- Sample #{rec_index} ---")
        print("  Press Enter to start recording (q to quit):", end=" ", flush=True)
        cmd = input().strip().lower()
        if cmd == 'q':
            break

        command_id = f"rec_{rec_index}_{int(time.time())}"
        result = record_glasses(duration_ms, command_id)

        if result is None:
            rec_index -= 1
            continue

        last_filename = result.get("data", {}).get("filename")

        while True:
            print("  [p]lay / [k]eep / [r]etry (delete & re-record) / [q]uit:", end=" ", flush=True)
            action = input().strip().lower()

            if action == 'p':
                if last_filename:
                    pull_and_play_glasses(last_filename)
                else:
                    print("  No file to play.")
            elif action == 'k':
                ref_count = get_ref_count()
                print(f"  Kept. Total refs: {ref_count}")
                break
            elif action == 'r':
                if last_filename:
                    delete_last_ref_glasses(last_filename)
                rec_index -= 1
                break
            elif action == 'q':
                ref_count = get_ref_count()
                print(f"\nTotal references on phone: {ref_count}")
                return
            else:
                print("  Unknown command. Use p/k/r/q.")

    ref_count = get_ref_count()
    print(f"\nTotal references on phone: {ref_count}")


def local_flow(output_dir, device):
    """Interactive local mic recording session."""
    import sounddevice as sd

    devices = sd.query_devices()
    print("\n--- Audio Devices ---")
    for i, d in enumerate(devices):
        if d['max_input_channels'] > 0:
            marker = " <-- default" if i == sd.default.device[0] else ""
            print(f"  [{i}] {d['name']} ({d['max_input_channels']}ch){marker}")
    print()

    if device is not None:
        info = sd.query_devices(device)
        print(f"Using device [{device}]: {info['name']}\n")
    else:
        info = sd.query_devices(sd.default.device[0])
        print(f"Using default device: {info['name']}\n")

    index = get_local_next_index(output_dir)
    saved_count = index - 1
    print(f"Output: {os.path.abspath(output_dir)}")
    print(f"Existing references: {saved_count}")
    print(f"Target: 5-8 samples for good detection")
    print("=" * 50)

    while True:
        print(f"\n--- Sample #{index} ---")
        print("  Press Enter to start recording (q to quit):", end=" ", flush=True)
        cmd = input().strip().lower()
        if cmd == 'q':
            break

        audio = record_local(device)
        if audio is None or len(audio) < SAMPLE_RATE * 0.3:
            print("  Too short (< 0.3s), try again.")
            continue

        while True:
            print("  [p]lay / [s]ave / [r]etry / [q]uit:", end=" ", flush=True)
            action = input().strip().lower()

            if action == 'p':
                play_local(audio)
            elif action == 's':
                save_local(audio, output_dir, index)
                index += 1
                saved_count += 1
                print(f"  Total saved: {saved_count}")
                break
            elif action == 'r':
                print("  Discarded.")
                break
            elif action == 'q':
                print(f"\nTotal saved: {saved_count} in {os.path.abspath(output_dir)}")
                if saved_count > 0:
                    print(f"Push to phone: python {sys.argv[0]} --local --push -o {output_dir}")
                return
            else:
                print("  Unknown command. Use p/s/r/q.")

    print(f"\nTotal saved: {saved_count} in {os.path.abspath(output_dir)}")
    if saved_count > 0:
        print(f"Push to phone: python {sys.argv[0]} --local --push -o {output_dir}")


def main():
    parser = argparse.ArgumentParser(description="Record wake word references")
    parser.add_argument("--local", action="store_true",
                        help="Record from local PC mic instead of glasses")
    parser.add_argument("--output-dir", "-o", default="./wake_refs",
                        help="Directory for local recordings (default: ./wake_refs)")
    parser.add_argument("--device", "-d", type=int, default=None,
                        help="Local audio input device index")
    parser.add_argument("--duration", type=int, default=5000,
                        help="Glasses recording duration in ms (default: 5000, first 1s discarded for BT flush)")
    parser.add_argument("--list-devices", "-l", action="store_true",
                        help="List local audio devices and exit")
    parser.add_argument("--push", action="store_true",
                        help="Push local recordings to phone and exit")
    parser.add_argument("--clear", action="store_true",
                        help="Clear all references on phone and exit")
    args = parser.parse_args()

    if args.list_devices:
        import sounddevice as sd
        devices = sd.query_devices()
        print("\n--- Audio Devices ---")
        for i, d in enumerate(devices):
            if d['max_input_channels'] > 0:
                marker = " <-- default" if i == sd.default.device[0] else ""
                print(f"  [{i}] {d['name']} ({d['max_input_channels']}ch){marker}")
        return

    if args.clear:
        clear_refs_glasses()
        return

    if args.push:
        push_to_phone(args.output_dir)
        return

    if args.local:
        local_flow(args.output_dir, args.device)
    else:
        glasses_flow(args.duration)


if __name__ == "__main__":
    main()
