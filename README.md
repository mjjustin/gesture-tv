# GestureTV ✋📺

Control an Android TV with hand gestures and voice commands, using a USB webcam (with a built-in mic) plugged into the TV.
Change the volume by moving your hand up and down in the air, browse the home screen with an open palm,
open Netflix or YouTube with a finger count, say *"Jarvis, pause"* when it's too dark to see your hand,
and show the kids a "5 minutes of TV left" countdown.

Everything runs **on the TV itself**: hand tracking is Google's [MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/vision/gesture_recognizer)
gesture model on the TV's GPU, and speech is recognised offline by [Vosk](https://alphacephei.com/vosk/).
No cloud, no account, and no camera images or audio leave the TV.

Built and tested on a **Philips 75PUS8546 (Android TV 11, 32-bit MediaTek)** with a Logitech StreamCam (USB-C, through a USB-A adapter).

---

## Gestures

First **switch gestures on**: hold an **open palm ✋ still for 1 second**. A small green ✋ appears in the top-right corner.
It stays on for 10 seconds after your last gesture, so people waving on the sofa don't change anything.

| Gesture | Does |
|---|---|
| ✋ Open palm, hold 1 s | Gestures **on** |
| ✋ Open palm, move **slowly** ⬅ ➡ ⬆ ⬇ | Arrow keys, one tile per step |
| 👌 OK sign, move up / down | Volume up / down (like a slider) |
| ✊ Fist | OK / select |
| 🤟 Thumb + index + pinky | Back |
| ☝️ 1 finger | Home |
| ✌️ 2 fingers | Open app #1 (default **Netflix**) |
| 3 fingers (index, middle, ring) | Open app #2 (default **YouTube**) |
| 👎 Thumbs down, hold 1 s | Mute / unmute |
| 👍 Thumbs up, hold 2 s | **Kids' timer**: "Only 5 minutes left of TV time!", a countdown at the top, and a "TV time is over!" message that **stays until you cancel it** with 👍 again |

Either hand works; use one hand at a time. Fast movements (dropping your hand to rest) are ignored.

Open the **GestureTV** app on the TV to see the live camera view with your hand's skeleton drawn on it. Handy for aiming the camera.

---

## Voice commands 🎤

Works with any webcam that has a microphone. Handy in a dark room, where the camera can't see your hand.

- Say **"Jarvis"** (or "Hey Jarvis" / "Hello Jarvis"). The TV **turns down** to 30% so it can hear you, and the corner shows 🎤 listening….
- Then say one or more commands. Each command gives you another 5 seconds, so *"Jarvis … right … right … open"* works.
  You can also say it in one breath: *"Jarvis, pause"*.
- The volume goes back to where it was when you stop talking, when you say **"thank you"**, or straight away when you **open an app**,
  so the show's own sound can't trigger anything.
- **Palm up** switches on gestures **and** voice together (✋ 🎤): no "Jarvis" needed until gestures switch off or you open an app.

| Say | Does |
|---|---|
| volume up / louder · volume down / quieter | Volume (3 steps) |
| mute · unmute | Mute |
| pause · play / resume | Pause / play the video |
| left · right · up · down | Arrow keys |
| ok · select · open · enter | OK / open the selected tile |
| go home · go back | Home / Back |
| open netflix · open youtube (or just the name) | Open app #1 / #2 |
| five minutes left · start timer · cancel timer | Kids' timer |
| thank you · stop listening | Stop listening now |

Commands without "Jarvis" are ignored (unless your palm is up), so people talking on TV don't change anything.
The speech model (~40 MB) is downloaded once, the first time the app starts; after that it works offline.

---

## What you need

- **An Android TV, Android 8 or newer**, that supports USB webcams. Run the `check` step below to find out.
- **A USB webcam** (UVC, "plug and play") **with a built-in microphone** for voice commands. 720p is plenty.
  Put it on top of or under the TV, facing the sofa. A USB-C webcam needs a USB-C → USB-A adapter on most TVs.
  For gestures in a dark room, get one with **infrared night vision**; otherwise use voice commands in the dark.
- **A computer** (Mac, Linux, or Windows with WSL) with:
  - `adb` ([Android platform tools](https://developer.android.com/tools/releases/platform-tools))
  - Python 3
  - To build the app yourself: JDK 17 and the Android SDK (Android Studio installs both)

### Philips TVs vs other Android TVs

| Feature | Philips Android TV (paired) | Other Android TVs |
|---|---|---|
| Volume, mute | ✅ | ✅ |
| App shortcuts, Home | ✅ | ✅ |
| Kids' timer, on-screen messages | ✅ | ✅ |
| Voice commands for the above | ✅ | ✅ |
| Arrow keys, OK, Back (gesture or voice) | ✅ | ❌ |
| Turning the TV down while listening | ✅ | ✅ (via Android volume) |

An ordinary Android app isn't allowed to press remote buttons inside other apps. On Philips TVs GestureTV uses
the TV's built-in remote-control API (*JointSpace*, port 1926), after a one-time PIN pairing.
Other brands have no such API, so the navigation gestures do nothing there.

### Will it work on my TV?

Not every Android TV, and not without a computer. What has to be true:

- **USB webcam support.** Android only shows external cameras if the TV maker enabled it. Many recent Android TVs
  have it (tested: Philips with Android TV 11), but some don't, and many streaming sticks don't.
  You also need a free USB port that gives enough power. The `check` step below tells you in 10 seconds.
- **The mic** works on almost any TV that sees the camera, as long as nothing else (Google Assistant, a video call app) is using it.
- **One-time setup from a computer.** You don't change any TV settings for daily use, but two permissions
  the app needs (*display over other apps* and *usage access*) are hidden on most TVs and can only be given with `adb`.
  The installer does this for you; it takes a minute.
- **After the TV restarts** (not standby), open the GestureTV app once to start it again.

**Without a computer** (e.g. with the *Downloader* app and the APK link below) the app installs and the camera works,
but without those two permissions you won't see the on-screen icon and messages, and the app shortcuts may not open.

---

## Setup

### 1. Enable debugging on the TV

1. **Settings → About → Build**: press OK **7 times** to unlock Developer options.
2. **Settings → Developer options**: turn on **USB debugging** (on most TVs this also allows network debugging).
3. Find the TV's IP address (**Settings → Network**). Below it's `192.168.1.50`; use yours.

### 2. Check the TV

Plug the webcam in, then:

```bash
git clone https://github.com/mjjustin/gesture-tv.git
cd gesture-tv
python3 tools/gesturetv.py --tv 192.168.1.50 check
```

Accept the **"Allow debugging?"** prompt on the TV (tick "always allow"). You want ✅ for USB host and
external camera, and `Number of camera devices: 1`.

### 3. Get the app

**Easiest:** use the ready-made APK. It's already in the `release/` folder you just cloned, and the installer in step 5
picks the right one for your TV automatically. Skip to step 4.

| File | For |
|---|---|
| [GestureTV-1.1-armeabi-v7a.apk](https://github.com/mjjustin/gesture-tv/raw/main/release/GestureTV-1.1-armeabi-v7a.apk) (31 MB) | **Almost every TV.** Most TVs run 32-bit apps, even with a 64-bit chip |
| [GestureTV-1.1-arm64-v8a.apk](https://github.com/mjjustin/gesture-tv/raw/main/release/GestureTV-1.1-arm64-v8a.apk) (37 MB) | TVs that only run 64-bit apps |

The links are for downloading without git. The installer and an `adb install` both work with them.

**Or build it yourself:**

```bash
./gradlew assembleDebug
```

The gesture model (~8 MB) is downloaded automatically on the first build. You get one APK per CPU type in
`app/build/outputs/apk/debug/`; the installer prefers your own build over `release/`.
(An APK you build is signed with your own debug key: uninstall the release version first.)
If your Android SDK isn't found, create `local.properties` with `sdk.dir=/path/to/Android/sdk`.

### 4. Pair with the TV (Philips only)

```bash
python3 tools/gesturetv.py --tv 192.168.1.50 pair
```

A 4-digit PIN appears on the TV. Type it into the terminal within 60 seconds.
This writes `config.json` with the pairing key. **Keep that file private** (it's in `.gitignore`).

### 5. Install

```bash
python3 tools/gesturetv.py --tv 192.168.1.50 install
# or a specific APK, e.g. one you downloaded:
python3 tools/gesturetv.py --tv 192.168.1.50 --apk ~/Downloads/GestureTV-1.1-armeabi-v7a.apk install
```

This installs the APK, grants its permissions (camera, microphone, display over other apps, usage access), copies `config.json` to the TV and starts it.
Hold up an open palm, or wait a minute for the speech model to download and say "Jarvis". 🎉

**After the TV restarts**, open GestureTV once from the home screen to start it again.

---

## Configuration

Copy `config.example.json` to `config.json` (the `pair` step does this for you), edit it, then push it:

```bash
python3 tools/gesturetv.py --tv 192.168.1.50 config
```

| Key | Default | Meaning |
|---|---|---|
| `tv.host` | `127.0.0.1` | Where the Philips API is. The app runs on the TV itself, so keep this. |
| `tv.user`, `tv.password` | empty | Filled in by `pair`. Empty = not paired (no arrow keys / OK / Back). |
| `armHoldSeconds` | `1.0` | How long to hold the open palm to switch gestures on |
| `activeSeconds` | `10` | Gestures switch off this long after your last gesture |
| `mirrorCamera` | `true` | Set `false` if left and right come out swapped |
| `homeLauncherPackage` | empty | Home-screen app, used to show the faint ✋ only on the home screen. Empty = the TV's default launcher. |
| `shortcuts.twoFingers` / `threeFingers` | Netflix / YouTube | `name` shown on screen + Android `package` of the app to open |
| `kidsTimer.minutes` | `5` | Length of the kids' timer |
| `kidsTimer.startMessage` | "Only {minutes} minutes left of TV time!" | `{minutes}` is replaced |
| `kidsTimer.endMessage` | "TV time is over! …" | Stays on screen until cancelled with 👍 |
| `shortcuts.*.spoken` | the app name | What to say to open it, e.g. `"youtube"` for a YouTube alternative |
| `voice.wakeWord` | `"always"` | `"always"`: listen for "Jarvis" all the time; `"dark"`: only when the room is dark; `"off"` |
| `voice.withPalm` | `true` | Palm up also switches on voice commands (no "Jarvis" needed) |
| `voice.listenSeconds` | `5` | How long to wait for (the next) command after "Jarvis" |
| `voice.duckPercent` | `30` | Turn the TV down to this % while listening. `0` = mute, `100` = don't change |
| `voice.volumeSteps` | `3` | Steps per "volume up" / "volume down" |

To find an app's package name:

```bash
adb -s 192.168.1.50:5555 shell pm list packages | grep -i youtube
```

---

## Troubleshooting

- **What is it seeing?** Open the GestureTV app: the camera view shows the hand skeleton, the recognised gesture (`Open_Palm`, `Thumb_Up`, `Pinch`, …) and the light level.
- **Event log:** `python3 tools/gesturetv.py --tv 192.168.1.50 log` shows gestures, actions and API results (the TV's own system log is usually flooded by the camera driver).
- **Dark room:** the app boosts exposure and brightness automatically, but a normal webcam can't see in the dark. Use an IR night-vision webcam or a small lamp.
- **`adb` says "No route to host" on macOS** while `ping` works: System Settings → Privacy & Security → **Local Network** → toggle your terminal app off and on, quit the terminal fully, reopen.
- **Arrow keys do nothing:** not paired (Philips only). Run `pair`, then `config`.
- **Left/right are swapped:** set `"mirrorCamera": false`.
- **"Jarvis" does nothing:** check the log for `voice ready (mic: …)`. If the model download failed (no internet), restart the app.
  Speak towards the camera; from a sofa 3 m away a normal voice is enough once the TV turns down.
- **Voice triggers by itself:** use `"wakeWord": "dark"` or `"off"`, and keep `withPalm`.

## Performance

On the Philips test TV (4× Cortex-A73, Mali-G52):

- **Nobody gesturing:** 8 checks per second, about a third of one CPU core.
- **Hand in view or gestures on:** about 24 checks per second, about three-quarters of one core, only for those seconds.
- **TV in standby:** the camera is switched off.
- **Voice, quiet room:** about a third of one core. The speech recogniser only runs while there is sound.
- **Memory:** about 150–280 MB.

## How it works

- `GestureService`: a foreground service that keeps running behind YouTube and Netflix.
  - It reads the webcam with CameraX and runs MediaPipe's gesture recognizer. This uses version 0.10.14, the last release that ships 32-bit ARM libraries, which many TVs need.
  - It adds a few custom poses from the hand landmarks: 👌, three fingers.
- `GestureEngine`: turns per-frame labels into actions.
  - Hold timers, a short debounce against flicker, and stillness checks.
  - Drag sliders for volume and arrows; fast movements are ignored.
- `VoiceControl`: reads the webcam mic, skips silence, and runs Vosk with a fixed list of phrases
  (much more accurate than free dictation). It handles the wake word, the listening window and several commands in a row.
- `JointSpace`: a tiny client for the Philips remote API (HTTPS + digest auth).
- `Config`: reads `config.json`.

## Uninstall

```bash
python3 tools/gesturetv.py --tv 192.168.1.50 uninstall
```

Then turn USB debugging off again in Developer options.

## Credits & licences

- App code: MIT licence (see `LICENSE`).
- [MediaPipe](https://github.com/google-ai-edge/mediapipe) and its gesture recognizer model: Apache 2.0, by Google.
- [Vosk](https://github.com/alphacep/vosk-api) and the small English model: Apache 2.0, by Alpha Cephei.
- The Philips JointSpace pairing method is as documented by [pylips](https://github.com/eslavnov/pylips).
- Not affiliated with Philips, TP Vision, Google or Netflix. Use at your own risk.
