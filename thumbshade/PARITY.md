# ThumbShade vs. the original: feature checklist

Built from the original app's settings screens (its labels, read from the v2.3.1 APK). Ticked
items exist in ThumbShade; the rest are planned.

## Button
- [x] Snap to edges, dock left/right, half tucked or fully visible, edge zone
- [x] Fling to an edge (adjustable velocity)
- [x] Allow off-screen placement
- [x] Different appearance when docked (size, corners, colour, opacity)
- [x] Behaviour while typing: as usual / move above / hide / dock / click through
- [x] Behaviour in selected apps: as usual / hide / dock / click through
- [x] No-notifications behaviour: stay / hide / dock
- [x] Landscape: relative position / keep place / don't show
- [x] Appear and hide animation: all 38 shade animations, travelling to/from the nearest edge, with Try it
- [x] New notification animation: pop / hop / wiggle / glow flash, intensity
- [x] Per-corner radius
- [x] Background and border colour source: theme / custom / notification / none
- [x] Number: size, bold, colour, position, hide when single
- [x] While media plays: album art, record, CD, tape, equalizer, pulse, wave, notes, scrolling title; only while playing; dim cover
- [x] Charging indicator: ring / progress ring, thickness, sweep / breathe
- [ ] Fullscreen-app behaviour
- [ ] Different *position* when docked vs. free
- [ ] Full-button app icon mode
- [ ] Charging colour stops, liquid / orbit animations
- [ ] Icon cluster: arc and half-orbit modes, distance, start/end angles, oldest/newest order, unique icons, tint
- [ ] Number custom X/Y offset
- [x] Action wheel (3 rings, follows the button, off-screen slots mirrored)

## Shade
- [x] Max height and width, position left / centre / right, newest at the bottom, dim
- [x] Browsing: list + 19 styles (ferris wheel, coverflow, spotlight, fan, wave, cascade, sway, tumble, helix, card stack, book, conveyor, crescent, fly-through, lens, origami, pinch, swirl, swivel)
- [x] Open/close animations: 38
- [x] Space between notifications
- [ ] Separate landscape height, width and position
- [x] Push/pull to close (rubber band, distance), swipe-to-dismiss distance
- [x] Wrap-around scrolling, remember scroll position, start at top/bottom
- [x] Background overlay: none / dim / blur / dim and blur
- [ ] Keep clear of the navigation bar (auto / custom gap / off)
- [ ] Editable snooze options ("for a while" / "until a time")

## Notification card styling
- [x] Card corner radius, card colour, body lines, large icon, pictures, buttons
- [x] Per-part text size / colour / bold (app name, title, body, time, subtitle)
- [x] Header icon options (app / sender), multiline header and title
- [x] Gradient backgrounds, borders, button styling
- [x] Media player: next track, tint

## Other
- [x] Themes (built-in + your own), Auto, Material You, tab bar position, notification controls
- [x] Rules engine, templates, widgets, lock-screen shade, backup
- [x] Icon packs and custom per-app icons (from a pack or a picture)
- [x] Reply on lock screen toggle, lock-screen appearance (hide content, darkness)
- [x] Screen lighting: 11 border + 13 button effects, played together, per-rule style
- [x] Apps' own custom notification layouts
- [x] Media player: shuffle, repeat, stop
- [x] Gestures: app screens (locked ones marked), shortcuts, custom intents, with Test
- [x] Animated button icons (17) with a preview gallery
- [x] Snooze and menu buttons in the card header
- [x] Usage access as an alternative to the accessibility service
- [x] Debug messages
- [ ] Translations

## Smart features (beyond the original)
- [x] Summaries of busy chats and app groups (Gemini Nano via Android AICore where available, built-in otherwise)
- [x] Learned priority: smart order (important nearest the thumb), minimise low-priority, urgent/low tags
- [x] Smart replies (intent-based plus Android's own suggestions), "Share my location"
- [x] Smart actions: add to calendar, track parcel, flight status, open address, copy code
- [x] Focus batching: smart digest at chosen times, quiet "normal" notifications
