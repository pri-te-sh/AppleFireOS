#!/usr/bin/env bash
# Installs AppleFireOS on a Fire TV and switches on everything Fire OS only lets a computer grant:
# the Home button redirect, the Top Shelf (notification access), Screen Time and TV Insights.
#
#   ./setup.sh <tv-ip>                      install the latest release and set it up
#   ./setup.sh <tv-ip> --tmdb <key>         …and give it a TMDB key for Top Shelf artwork
#   ./setup.sh <tv-ip> --apk <file>         use this APK instead of downloading the release
#   ./setup.sh <tv-ip> --uninstall          put the Amazon home screen back and remove AppleFireOS
#
# Before running: on the TV, Settings › My Fire TV › Developer Options › ADB Debugging → On.
# Needs adb (macOS: brew install android-platform-tools; Linux: apt install adb; or set $ADB).
set -euo pipefail

PKG=com.applefireos.home
RELEASE=https://github.com/pri-te-sh/AppleFireOS/releases/latest/download/AppleFireOS.apk
cd "$(dirname "$0")"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

[[ $# -ge 1 && "$1" != -* ]] || { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
TV="$1"; shift
[[ "$TV" == *:* ]] || TV="$TV:5555"
APK="" TMDB="" UNINSTALL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk) APK="$2"; shift 2 ;;
        --tmdb) TMDB="$2"; shift 2 ;;
        --uninstall) UNINSTALL=1; shift ;;
        *) die "Unknown option: $1" ;;
    esac
done

ADB="${ADB:-$(command -v adb || true)}"
[[ -n "$ADB" ]] || die "adb not found. Install it (macOS: brew install android-platform-tools) or set ADB=/path/to/adb."
adb() { "$ADB" -s "$TV" "$@"; }
shell() { adb shell "$@" | tr -d '\r'; }

say "Connecting to $TV…"
"$ADB" connect "$TV" >/dev/null || true
for _ in $(seq 60); do
    state=$("$ADB" -s "$TV" get-state 2>/dev/null || true)
    [[ "$state" == device ]] && break
    [[ $_ == 3 ]] && echo "  If the TV asks to allow USB debugging from this computer, choose Allow."
    sleep 1
    "$ADB" connect "$TV" >/dev/null 2>&1 || true
done
[[ "${state:-}" == device ]] || die "Couldn't connect. Check the IP address and that ADB Debugging is on."

SERVICE="$PKG/$PKG.HomeRedirectService"
LISTENER="$PKG/$PKG.RecsListener"

if [[ -n "$UNINSTALL" ]]; then
    say "Removing AppleFireOS…"
    current=$(shell settings get secure enabled_accessibility_services)
    rest=$(printf '%s' "$current" | tr ':' '\n' | grep -vx "$SERVICE" | grep -v '^null$' | paste -sd: - || true)
    if [[ -n "$rest" ]]; then shell settings put secure enabled_accessibility_services "$rest"
    else shell settings delete secure enabled_accessibility_services >/dev/null; fi
    adb uninstall "$PKG" >/dev/null || true
    say "Done. The Home button opens the Amazon home screen again."
    exit 0
fi

if [[ -z "$APK" ]]; then
    for f in build/AppleFireOS.apk AppleFireOS.apk; do [[ -f "$f" ]] && APK="$f" && break; done
fi
if [[ -z "$APK" ]]; then
    say "Downloading the latest AppleFireOS…"
    APK="$(mktemp -d)/AppleFireOS.apk"
    curl -fsSL -o "$APK" "$RELEASE" || die "Download failed: $RELEASE"
fi

say "Installing $APK…"
adb install -r "$APK" >/dev/null

say "Switching on the Home button, Top Shelf, Screen Time and Insights…"
# The Home button: an accessibility service that sends Home presses to AppleFireOS. Added to any
# services already on, not replacing them.
current=$(shell settings get secure enabled_accessibility_services)
if [[ ":$current:" != *":$SERVICE:"* ]]; then
    [[ -z "$current" || "$current" == null ]] && list="$SERVICE" || list="$current:$SERVICE"
    shell settings put secure enabled_accessibility_services "$list"
fi
shell settings put secure accessibility_enabled 1
# The Top Shelf: reads the "continue watching" cards apps post (Netflix and others) and the VPN status.
shell cmd notification allow_listener "$LISTENER" >/dev/null || true
# Screen Time and TV Insights.
shell appops set "$PKG" GET_USAGE_STATS allow
for p in PACKAGE_USAGE_STATS DUMP WRITE_SECURE_SETTINGS; do
    shell pm grant "$PKG" "android.permission.$p" || true
done

if [[ -n "$TMDB" ]]; then
    say "Adding your TMDB key…"
    shell am start -n "$PKG/.NativeHome" --es tmdbKey "$TMDB" >/dev/null
fi
shell am start -n "$PKG/.NativeHome" >/dev/null

say "AppleFireOS is ready. Press Home on the remote."
