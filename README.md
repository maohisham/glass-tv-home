# Glass TV

**Make your Android TV or Google TV fast and ad-free again: strip out the bloat and ads, free up memory,
and come home to just your apps, with a new place to visit behind them every week.**

<p align="center">
  <img src="docs/screenshots/glass_tv_os_showcase_poster.jpg" width="100%" alt="Glass TV OS Presentation Poster">
</p>

> 🎨 **Interactive Design Presentation Poster**: Open [`docs/poster.html`](docs/poster.html) in any browser for the complete UI/UX specifications, design tokens, and printable poster deck.
>
> 📖 **User Guides / Panduan Pengguna**:
> - 🇬🇧 **English User Guide**: Complete beginner-friendly walkthrough, remote navigation, phone setup & FAQ in [USER_GUIDE.md](USER_GUIDE.md).
> - 🇲🇾 **Panduan Bahasa Melayu**: Panduan lengkap langkah demi langkah untuk pengguna baru di [PANDUAN_PENGGUNA.md](PANDUAN_PENGGUNA.md).

Glass TV debloats any Android TV or Google TV over Wi-Fi, with no root and nothing that can't be undone, then
swaps the ad-filled home screen for a light one in frosted glass, with a new set of places behind your apps
every week.

<p>
  <img src="docs/screenshots/home-live.png" width="24%" alt="Live Home Screen">
  <img src="docs/screenshots/weather-live.png" width="24%" alt="Weather & Air Quality Dashboard">
  <img src="docs/screenshots/screensaver-live.png" width="24%" alt="StandBy Nightstand Screensaver">
  <img src="docs/screenshots/recent-apps-live.png" width="24%" alt="Recent Apps & RAM Manager">
</p>

## What you get

- **System recents on double-press Home.** Double-pressing the Home button opens the system's native recents drawer — the same one the remote's recents key uses — via Android's Accessibility Service (`GLOBAL_ACTION_RECENTS`). No custom overlay, no RAM overhead.

- **Weather & Air Quality Engine.** A full-screen Leanback weather hub (`WeatherActivity`) accessible directly from the under-clock pill:
  - Current condition, apparent temperature, humidity, wind velocity, UV index, sunrise and sunset times.
  - Air Quality Index (AQI / API / IPU) from Malaysia's Jabatan Alam Sekitar (APIMS) with a multi-color spectrum gauge, station comparisons, PM2.5 / PM10 readings, and health advisories.
  - Next 12 Hours hourly forecast pills and 7-day daily forecast cards with precipitation probabilities.

- **Islamic Prayer Timetable & Daily Ayat.** A mindful cultural shelf on a dark frosted pill so it stays readable against any wallpaper:
  - 6-phase timetable (Subuh, Syuruk, Zohor, Asar, Maghrib, Isyak) with emerald highlighting for the active prayer window.
  - Daily Quranic Ayat of the Day with Arabic calligraphy and translations.

- **StandBy Ambient Mode & Smart Energy Lifecycle.**
  - **10 Minutes Idle**: Automatically engages an ambient StandBy screensaver with large 110sp numerals, Islamic Hijri calendar, next prayer capsule, and a late-night red monochrome wind-down mode.
  - **Sleep timer pill**: A moon-icon button in the top bar cycles through preset durations (15 / 30 / 60 / 120 min); a countdown overlay appears on screen and the TV turns off when it expires via `GLOBAL_ACTION_LOCK_SCREEN`. The timer and its overlay survive a device reboot — if a timer was active before restart, the overlay reappears as soon as the accessibility service reconnects.

- **Hide apps from your home screen.** Long-press any app tile and choose **Hide App** — it disappears from the grid instantly. Restore hidden apps from **Launcher Settings → Hidden Apps** at any time.

- **Full app icon customisation.** Long-press any tile to:
  - **Change Icon** — pick from any installed icon pack.
  - **Upload Icon** — use any image from your device as a custom tile icon.
  - **Reset Icon** — restore the app's original icon.

- **Aerial video wallpaper with scene filtering.** Connects to AerialViews for curated aerial footage. When AerialViews isn't available the launcher falls back to its own bundled library of aerial clips. In **Launcher Settings → Wallpaper** you can filter by scene type (Beach, City, Countryside, Nature, Sea, Space, Waterfall) and the new filter applies instantly without waiting for a timeout. When **Show Wallpaper Info** is on, the location name of the currently playing scene appears on the shelf above the dock.

- **Online 4K wallpapers with collection picker.** Fetches curated high-resolution photos from Pexels and rotates them at a configurable interval. In **Launcher Settings → Wallpaper** choose a collection — Landscape, Architecture, Nature, Urban, Abstract, or Malaysia — and set how often the wallpaper changes (15 min, 30 min, 1 h, 2 h, or 4 h). The Malaysia collection highlights local scenes with Malay place names on the info shelf. Online wallpapers, places, and aerial footage are mutually exclusive; switching to one automatically turns off the others.

- **Keeps itself clean.** Every 15 days, while Home sits idle, the TV clears its app caches, where the Play
  Store leaves gigabytes of download leftovers that can make apps open to a blank screen. No computer needed,
  sign-ins and app data are never touched; the TV asks once to allow it.

- **Glass, like Apple's.** The dock's tray, the top-bar buttons, the menus, and the Waktu Solat, Weather, and Now Playing widgets all show the picture behind them softened, with a dark veil that keeps text readable against any wallpaper. Android 10 has no live blur, so the picture is blurred once each time it changes and every glass surface draws its patch of it. The rim shine is suppressed on the widgets so they sit quietly on screen without visual noise.

- **One selection that glides, in your app's colour.** Between apps the highlight is a pane of glass with a soft bloom underneath; on the top bar and in the menus it travels as a white rim outline. It glides and changes shape from one tile to the next, never jumps. The bloom colour adapts to the dominant colour sampled from the focused app's icon, so the glow shifts from amber on Netflix to green on Spotify.

- **Places to visit that renew themselves.** Once a week the TV takes the pictures Bing has shown on its home
  page over the past days — landmarks, landscapes, wildlife — deletes last week's and rotates through them,
  dimmed so the clock and tiles stay readable. The wallpaper info shelf above the dock names each place, its country and a few
  lines about it; this shelf is positioned slightly higher than the app-name label so it clears the dock cleanly without moving the Waktu Solat widget. Toggle it off in **Launcher Settings → Wallpaper → Show Wallpaper Info** and the shelf falls back to just the focused app's name at the original position. Pictures with people in them are skipped: by the words in their caption, and by Android's
  own face detector on the TV. No key, no account, nothing to set up.

- **Arrange your apps from the remote.** Hold OK on an app for a glass menu with **Move**, **Hide App**, **App Info**, **Change Icon**, and **Upload Icon**.
  Move picks the app up: it wiggles, the arrows carry it (into or out of the dock too) and the other apps
  slide aside; OK puts it down. The order is kept, and apps installed later join at the end.

- **An Inputs menu in glass.** The selection glides out of the Inputs button into the list of HDMI and AV
  inputs, and Up from the top of the list glides it back.

- **Comes back as you left it.** Back from an app, the app you left is still selected; pressing Home on Home
  goes to the first app. When Android has closed Home to free memory, it comes back whole in one frame,
  with no black screen and no half-built dock.

- **A clock screensaver.** A grey analog dial with a yellow-orange second hand that ticks, the time and
  date beneath, drifting a little each minute so nothing burns in.

- **Rows feather out under the top bar** instead of being cut off when you scroll, and the Wi-Fi / Ethernet
  button can be hidden (`show_network_pill`).

- **Rotating wallpapers of your own** instead, with `"places": false`: put pictures in a theme's `wallpapers/`
  folder and choose how often they change, down to every 5 seconds. Each picture is decoded in the background
  and cross-faded.

| | |
|---|---|
| ![Home with Mount Sneffels behind the apps, its name, country and story above the glass dock](docs/screenshots/home.jpg) | ![The app grid scrolled down over the Chauvet cave, rows feathering out under the top bar](docs/screenshots/grid.jpg) |
| *Home, with this week's place* | *Every app, feathering out under the top bar* |
| ![The glass Inputs menu with HDMI 1 selected](docs/screenshots/inputs.jpg) | ![The clock screensaver: a grey analog dial, the time and the date](docs/screenshots/clock.jpg) |
| *Inputs, in glass* | *The clock screensaver* |

Still light: on a Xiaomi Mi TV with 1.8 GB of RAM the launcher uses about 80–100 MB and stays there over hours of use. Wallpapers are decoded at the display's native resolution rather than 4K, so a 1080p TV holds ~2 MB per wallpaper instead of ~8 MB; only one is in memory at a time, the week's places take about 2 MB of storage, and nothing runs while you are inside an app. On devices with 1 GB of RAM or a weak GPU, set `"reduced_motion": true` in your theme — this halves the glow-layer count, doubles the aerial backdrop update interval, and snaps glass transitions instead of animating them.

## Setting it up (Auto with AI or Manual)

**Using an AI coding agent** (Google Antigravity [Free], Claude Code [Paid], Cursor, Codex...)? Point it at this repository and ask it to debloat your TV, block ad tracking, and install Glass TV automatically! [AGENTS.md](AGENTS.md) and [PROMPT.md](PROMPT.md) guide any AI assistant safely step-by-step.

> 📖 **Read the Beginner User Guides**:
> - 🇬🇧 **[USER_GUIDE.md](USER_GUIDE.md)**: Full English step-by-step guide (Developer Mode, AI prompts, iPhone & Android setups, FAQ).
> - 🇲🇾 **[PANDUAN_PENGGUNA.md](PANDUAN_PENGGUNA.md)**: Panduan penuh Bahasa Melayu untuk pengguna baru (aktifkan Developer Mode, arahan AI, telefon pintar & FAQ).

The full step-by-step guide for debloating and installing is in
[docs/CLEANYOURTV-README.md](docs/CLEANYOURTV-README.md), and [PROMPT.md](PROMPT.md) lets a coding assistant do
it for you. In short:

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.1.50                                   # your TV's IP address
tools/disable.sh examples/<your tv>/debloat.txt               # debloat
launcher/build-and-install.sh                                 # build the launcher and make it Home
tools/theme.sh themes/<your theme>                            # push your settings and wallpapers
```

Glass TV clears the TV's caches by itself every 15 days ([how](docs/cache-clean.md)). To clean several TVs
from one computer instead, [`tools/cache-clean.sh`](docs/cache-clean.md) finds the TVs on your network and
clears their caches, leaving sign-ins and app data untouched.

## Which TVs work

Android TV and Google TV, Android 8 or newer, on the TV or on a box plugged into any TV.
[docs/DEVICES.md](docs/DEVICES.md) lists every tested model and how far each was tested: end to end with
Glass TV, with the original launcher, or debloat only, plus what is expected to work and what cannot.

## LG and Samsung TVs

LG TVs run webOS and Samsung TVs run Tizen; neither can run Android apps, and neither lets any app replace its
home screen or the Home button. For the full Glass TV, plug in a Google TV or Android TV box (Chromecast with
Google TV, Xiaomi Mi Box, NVIDIA Shield): the TV becomes the screen and Glass TV is Home. On LG, a version you
open as an app is in preview in [webos/](webos/README.md).

## Places, or pictures of your own

Places are on from the start. To change how often the picture changes, or to use your own pictures and keep
the launcher offline, make a theme folder with a `config.json` like this and push it with `tools/theme.sh`:

```json
{
  "places": false,
  "wallpaper_seconds": 15,
  "show_network_pill": false
}
```

All keys are listed in [themes/README.md](themes/README.md). With `"places": false`, the launcher never goes
online.

## Credits and licence

- Built on [CleanYourTV](https://github.com/Gathin23/cleanyourtv) © 2026 Gathin, MIT licence — see
  [LICENSE](LICENSE). Its debloat lists, tools and launcher are the foundation of this project.
- The Outfit typeface is under the SIL Open Font License (`launcher/FONT-LICENSE-OFL.txt`).
- The Inputs icon is from Google's Material Icons (Apache 2.0).
- Pictures of places and their captions are Bing's daily images (Microsoft). The launcher fetches them on your
  own TV; the promo pictures above show three of them.
- Gotham, used in the promo pictures only, is a Hoefler&Co. typeface and is not part of this repository.
