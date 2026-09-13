#!/usr/bin/env bash
# Resolves which device the dev scripts talk to, so every one of them targets the
# same thing without repeating the logic.
#
# A plugged-in phone always wins over the emulator: if you went to the trouble of
# connecting it, that is the thing you want to look at. Set QUIRE_DEVICE to a
# serial from `adb devices` to override.
resolve_device() {
  if [ -n "${QUIRE_DEVICE:-}" ]; then echo "$QUIRE_DEVICE"; return 0; fi
  local phone emu
  phone=$(adb devices | awk '$2=="device" && $1 !~ /^emulator-/ {print $1; exit}')
  [ -n "$phone" ] && { echo "$phone"; return 0; }
  emu=$(adb devices | awk '$2=="device" && $1 ~ /^emulator-/ {print $1; exit}')
  [ -n "$emu" ] && { echo "$emu"; return 0; }
  return 1
}
