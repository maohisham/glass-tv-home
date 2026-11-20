# Home Assistant link

Glass TV can keep one connection open to your own Home Assistant (HA). Through it, HA can start, change and
cancel the **sleep timer** and see what the timer is doing. It is built to carry more later (a door-camera
pop-up is planned), so you set it up once.

It is **off by default**. With it off or not set up, the launcher makes no connection to anything but the
weekly places pictures. When it is on, the only place it talks to is the HA address you typed in.

## What you need

- The **Home key service** switched on (it is the always-running part that holds the connection and draws the
  timer over other apps). Install with `HOME_KEY_SERVICE=1 launcher/build-and-install.sh`, or switch it on in the
  TV's Accessibility settings.
- A **long-lived access token** from a Home Assistant user. Create it in HA: your profile → **Security** →
  **Long-lived access tokens** → **Create token**. The user needs to be an **administrator**, because HA only
  lets administrators fire and listen to custom events over the connection.

## Set it up on the TV

1. On the TV open Glass TV's **Launcher Settings** (long-press the Settings pill) → **Home Assistant**.
2. **Address**: where HA is on your network, for example `http://homeassistant.local:8123`, or an
   `https://` address. Without a port, `http` addresses use HA's usual `8123`. You can paste the address from
   your browser: anything after the host (a dashboard page, a query) is dropped. Plain `http://` is only accepted for addresses inside your home network (a private IP,
   `localhost`, or a name ending in `.local` or `.lan`); anything on the internet must use `https://`.
3. **Access token**: type it, or put it in a file and push it so you do not have to type about 180
   characters with a remote. Save the token as the only text in `ha-token.txt`, then push it into the app's files
   folder:

   ```sh
   adb push ha-token.txt /sdcard/Android/data/com.gathin.tvhome/files/ha-token.txt
   ```

   The launcher reads the file the next time the link starts or settings change, keeps the token in its own
   private storage, and **deletes the file**. The token is never written into `config.json`, never logged, and
   never shown in full on screen.
4. **Device name** (optional): a name for this TV, `tv` by default. Use it when you have several TVs.
5. Switch on **Link to Home Assistant**. The **Status** line shows *Connecting…* and then *Connected*.

## Commands: Home Assistant to the TV

Fire the event `glasstv_command` (from a script, an automation, or **Developer tools → Events**):

| `command` | `minutes` | What happens |
| --- | --- | --- |
| `timer.start` | 1 to 720 | Starts a countdown of that length. A running timer is restarted at that length. |
| `timer.cancel` | not used | Stops the timer and removes the countdown from the screen. |
| `timer.add` | 1 to 720 | Adds time to the running timer. If none is running, starts one of that length. Never above 720 minutes. |
| `timer.subtract` | 1 to 720 | Removes time from the running timer, never below 1 minute left, and never ends it. Does nothing if no timer is running. |
| `timer.status` | not used | The TV reports its timer state right now. |

Add `"device": "<name>"` to talk to one TV only; without it every linked TV obeys. Names are compared ignoring
upper and lower case. `minutes` must be a whole number (`30` or `30.0` are both fine). Commands with a missing, zero, negative,
fractional (`4.5`) or text `minutes`, and commands the
launcher does not know, are ignored.

When the timer reaches zero the TV goes to sleep, even if you are in another app.

Example scripts (**Settings → Automations & scenes → Scripts → Add script → Edit in YAML**):

```yaml
sleep_tv_30:
  alias: Sleep TV in 30 minutes
  sequence:
    - event: glasstv_command
      event_data:
        command: timer.start
        minutes: 30

tv_timer_more:
  alias: TV timer plus 15 minutes
  sequence:
    - event: glasstv_command
      event_data:
        command: timer.add
        minutes: 15

tv_timer_less:
  alias: TV timer minus 15 minutes
  sequence:
    - event: glasstv_command
      event_data:
        command: timer.subtract
        minutes: 15

tv_timer_off:
  alias: Cancel TV sleep timer
  sequence:
    - event: glasstv_command
      event_data:
        command: timer.cancel
```

## State: the TV to Home Assistant

The TV fires the event `glasstv_state` when it connects, whenever the timer starts, changes, is cancelled or
ends, in answer to `timer.status`, and about once a minute while a timer runs:

```json
{ "device": "tv", "timer": { "active": true, "minutes": 45, "remaining_seconds": 2310 } }
```

Turn it into sensors with a trigger-based template (add to `configuration.yaml`, or use a template helper):

```yaml
template:
  - triggers:
      - trigger: event
        event_type: glasstv_state
    sensor:
      - name: TV sleep timer remaining
        unique_id: glasstv_timer_remaining
        unit_of_measurement: s
        device_class: duration
        state: "{{ trigger.event.data.timer.remaining_seconds }}"
    binary_sensor:
      - name: TV sleep timer active
        unique_id: glasstv_timer_active
        state: "{{ trigger.event.data.timer.active }}"
```

The remaining time is updated about once a minute, not every second. Use it to show roughly how long is left;
it is not a live clock.

## If it does not connect

See [Home Assistant link](TROUBLESHOOTING.md#home-assistant-link-does-not-connect) in the troubleshooting guide.
