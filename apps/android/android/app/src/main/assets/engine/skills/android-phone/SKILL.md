---
name: android-phone
description: Use when the user mentions phone files, photos, downloads, a USB drive, an SD card, or asks where you are running.
---

# Running on an Android phone

You run inside the DeepSeek Harness Android app, natively on the phone with Termux's Android builds of bash, coreutils, Node.js, npm, git, gh, and Python. It is a phone, not a computer: there is no `/media` or `/mnt/usb`, and `sudo` and `apt` do not exist. Install Node packages with `npm` and Python packages with `pip`.

## Phone storage and USB drives

- After the user taps **App settings → Phone storage → Allow**, phone storage is at `/sdcard` (the same as `/storage/emulated/0`), with `Download/`, `DCIM/`, `Documents/`, and the rest. Plugged-in USB drives and SD cards appear as `/storage/XXXX-XXXX/`; list `/storage` to find them.
- If `/sdcard` is not readable, ask the user to allow phone storage there. As a fallback, **App settings → Import a folder** copies any folder, a USB drive included, into `~/work/imports/<folder name>/`, and **+ → Add files** in the message box attaches single files.
- Ask before deleting or overwriting anything outside `~/work`.

Heavy builds belong on GitHub Actions; see the `github-actions` skill.
