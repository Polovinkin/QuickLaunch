<div align="center">

# ⚡ Quick Launch

**Type a few letters. Press Enter. The app opens.**

A Spotlight-style app launcher for Android. Instant, lightweight, and out of your way.

[**Download the APK**](https://github.com/Polovinkin/QuickLaunch/releases/latest) · [How it works](docs/TECHNICAL.md) · GPL-3.0

<img src="docs/demo.gif" width="420" alt="On a Galaxy Z Fold: typing x finds X, typing spo finds Spotify, Enter opens it">

</div>

---

## Why

Finding an app on Android means scrolling a grid or opening a search that still makes you tap through folders. Quick Launch brings up an instant search bar: summon it, type two or three letters, and tap or press Enter. Done.

It is not a home screen replacement. It is a small card that appears over whatever you are doing and
disappears the moment you launch something.

## What you get

- **Instant.** Appears on the very next frame after you trigger it. No animation, no spinner.
- **Smart matching.** Exact and prefix matches first, then word starts, then initials.
  `mbs` finds Meta Business Suite, `yt` finds YouTube.
- **Learns from you.** Apps you launch often rise to the top. Grant *Usage access* and your most used
  apps are already listed before you type.
- **Pin your essentials.** Tap the pin on the top row and that app sits in a Pinned section at the
  top, in a fixed order, every time.
- **Split screen by drag.** Long-press a result and drag it to open it next to the current app.
- **Long-press for more.** Hold a result for App info.
- **Copied a link?** It shows up as the first row. Tap it or press Enter to open it.
- **Big screens welcome.** A centered palette on tablets and foldables. Full width on phones.
- **Private by design.** No internet permission. No analytics. No ads. About 90 KB.

## Install

1. Download the APK from the [latest release](https://github.com/Polovinkin/QuickLaunch/releases/latest)
   and open it on your phone. Allow installing from your browser when asked.
2. Open Quick Launch once and tap the **⚡ row** to allow *Display over other apps*. That is what makes
   it instant.
3. Optional: tap the **★ row** to show your most used apps.

Android 8.0 or newer. The blur behind the card needs Android 12 or newer.

## How to trigger it

| You have | Do this |
|---|---|
| A Samsung phone | Good Lock → **One Hand Operation+** → any gesture → *Open app* → Quick Launch. Or Settings → Advanced features → Side button → double press → Quick Launch. |
| Anything else | Tap the Quick Launch icon like any app, or put it in your dock. |

## Good to know

- Opening Quick Launch over a playing video app sends that app into picture-in-picture, the same as
  any launcher-style app does.
- Over Settings and some secure screens Android hides overlays, so the card appears with the normal
  app animation there instead of instantly.
- No internet permission, no analytics, nothing leaves the device.
  [Privacy policy](https://polovinkin.github.io/QuickLaunch/privacy-policy.html).

## For the curious

Architecture, the measurements behind "instant", and how to build it:
**[docs/TECHNICAL.md](docs/TECHNICAL.md)**.

## License

GPL-3.0. See [LICENSE](LICENSE).
