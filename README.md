# TV Home — minimal launcher for Google TV

A clean, Apple TV–style home screen for Google TV.

## What's in it
- **Greeting** — "Good morning / afternoon / evening, Jaiden" or "Goodnight, Jaiden",
  changing with the time of day. Change the name in `MainActivity.kt` (`USER_NAME`).
- **Top Shelf** — the focused app's art fills the background, blurred and dimmed,
  with its name in large type.
- **Top Row** — your favourite apps. Starts with Nomorflix plus popular streaming
  apps you have installed.
- **All Apps** — every other app installed on the TV, five per row, plus Settings.
  Scrolling down slides the Top Shelf away; **Back** jumps to the top again.
- **Hold OK on any tile** — Add to / Remove from Top Row, Move Left / Right, App Info.
- Tiles lift with a soft shadow, show their name underneath, and a light sweep
  glides across them when selected.
- **Nomorflix app** (`WebAppActivity.kt`) — opens the site full-screen, laid out for a
  bigger screen so more fits. Remote controls:
  - **Arrows** glide a mouse pointer; **OK** clicks.
  - **Hold OK** → *Scroll mode*: arrows scroll whatever is under the pointer
    (dropdowns, episode lists, the page). Press **OK** or **Back** to return to the pointer.
  - Pushing the pointer against an edge also scrolls; **Channel Up/Down** scroll too.
  - **Back** goes back, **Menu** reloads, **Play/Pause · Fast-forward · Rewind** control video.

## Phone control panel — "Jaiden's TV"
The app serves a control page over Wi-Fi. Open **`http://<TV-IP>:8099`** in any
phone browser — no second app to install. It can:

- **Dim the whole screen** (any app, video included), 0–80%, with Day / Dim / Night /
  Dark presets and a live slider.
- **Warm tint** — shift the picture warmer and cut blue light, separate from dimming.
- **Sleep timers** — sleep in 15 / 30 / 60 min, or at a set time; a **Wake** button
  brings it back. ("Sleep" is a software blackout — an app can't cut the TV's power —
  so you wake it from the phone.)
- **Auto-off every night** — a recurring blackout time you can enable and override for
  the day with a one-off sleep timer.
- **Remote** — Home, Back, and Open Nomorflix straight from your phone.

### Night dimming schedule
Dims at a set time and clears in the morning. Defaults: dim to 60% at **10:00pm**,
clear at **7:00am**. Runs entirely on the TV, no computer needed.

### How it works
The dim / warmth / sleep layer is one black-to-amber overlay the accessibility
service lays over the screen, so it works even on TVs whose backlight can't be
changed over adb. It never takes touch or focus. Requires the **TV Home**
accessibility service to be switched on (the same one that makes TV Home your home
screen).

Code: `DimController.kt` (the layer, schedule and timer settings), `ControlServer.kt`
(the phone page and its endpoints). No extra libraries.

## Add more website apps
In `MainActivity.kt`, add a line to `webApps`:
```kotlin
WebApp("Aira Freight", "https://airafreight.com", "aira", 0xFF1E3A8A.toInt(), 0xFF0B1530.toInt()),
```
(name, address, word shown on the tile, top colour, bottom colour)

## Build the APK — pick one
**A. GitHub (no software needed):** push this folder to a new GitHub repo. The
*Build APK* action runs automatically → open the run under **Actions** → download
**TVHome-apk**.

**B. Android Studio:** File → Open this folder → Build → Build App Bundle(s)/APK(s)
→ Build APK(s). The file lands in `app/build/outputs/apk/`.

## Install on the Google TV
1. Settings → System → About → click **Android TV OS build** 7 times (turns on Developer mode).
2. Settings → Apps → Security & restrictions → **Unknown sources** → allow the app you'll install from.
3. Get the APK onto the TV with **Send Files to TV** (phone → TV) or the **Downloader**
   app (paste a link to the APK), then open it to install.
4. TV Home now appears in your Apps row — open it from there.

## Making it the default home screen
Google TV doesn't offer a "choose home app" switch. To boot straight into TV Home,
the common route is a key-remapping app (e.g. *Button Mapper*) set so the Home button
opens TV Home. Disabling the stock launcher with adb is possible but can break
system features, so it isn't recommended.
