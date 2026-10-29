#!/usr/bin/env zsh
# Optional: turns off Amazon background services that most people never use, to free memory on
# the TV (telemetry, metrics, Shop, miniTV, Photos, Kids, Alexa smart-home/multi-room extras).
# Updates, the Appstore, Alexa voice and the keyboard are deliberately left alone. Nothing is
# uninstalled: `restore` turns every one back on.
#   tools/debloat.sh <tv-ip> status    show each one's state
#   tools/debloat.sh <tv-ip> disable   turn them off
#   tools/debloat.sh <tv-ip> restore   turn them all back on
set -euo pipefail
ADB="${ADB:-$(command -v adb || true)}"
[[ -n "$ADB" ]] || { echo "adb not found: install it or set ADB=/path/to/adb" >&2; exit 1; }
[[ $# -ge 1 ]] || { echo "usage: $0 <tv-ip> [status|disable|restore]"; exit 2; }
FIRETV="$1"; shift
[[ "$FIRETV" == *:* ]] || FIRETV="$FIRETV:5555"
"$ADB" connect "$FIRETV" >/dev/null
PACKAGES=(
  com.amazon.device.telemetry.emitter   # usage telemetry
  com.amazon.neo.minerva                # metrics
  com.amazon.neo.minerva.logcat         # metrics logging
  com.amazon.aca                        # Alexa Central for Apps (voice tips, launch tracking)
  com.amazon.shoptv.firetv.client       # Amazon Shop
  com.amazon.minitv.android.app         # Amazon miniTV
  com.amazon.bueller.photos             # Amazon Photos
  com.amazon.tahoe                      # Amazon Kids
  com.amazon.smarthomemapviewapp        # Alexa smart-home map
  com.amazon.audiohome                  # Alexa multi-room audio
  com.amazon.whasettings                # Alexa home audio settings
)

case "${1:-status}" in
  restore) cmd="pm enable --user 0" ;;
  disable) cmd="pm disable-user --user 0" ;;
  status)  cmd="" ;;
  *) echo "usage: $0 <tv-ip> [status|disable|restore]"; exit 2 ;;
esac

for p in $PACKAGES; do
  if [[ -n "$cmd" ]]; then
    "$ADB" -s "$FIRETV" shell "$cmd $p"
  else
    state=$("$ADB" -s "$FIRETV" shell "pm list packages -d" | grep -qx "package:$p" && echo disabled || echo enabled)
    echo "$state  $p"
  fi
done
