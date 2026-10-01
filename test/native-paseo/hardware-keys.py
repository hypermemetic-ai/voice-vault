#!/usr/bin/env python3
"""Offline fixture host: real emulator volume keys, never a physical device."""
import re
import subprocess
import sys
import time

adb, serial, log = sys.argv[1:]
if not re.fullmatch(r"emulator-[0-9]+", serial):
    raise SystemExit("Refusing non-emulator target")
command = [adb, "-s", serial]
devices = subprocess.run(command + ["shell", "getevent", "-lp"], capture_output=True, text=True, check=True).stdout
keyboard = None
for block in re.split(r"(?=add device )", devices):
    match = re.search(r"add device [0-9]+: (/dev/input/event[0-9]+)", block)
    if match and "KEY_VOLUMEDOWN" in block and "keyboard" in block.lower():
        keyboard = match[1]
        break
if keyboard is None:
    raise SystemExit("Isolated emulator hardware keyboard unavailable")
process = subprocess.Popen(command + ["shell", "am", "instrument", "-w",
    "sh.paseo.debug/ai.hypermemetic.voicevault.PaseoFixtureTest"],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
try:
    with open(log, "w") as evidence:
        for line in process.stdout:
            print(line, end="", flush=True)
            evidence.write(line)
            evidence.flush()
            request = re.search(r"HARDWARE_KEY (24|25) ([12])\s*$", line)
            if request:
                linux_code = "115" if request[1] == "24" else "114"
                for press in range(int(request[2])):
                    if press:
                        time.sleep(0.06)
                    script = "\n".join([
                        f"sendevent {keyboard} 1 {linux_code} 1",
                        f"sendevent {keyboard} 0 0 0",
                        f"sendevent {keyboard} 1 {linux_code} 0",
                        f"sendevent {keyboard} 0 0 0",
                    ]) + "\n"
                    result = subprocess.run(command + ["shell", "sh", "-s"], input=script,
                        capture_output=True, text=True, timeout=5)
                    if result.returncode != 0 or result.stderr:
                        raise RuntimeError("Emulator key injection failed: " + result.stdout + result.stderr)
    raise SystemExit(process.wait())
finally:
    if process.poll() is None:
        process.terminate()
        process.wait(timeout=5)
