#!/usr/bin/env zsh
# Keeps the home screen on one design language: screens must take colours, text styles, shapes
# and motion from Theme / Kit / Row / Motion instead of choosing their own. Run by build.sh.
#   tools/design-lint.sh            fails (exit 1) listing each violation
# A line that genuinely draws a material (artwork shades, a glare) can opt out with a trailing
# "// material: <why>" comment.
set -uo pipefail
cd "$(dirname "$0")/../src/com/applefireos/home"

# The design system itself, the materials it's drawn with, and code that isn't UI.
SYSTEM=(Theme Kit Row Toggle TabBar PageScaffold PanelScaffold Motion Ui Glass DockView
        AuroraView SlidesView TileView
        Apps Images Prefs Tmdb Sound Vpn Insights NowPlaying Controls RecsListener
        HomeRedirectService AerialActivity AerialDream AerialView ScreenTime)
RULES=(
  '0x[0-9A-Fa-f]{8}'                       'a colour literal: use a Theme colour role'
  'Ui\.(text|round|vertical|ring|ringOval)\(' 'hand-built text/shape: use Kit.text / Kit.shape / Row'
  'setTextSize\(|Typeface\.'               'a text size or face: use a Theme.Type via Kit.text'
  'new GradientDrawable'                    'a hand-made shape: use Kit.shape / Kit.card / Kit.chrome'
  'new [A-Za-z]*Interpolator\('             'an animation curve: use Motion.SPRING / Motion.EASE'
  'setInterpolator\('                       'an animation curve: use Motion.SPRING / Motion.EASE'
)

out=""
for f in *.java; do
  name=${f%.java}
  (( ${SYSTEM[(Ie)$name]} )) && continue
  for (( i = 1; i <= ${#RULES}; i += 2 )); do
    hits=$(grep -nE "${RULES[i]}" "$f" | grep -v '// material:')
    # Curves are fine when they come from Motion.
    [[ "${RULES[i]}" == *setInterpolator* ]] && hits=$(print -r -- "$hits" | grep -v 'Motion\.')
    [[ -z "$hits" ]] && continue
    while IFS= read -r hit; do
      out+="$f:${hit%%:*}: ${RULES[i+1]}"$'\n'"    ${${hit#*:}##[[:space:]]#}"$'\n'
    done <<< "$hits"
  done
done
if [[ -n "$out" ]]; then
  print -n -- "$out"
  echo "design-lint: $(print -n -- "$out" | grep -c '^[A-Za-z]*\.java:') places style themselves instead of using the design system" >&2
  exit 1
fi
