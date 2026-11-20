# Home Assistant Link: Design

**Date:** 2026-11-20
**Repo:** `launcher/` inside `glass-tv-home`, package `com.gathin.tvhome`
**Target device:** NVIDIA Shield TV (Android 11), already connected to Home Assistant through the
`androidtv` (ADB) and `androidtv_remote` integrations. The design must work on any Android 8+ TV.

## Intent and why

The owner wants Home Assistant (HA) to control the launcher's sleep timer: start it, change it, cancel it, and
see what it is doing. The timer is only the first feature. Soon after, a pop-up of the front-door camera
should appear over any app (Netflix, YouTube) when HA detects movement. The owner decided to build the HA
connection first, as a lasting foundation, so that connection is set up once and later features (camera
pop-up, more commands, more state) are added to it without redoing the setup.

## Goals

- One persistent, two-way connection from the launcher to the owner's HA, over HA's WebSocket API.
- It runs while any app is in front, not only while the launcher is open.
- First feature on it: sleep-timer control from HA (`start`, `cancel`, `add`, `subtract`) and timer state
  reported back to HA.
- Set up once in the launcher settings: HA address and a long-lived access token.
- A message layer where a new feature is "register a handler for a command name", with no change to the
  connection code.
- Opt-in: with the link off or not configured, the launcher makes no new network calls.

## Non-goals

- The camera pop-up itself (follow-up project; see "Leaving room for the camera").
- A custom HA integration or HACS component. HA sees the TV through events and ordinary HA automations and
  template sensors; docs give copy-paste examples.
- Control over ADB, an HTTP server on the TV, or MQTT (all considered and rejected, see Approaches).
- Several TVs sharing one HA with per-TV entities beyond a simple optional device name in each message.

## Anti-goals (a failure even if it technically works)

- The access token ends up in the repository, in `config.json`, in a theme pushed by `tools/theme.sh`, in
  logs, or in a screenshot of the settings screen.
- The APK grows past the 400 KB limit CI checks (so no OkHttp or similar library).
- A timer started from HA while the launcher is closed fails to put the TV to sleep when it ends.
- The link makes the launcher heavier when idle: no polling loops, no wake locks, nothing running while the
  link is off.

## Constraints

- `AGENTS.md`: no new background services, no network use but the places pictures. This work is a named,
  opt-in exception: traffic only to the HA address the owner typed in; update `AGENTS.md` to say so.
- The connection lives inside `HomeKeyService` (an `AccessibilityService`, already always running and already
  drawing the timer overlay over other apps). It is opt-in at install (`HOME_KEY_SERVICE=1`), so the HA
  link requires it; the settings screen must say so when it is missing.
- Targets Android 14 with no network security config, so cleartext `ws://` and `http://` are currently
  blocked.
- Only `HttpURLConnection` and plain sockets exist today; there is no WebSocket client.
- `SleepTimer` is a main-thread singleton with a single `Listener`, set by `MainActivity` and cleared in its
  `onDestroy`. While the listener is null, `onExpired` goes nowhere and nothing sleeps the TV.
- Credentials stay with the person: they enter the token themselves.

## Approaches considered

Where the connection lives:

1. **Inside `HomeKeyService` (chosen).** Already persistent, already owns an overlay window that shows over
   other apps, needs no new permission or notification. Cost: the link needs that service enabled.
2. A new foreground service plus the "draw over other apps" permission. Cleaner separation, but adds a
   permission, a persistent notification and more code for no overlay benefit.

How it talks to HA:

1. **WebSocket to HA (chosen).** One connection carries commands in, state out, and later camera triggers.
   HA is the server, so nothing listens on the TV.
2. ADB broadcast for commands plus a webhook for state. Works for the timer, but each feature needs its own
   command and webhook, the camera still needs a token, and ADB drops and adds latency.
3. HTTP server on the TV. Easy for HA, but it breaks the "no listening socket" rule.
4. MQTT. Needs a client library (size limit) and a broker.

## The design

### 1. Transport: a small WebSocket client

A minimal RFC 6455 text-frame client written for this repo, over `Socket` / `SSLSocket` (no library).
It must do the HTTP upgrade handshake, send masked text frames, receive text frames including fragmented
ones, answer ping with pong, handle close, and read with a socket timeout so a dead connection is noticed.
Runs on one background thread; every callback into app logic is posted to the main thread, because
`SleepTimer` and the overlay are main-thread only.

### 2. HA protocol

Standard HA WebSocket API at `<base>/api/websocket`:

1. HA sends `auth_required`; the launcher replies `{"type":"auth","access_token":"<token>"}`.
2. On `auth_ok`, subscribe to the event `glasstv_command` (`subscribe_events`), then send a state message
   (below).
3. `auth_invalid` stops reconnecting and shows "Auth failed" in settings until the token or URL changes.
4. Keep-alive: send HA's `{"type":"ping"}` about every 30 seconds; no reply within a timeout means
   reconnect.
5. Reconnect with exponential backoff from 2 s up to 60 s, reset on a successful auth. Reconnect when the
   network comes back; do not poll while there is no network.

Address handling: the owner types `http://homeassistant.local:8123`, `https://ha.example.com`, or a bare
host; the client maps `http` to `ws` and `https` to `wss`, and appends `/api/websocket`. `ws://` is allowed
only for private, link-local or `.local` / `.lan` / single-label hosts; anything else must be `wss://`.

### 3. Messages

Inbound (HA to TV): HA fires the event `glasstv_command` (from an automation or script using the
`event.fire` action, or the "Events" developer tool):

```json
{ "command": "timer.start", "minutes": 45 }
```

Optional `"device": "<name>"`: if present and not equal to this TV's configured device name, ignore it.

| `command` | Effect |
|---|---|
| `timer.start` + `minutes` (1 to 720) | start, or restart, a countdown of that length |
| `timer.cancel` | stop the timer and remove the overlay |
| `timer.add` + `minutes` | add time to the running timer; if none is running, start one of that length |
| `timer.subtract` + `minutes` | remove time from the running timer, never below 1 minute remaining; no-op if none is running |
| `timer.status` | reply with the current state right now |

Unknown commands and malformed data are ignored and logged without the payload.

Outbound (TV to HA): the launcher fires the event `glasstv_state` (`fire_event` command over the same
socket):

```json
{ "device": "living-room", "timer": { "active": true, "minutes": 45, "remaining_seconds": 2310 } }
```

Sent on connect, whenever the timer starts, changes or is cancelled, when it expires, in reply to
`timer.status`, and about once a minute while a timer runs. HA reads it with an event-triggered template
sensor; the docs give the YAML.

(`fire_event`, and possibly `subscribe_events`, need the token's user to be an HA administrator. The docs and the settings status message
say so if HA rejects it.)

### 4. Timer behaviour changes

- `SleepTimer` gains `adjust` / "set remaining" support (the platform `CountDownTimer` cannot be changed, so
  it is restarted with the new remaining time) and a **permanent expiry callback** that is separate from the
  UI listener. The permanent callback is set by the HA link / `HomeKeyService`, which are always running, so
  expiry always sleeps the TV even when the launcher activity is gone. `MainActivity`'s listener only
  updates the screen while it is alive.
- All `SleepTimer` calls from HA are posted to the main thread; every change calls the existing overlay
  show / hide so the on-screen countdown follows.
- A timer started or changed from HA is in memory only. It does not rewrite `sleep_timer_minutes` in
  `config.json`, because that key is the "default sleep timer at launcher start" setting, not the live
  state.
- The remote's pill still cycles Off, 15, 30, 60, 120. If the running length is not one of those, the next
  press moves to the next preset above the current minutes, or Off after 120.

### 5. Settings and credentials

A new "Home Assistant" section in `LauncherSettingsActivity`:

- Toggle: enable the link.
- HA address (text).
- Access token (text, hidden after entry).
- Device name (text, default "tv", used in messages and the optional `device` filter).
- A live status line: Not configured, Connecting, Connected, Auth failed, Needs Home key service, Network
  unreachable.

Storage: the address, device name and enable flag go in `config.json` like other settings. The token goes
in the app's private storage (private `SharedPreferences`), never in `config.json`, which sits in a
world-readable folder and is overwritten by themes. Typing about 180 characters with a remote is painful,
so the token can also be imported: the owner pushes a file `ha-token.txt` into the app's files folder (the
same mechanism as `theme.sh` and `clean-now`); the launcher reads it once, stores it privately, and deletes
the file. The token is never logged and never shown in full on screen.

### 6. Network config

Add a cleartext allowance so `http://` / `ws://` to a LAN HA works (`usesCleartextTraffic` or a network
security config). Because Android cannot limit cleartext to a private IP range, the "private hosts only for
`ws://`" rule in section 2 is enforced in code. Existing traffic (wallpapers over `https`) is unaffected.

### 7. Leaving room for the camera

The camera pop-up is the next project, not part of this one, but this design must not block it:

- Commands are dispatched by name through a small registry (`timer.*` is the first group); a later
  `camera.show` / `camera.hide` registers a handler without touching the connection code.
- The link exposes the validated HA base URL and token to other classes in the launcher (for the camera
  stream request), without writing them anywhere else.
- Planned for that follow-up, recorded here so it is not re-decided: show the picture from HA's MJPEG proxy
  stream (`/api/camera_proxy_stream/<entity>`) in a second overlay window owned by `HomeKeyService`, like
  the timer overlay. HLS is avoided because a video library would break the APK size limit.

### 8. Docs and tests

- New `docs/home-assistant.md`: how to create the token, set it up on the TV, the commands, and HA YAML for
  buttons (start 15 / 30 / 60, add 15, cancel), a template sensor for `glasstv_state`, and a troubleshooting
  list. Link it from the README. Add a short entry to `docs/TROUBLESHOOTING.md`.
- `AGENTS.md`: replace "no network use but the weekly places pictures" with that plus "and the owner's own
  Home Assistant, only when set up", and mention the Home key service requirement.
- Tests (JVM, in the style of `SleepTimerTest`): message building and parsing, address to WebSocket URL
  mapping and the private-host rule, command to timer-action mapping including the clamps (1 to 720
  minutes, subtract never below 1 minute remaining), backoff schedule, and the WebSocket frame
  encoder/decoder (masking, fragmentation, lengths above 125 and 65 535 bytes).
- CI must still pass: `python3 -m unittest discover -s tests` and `./gradlew testReleaseUnitTest
  assembleRelease`, APK under 400 KB.

## Left to the builder

- Class layout and names (suggested: `HaLink`, `WebSocketClient`, `HaMessages`; `HaLink` owned by
  `HomeKeyService`).
- The settings text-entry UI, built to match the existing settings rows.
- Exact timeouts, and how the overlay text is formatted for lengths from HA.
- How the HA link behaves while the TV is asleep or the network is down: it only needs to reconnect when
  the network returns.

## Calls I made (not decided by the owner)

- Command shape: one event `glasstv_command` with a `command` field, and one outbound `glasstv_state`.
- Events instead of webhooks, because the socket is already open.
- `timer.add` on an inactive timer starts one; `timer.subtract` never goes below 1 minute and never
  ends the timer.
- HA-driven timer changes are not saved as the default timer in `config.json`.
- Token in private storage with a file-import path, instead of typing only.
- `ws://` limited to private hosts in code, with cleartext allowed at the manifest level.
- Maximum 720 minutes (12 hours).
