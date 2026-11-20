# Home Assistant Link Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A persistent, opt-in WebSocket link from the launcher to the owner's Home Assistant, hosted in `HomeKeyService`, with sleep-timer control (`start`, `cancel`, `add`, `subtract`, `status`) and timer state reported back.

**Architecture:** Pure, JVM-tested logic classes (address, messages, frames, router, backoff, token parsing, timer arithmetic) sit under a thin Android layer: `HaLink` owns a `WebSocketClient` thread, posts everything to the main thread, and `HomeKeyService` starts and stops it. Commands go through a registry keyed by name prefix so the camera pop-up can register later.

**Tech Stack:** Java 17, Android API 26+, `org.json`, `java.net.Socket` / `SSLSocket`, JUnit 4 (no new libraries).

**Spec:** [docs/superpowers/specs/2026-11-20-home-assistant-link-design.md](../specs/2026-11-20-home-assistant-link-design.md)

## Global Constraints

- Package `com.gathin.tvhome`; sources in `launcher/app/src/main/java/com/gathin/tvhome/`, tests in `launcher/app/src/test/java/com/gathin/tvhome/`.
- APK stays under 400 KB (CI checks it); no OkHttp or other new dependency; minSdk 26.
- With the link off or unconfigured: no new network calls, no running threads, no callbacks.
- The token is never in `config.json`, the repo, logs, or full on screen; it lives in private `SharedPreferences`.
- Every call into `SleepTimer`, the overlay and settings state runs on the main thread; socket I/O runs on one background thread.
- Event names: inbound `glasstv_command`, outbound `glasstv_state`. Commands: `timer.start`, `timer.cancel`, `timer.add`, `timer.subtract`, `timer.status`. Minutes 1 to 720.
- `ws://` only to private / link-local / loopback / `.local` / `.lan` / single-label hosts; anything else must be `wss://`.
- Backoff 2 s doubling to 60 s; keep-alive ping every 30 s.
- Commits carry only the human author's identity: no AI attribution lines (`AGENTS.md`).
- Test commands run from `launcher/`: `./gradlew testReleaseUnitTest --tests 'com.gathin.tvhome.<Class>'`.
- Ask the owner before installing on or touching the TV (`AGENTS.md` ground rule 1).

## Review Focus

- Command JSON with missing, negative, zero, fractional, string or above-720 `minutes` is ignored, never crashes or starts a bad timer (Task 2, Task 3).
- `timer.subtract` larger than the time left leaves 1 minute and does not end the timer or sleep the TV; `timer.add` never goes above 720 minutes (Task 3).
- A server frame claiming a huge length, or garbage bytes, makes the reader throw `IOException`, not run out of memory or hang (Task 4).
- Token file with CRLF, trailing newline, BOM, surrounding quotes, or inner spaces: the first three are cleaned, inner spaces are rejected (Task 5).
- Address typed with spaces, a trailing slash, no scheme, uppercase scheme, or a public host over plain `http` gives the right URL or is refused (Task 1); device filter ignores case (Task 6).

---

### Task 1: HaAddress

**Files:**
- Create: `HaAddress.java`
- Test: `HaAddressTest.java`

**Interfaces:**
- Produces: `static String HaAddress.toWebSocketUrl(String input)` returning the full `ws(s)://host[:port]/api/websocket` or `null` if invalid or not allowed; `static String HaAddress.toHttpBase(String input)` returning `http(s)://host[:port]` (no path, no trailing slash) or `null`; `static boolean HaAddress.isPrivateHost(String host)`.

- [ ] **Step 1: Write `HaAddressTest`** with these assertions:
  - `toWebSocketUrl("http://homeassistant.local:8123")` equals `"ws://homeassistant.local:8123/api/websocket"`.
  - `toWebSocketUrl("  192.168.0.10:8123/ ")` equals `"ws://192.168.0.10:8123/api/websocket"` (no scheme means `http`).
  - `toWebSocketUrl("HTTPS://ha.example.com")` equals `"wss://ha.example.com/api/websocket"`.
  - `toWebSocketUrl("ws://x.local:8123/api/websocket")` equals `"ws://x.local:8123/api/websocket"` (not doubled).
  - `toWebSocketUrl("http://ha.example.com")` is `null` (public host, plain).
  - `toWebSocketUrl("")`, `toWebSocketUrl(null)`, `toWebSocketUrl("ftp://x")` are `null`.
  - `isPrivateHost` true for `10.1.2.3`, `172.16.0.1`, `172.31.255.1`, `192.168.0.126`, `169.254.1.1`, `127.0.0.1`, `localhost`, `homeassistant`, `ha.local`, `ha.lan`; false for `172.32.0.1`, `8.8.8.8`, `ha.example.com`.
  - `toHttpBase("https://ha.example.com/")` equals `"https://ha.example.com"`.
- [ ] **Step 2: Run** `HaAddressTest`; expected FAIL (class missing).
- [ ] **Step 3: Implement** `HaAddress` with the signatures above using `java.net.URI`/manual parsing (no `android.*`, so it runs on the JVM).
- [ ] **Step 4: Run** `HaAddressTest`; expected PASS.
- [ ] **Step 5: Commit** `feat: HA address parsing and private-host rule`.

### Task 2: HaMessages and HaCommand

**Files:**
- Create: `HaMessages.java`
- Test: `HaMessagesTest.java`

**Interfaces:**
- Produces:
  - `final class HaCommand { final String name; final int minutes; final String device; }` (nested in `HaMessages` as `HaMessages.Command`); `minutes` is `-1` when absent; `device` is `""` when absent.
  - `static String auth(String token)`, `static String subscribeEvents(int id, String eventType)`, `static String ping(int id)`, `static String stateEvent(int id, String device, boolean active, int minutes, long remainingSeconds)` (a `fire_event` of type `glasstv_state` with `{"device":…,"timer":{"active":…,"minutes":…,"remaining_seconds":…}}`).
  - `static Incoming parse(String json)` never throws; returns `Incoming` with `enum Type { AUTH_REQUIRED, AUTH_OK, AUTH_INVALID, COMMAND, RESULT, PONG, OTHER }`, plus `Command command` (for `COMMAND`), `int id`, `boolean success` (for `RESULT`).

- [ ] **Step 1: Write `HaMessagesTest`:**
  - `auth("abc")` parses back to `{"type":"auth","access_token":"abc"}`.
  - `stateEvent(5,"tv",true,45,2310)` parses back with `type=fire_event`, `event_type=glasstv_state`, `event_data.timer.remaining_seconds=2310`.
  - `parse` of `{"type":"auth_required"}`, `auth_ok`, `auth_invalid`, `pong` gives the matching `Type`.
  - `parse` of `{"type":"event","event":{"event_type":"glasstv_command","data":{"command":"timer.start","minutes":45}}}` gives `COMMAND` with `name="timer.start"`, `minutes=45`, `device=""`.
  - Same event with `"minutes":"45"`, `"minutes":4.5`, `"minutes":null`, or no minutes gives `minutes=-1` (not 45, not a crash); `"minutes":-3` also gives `-1`.
  - `parse("not json")`, `parse("")`, `parse(null)`, `parse("[]")` give `OTHER`.
  - `parse` of an event with another `event_type` gives `OTHER`.
  - `parse` of `{"id":7,"type":"result","success":false}` gives `RESULT`, `id=7`, `success=false`.
- [ ] **Step 2: Run** `HaMessagesTest`; expected FAIL.
- [ ] **Step 3: Implement** `HaMessages` with `org.json` (the `org.json:json` test dependency is already present).
- [ ] **Step 4: Run** `HaMessagesTest`; expected PASS.
- [ ] **Step 5: Commit** `feat: HA WebSocket message build and parse`.

### Task 3: SleepTimer arithmetic, hooks and preset cycling

**Files:**
- Modify: `SleepTimer.java`
- Test: `SleepTimerTest.java` (add cases)

**Interfaces:**
- Produces (static, pure): `static final int MAX_MINUTES = 720`; `static boolean validMinutes(int m)` (1 to 720); `static long addedRemainingMs(boolean active, long remainingMs, int minutes)` (inactive means `minutes*60_000`; capped at `MAX_MINUTES*60_000`); `static long subtractedRemainingMs(long remainingMs, int minutes)` (never below `60_000`).
- Produces (instance, main thread): `void setRemaining(long ms)` (restarts the countdown with `ms` left; `activeMinutes` becomes `ceil(ms/60000)`); `void setExpiryHandler(Runnable r)` (always called on expiry, independent of the UI `Listener`); `void setStateObserver(Runnable r)` (called after every start, cancel, `setRemaining` and expiry, once per change).
- Changes: `nextPresetMinutes(int current)` for a value that is not a preset returns the first preset above it, or `0` when above 120.

- [ ] **Step 1: Add tests to `SleepTimerTest`:**
  - `validMinutes`: false for `0`, `-1`, `721`; true for `1`, `720`.
  - `addedRemainingMs(false, 0, 15)` equals `900_000`; `addedRemainingMs(true, 600_000, 15)` equals `1_500_000`; `addedRemainingMs(true, 719*60_000L, 30)` equals `720*60_000L`.
  - `subtractedRemainingMs(600_000, 5)` equals `300_000`; `subtractedRemainingMs(120_000, 60)` equals `60_000`.
  - `nextPresetMinutes(45)` is `60`; `nextPresetMinutes(7)` is `15`; `nextPresetMinutes(130)` is `0`; existing preset cases unchanged.
- [ ] **Step 2: Run** `SleepTimerTest`; expected FAIL on the new cases.
- [ ] **Step 3: Implement** the static methods and the new `nextPresetMinutes` rule.
- [ ] **Step 4: Implement** the instance methods. `start`, `cancel`, `setRemaining` and `onFinish` must call the state observer exactly once per change (`start` currently calls `cancel` first: use an internal stop that does not notify). `onFinish` calls the expiry handler, then the UI listener.
- [ ] **Step 5: Run** `SleepTimerTest`; expected PASS.
- [ ] **Step 6: Commit** `feat: sleep timer adjust, expiry handler and state observer`.

### Task 4: WebSocket frames and client

**Files:**
- Create: `WsFrames.java`, `WebSocketClient.java`
- Test: `WsFramesTest.java`

**Interfaces:**
- Produces:
  - `WsFrames`: constants `OP_TEXT=1`, `OP_CLOSE=8`, `OP_PING=9`, `OP_PONG=10`; `static byte[] encode(int opcode, byte[] payload, byte[] maskKey)` (FIN set, masked, 7-, 16- and 64-bit lengths); `static String acceptKey(String clientKey)`; `static final class Message { final int opcode; final byte[] payload; }`; `static Message read(InputStream in) throws IOException` (reassembles continuation frames; returns one logical message; throws `IOException` on a payload over `MAX_PAYLOAD = 1_048_576` or a malformed header).
  - `WebSocketClient(String wsUrl, Listener l)`; `interface Listener { void onOpen(); void onText(String text); void onClosed(Exception reasonOrNull); }` (called on the client thread); `void start()` (spawns the thread: connect, handshake, read loop, answers ping with pong); `boolean send(String text)` (thread-safe); `void close()`; socket read timeout 75 s.

- [ ] **Step 1: Write `WsFramesTest`:**
  - `acceptKey("dGhlIHNhbXBsZSBub25jZQ==")` equals `"s3pPLMBiTxaQ9kYGzzhZRbK+xOo="` (RFC 6455 example).
  - `encode(OP_TEXT, "hi", key)` has first byte `0x81`, second byte `0x82`, then the 4 key bytes, then payload XOR key.
  - Round trip: frames built unmasked by a test helper for payload lengths `5`, `125`, `126`, `65_535`, `65_536` are read back with the same bytes.
  - A text frame split into FIN=0 text plus FIN=1 continuation reads as one message.
  - A header claiming length `2_000_000_000` throws `IOException`.
  - Truncated stream (header only) throws `IOException`.
- [ ] **Step 2: Run** `WsFramesTest`; expected FAIL.
- [ ] **Step 3: Implement** `WsFrames` (pure, JVM).
- [ ] **Step 4: Run** `WsFramesTest`; expected PASS.
- [ ] **Step 5: Implement** `WebSocketClient` over `Socket` / `SSLSocketFactory.getDefault()`: HTTP/1.1 upgrade with random `Sec-WebSocket-Key`, require status 101 and a matching `Sec-WebSocket-Accept`, then loop on `WsFrames.read`. No test on the JVM; verified on device in Task 9.
- [ ] **Step 6: Commit** `feat: minimal WebSocket client`.

### Task 5: HaSettings and token storage

**Files:**
- Create: `HaSettings.java`
- Test: `HaSettingsTest.java`

**Interfaces:**
- Produces: `static String HaSettings.parseTokenFile(String text)` (pure; returns the token or `null`); `static boolean enabled(Context)`, `static String url(Context)`, `static String device(Context)` (default `"tv"`), read from `config.json` keys `ha_enabled`, `ha_url`, `ha_device` the same way `Config` reads that file; `static String token(Context)`, `static void setToken(Context, String)` (private `SharedPreferences` named `ha_link`); `static boolean importTokenFile(Context)` (reads `ha-token.txt` in `Config.dir(ctx)`, stores via `setToken`, deletes the file; returns whether it imported).

- [ ] **Step 1: Write `HaSettingsTest` for `parseTokenFile`:**
  - `"abc.def\n"`, `"abc.def\r\n"`, `"﻿abc.def"`, `"\"abc.def\""` all give `"abc.def"`.
  - `"abc def"` gives `null` (inner space); `""`, `"   \n"` give `null`; a 2049-character string gives `null`.
- [ ] **Step 2: Run** `HaSettingsTest`; expected FAIL.
- [ ] **Step 3: Implement** `HaSettings`. Never log token values.
- [ ] **Step 4: Run** `HaSettingsTest`; expected PASS.
- [ ] **Step 5: Commit** `feat: HA settings and private token storage`.

### Task 6: HaLink, timer commands and service wiring

**Files:**
- Create: `HaBackoff.java`, `HaCommandRouter.java`, `HaTimerCommands.java`, `HaLink.java`
- Modify: `HomeKeyService.java` (`onServiceConnected`, `onDestroy`), `MainActivity.java` (`SleepTimer` listener at the block near line 688), `AndroidManifest.xml` (`<application>`)
- Test: `HaBackoffTest.java`, `HaCommandRouterTest.java`

**Interfaces:**
- Consumes: Tasks 1 to 5 as defined.
- Produces:
  - `static long HaBackoff.delayMs(int attempt)` (attempt 0 gives `2000`, then 4000, 8000, 16000, 32000, 60000, capped at 60000).
  - `HaCommandRouter`: `interface Handler { void handle(HaMessages.Command c); }`; `void register(String prefix, Handler h)`; `boolean dispatch(HaMessages.Command c, String ownDevice)` (false when the device is non-empty and differs from `ownDevice` ignoring case, or no prefix matches; prefix match is `name.startsWith(prefix + ".")`).
  - `HaLink`: `static HaLink get()`; `void start(Context ctx)`, `void stop()`, `void onSettingsChanged()`, `static Status getStatus()` with `enum Status { NOT_CONFIGURED, CONNECTING, CONNECTED, AUTH_FAILED, NETWORK_UNREACHABLE }`; `HaCommandRouter router()`; `String baseUrl()` and `String token()` for the later camera work.
  - `HaTimerCommands implements HaCommandRouter.Handler`.

- [ ] **Step 1: Write `HaBackoffTest`** (`delayMs(0)==2000`, `delayMs(1)==4000`, `delayMs(5)==60000`, `delayMs(50)==60000`, `delayMs(-1)==2000`) and **`HaCommandRouterTest`**:
  - A handler registered for `"timer"` receives `timer.start`, and `dispatch` returns true.
  - `dispatch` of `camera.show` with only `timer` registered returns false.
  - Command with `device="Living-Room"` and own device `"living-room"` is dispatched; with `device="bedroom"` it is not, and the handler is not called; with `device=""` it is dispatched.
  - `"timerx.start"` does not match prefix `"timer"`.
- [ ] **Step 2: Run** both tests; expected FAIL.
- [ ] **Step 3: Implement** `HaBackoff` and `HaCommandRouter`; run both tests; expected PASS.
- [ ] **Step 4: Implement `HaTimerCommands`** (main thread): `timer.start` (valid `minutes`) calls `SleepTimer.start`; `timer.cancel` cancels and hides the overlay; `timer.add` / `timer.subtract` call `setRemaining(addedRemainingMs…/subtractedRemainingMs…)` (`subtract` on an inactive timer does nothing); `timer.status` triggers a state report; invalid or missing `minutes` is ignored with a log line that does not print the payload. Every change that leaves a timer running calls `HomeKeyService.getInstance().showTimerOverlay()`.
- [ ] **Step 5: Implement `HaLink`** per the spec section 2: build the URL with `HaAddress`, connect with `WebSocketClient`, `auth` then `subscribeEvents(…, "glasstv_command")`, send a state event on auth, ping every 30 s, reconnect with `HaBackoff` (reset after `auth_ok`), stop retrying on `auth_invalid` (status `AUTH_FAILED` until `onSettingsChanged`), register a default network callback only while enabled and not connected, send a state event on every `SleepTimer` state-observer call and every 60 s while a timer runs. On a `RESULT` with `success=false` for a state event, log once that the HA user may need to be an administrator. Callbacks from the client thread are posted to the main thread `Handler`. `start` calls `HaSettings.importTokenFile`. When disabled or unconfigured, `stop()` closes everything and no thread or callback remains.
- [ ] **Step 6: Wire it:** `HomeKeyService.onServiceConnected` calls `SleepTimer.get().setExpiryHandler(HomeKeyService::triggerSleep)`, `HaLink.get().router().register("timer", new HaTimerCommands())`, `HaLink.get().start(this)`; `onDestroy` calls `HaLink.get().stop()` and clears the expiry handler. In `MainActivity`'s `SleepTimer.Listener`, `onExpired` now only calls `updateTimerDisplay()` (sleeping moved to the expiry handler, so it happens even after the activity is destroyed). Add `android:usesCleartextTraffic="true"` to `<application>` in `AndroidManifest.xml`.
- [ ] **Step 7: Run** `./gradlew testReleaseUnitTest assembleRelease` from `launcher/`; expected: all tests pass and the APK builds.
- [ ] **Step 8: Commit** `feat: Home Assistant link in HomeKeyService with timer commands`.

### Task 7: Settings screen section

**Files:**
- Modify: `LauncherSettingsActivity.java` (new section after "Sleep Timer", near line 92), `res/values/strings.xml` only if the file already holds the other settings strings (follow how existing rows are labelled)

**Interfaces:**
- Consumes: `HaSettings`, `HaLink.getStatus()`, `HaLink.get().onSettingsChanged()`, `HomeKeyService.getInstance()`.
- Produces: `private void addTextSetting(LinearLayout parent, String title, String subtitle, String current, boolean masked, java.util.function.Consumer<String> onSave)` (an OK-to-edit row that opens a text-entry dialog usable with the TV remote and on-screen keyboard); `private void addHaSection(LinearLayout parent, Config cfg)`.

- [ ] **Step 1: Implement `addHaSection`:** section title "Home Assistant"; toggle "Link to Home Assistant" saving `ha_enabled`; text rows "Address" (`ha_url`), "Device name" (`ha_device`, default `tv`), "Access token" (masked; subtitle shows "Set" or "Not set"; saving calls `HaSettings.setToken`); a status row showing `HaLink.getStatus()` text, or "Turn on the Home key service first" when `HomeKeyService.getInstance()` is null. After each save call `HaLink.get().onSettingsChanged()`. Refresh the status row in `onResume`.
- [ ] **Step 2: Build** with `./gradlew assembleRelease`; expected success.
- [ ] **Step 3: Commit** `feat: Home Assistant section in launcher settings`.

### Task 8: Docs

**Files:**
- Create: `docs/home-assistant.md`
- Modify: `AGENTS.md` ("Working on this repository", the "Keep the launcher light" line), `README.md` (link), `docs/TROUBLESHOOTING.md` (new entry), `SHOWCASE.md` only if it lists features

- [ ] **Step 1: Write `docs/home-assistant.md`:** what it does; creating a long-lived token (HA profile, Security); pushing it with `adb push` to `/sdcard/Android/data/com.gathin.tvhome/files/ha-token.txt` or typing it in settings; the Home key service requirement (`HOME_KEY_SERVICE=1`); the command table from the spec; HA YAML for scripts that call `event.fire` with `glasstv_command` (start 15 / 30 / 60, add 15, subtract 15, cancel); a trigger-based template sensor reading `glasstv_state`; the administrator-user note.
- [ ] **Step 2: Edit `AGENTS.md`:** change the "no network use but the weekly places pictures (none with `"places": false`)" clause to also allow the owner's own Home Assistant, only when set up and enabled, and add that it needs the Home key service.
- [ ] **Step 3: Add** a README link and a TROUBLESHOOTING entry covering: status "Auth failed", "Needs Home key service", HA rejecting `fire_event`, and `http://` addresses to public hosts being refused.
- [ ] **Step 4: Run** `python3 -m unittest discover -s tests` from the repo root; expected PASS (docs counts unchanged).
- [ ] **Step 5: Commit** `docs: Home Assistant link guide`.

### Task 9: Whole-change verification

- [ ] **Step 1: Run** `CLEANYOURTV_STRICT=1 python3 -m unittest discover -s tests` and `cd launcher && ./gradlew testReleaseUnitTest assembleRelease`; expected: all pass, APK under 400 KB (`ls -l app/build/outputs/apk/release/app-release.apk`).
- [ ] **Step 2: Ask the owner for a yes**, then install with `TV_HOST=<ip> launcher/build-and-install.sh`, enable the link in settings, and push the token file.
- [ ] **Step 3: From HA** (Developer tools, Events) fire `glasstv_command` with `timer.start` / 2 minutes; expected: overlay appears and a `glasstv_state` event with `active: true` arrives in HA within a second. Then `timer.add` 5, `timer.subtract` 60 (expect 1 minute left, not expired), `timer.cancel` (overlay gone, state `active: false`).
- [ ] **Step 4: With Netflix or YouTube in front,** start a 1-minute timer from HA and let it expire; expected: the TV sleeps.
- [ ] **Step 5: Disable the link** in settings; expected: status "Not configured", no further `glasstv_state` events, and HA shows no connection from the TV.
- [ ] **Step 6: Wrong token** in settings; expected: status "Auth failed" and no reconnect attempts in `logcat -s TvHome`.
