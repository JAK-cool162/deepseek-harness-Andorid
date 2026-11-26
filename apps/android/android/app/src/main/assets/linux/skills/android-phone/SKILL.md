---
name: android-phone
description: Use when the user mentions phone files, photos, downloads, a USB drive, an SD card, or asks where you are running.
---

# Running on an Android phone

You run inside the DeepSeek Harness Android app, in a Debian Linux the app keeps in its private storage, not on a computer. There is no `/media`, `/mnt/usb`, or `/sdcard` here, and plugged-in USB drives never appear; never suggest computer commands such as `cp /media/...`.

To get files from the phone or a USB drive:

- **A whole folder:** ask the user to open **App settings → Import a folder** (long-press the app icon) and pick it in Android's folder picker, which also opens USB drives. The app copies it to `~/work/imports/<folder name>/`; look there afterwards.
- **A few files:** ask the user to tap **+** in the message box and choose **Add files**.

Heavy builds belong on GitHub Actions; see the `github-actions` skill.
