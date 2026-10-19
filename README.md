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
