# GestureTV ✋📺

Control an Android TV with hand gestures, using a cheap USB webcam plugged into the TV.
Change the volume by moving your hand up and down in the air, browse the home screen with an open palm,
open Netflix or YouTube with a finger count, and show the kids a "5 minutes of TV left" countdown.

Everything runs **on the TV itself**: hand tracking is Google's [MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/vision/gesture_recognizer)
gesture model, running on the TV's GPU. No cloud, no account, and no camera images leave the TV.

Built and tested on a **Philips 75PUS8546 (Android TV 11, 32-bit MediaTek)** with a basic USB-C webcam (through a USB-A adapter).

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

## What you need

- **An Android TV, Android 8 or newer**, that supports USB webcams. Run the `check` step below to find out.
- **A USB webcam** (UVC, "plug and play"). 720p is plenty. Put it on top of or under the TV, facing the sofa.
  For a dark room, get one with **infrared night vision** (built-in IR LEDs); normal webcams need some light.
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
| Arrow keys, OK, Back | ✅ | ❌ |

An ordinary Android app isn't allowed to press remote buttons inside other apps. On Philips TVs GestureTV uses
the TV's built-in remote-control API (*JointSpace*, port 1926), after a one-time PIN pairing.
Other brands have no such API, so the navigation gestures do nothing there.

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

**Easiest:** use the ready-made APK in [`release/`](release/), which works on 32-bit and 64-bit TVs, and skip to step 4.
In step 5 add `--apk release/GestureTV-1.0.apk`.

**Or build it yourself:**

```bash
./gradlew assembleDebug
```

The gesture model (~8 MB) is downloaded automatically on the first build.
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
# using the ready-made APK instead of your own build:
python3 tools/gesturetv.py --tv 192.168.1.50 --apk release/GestureTV-1.0.apk install
```

This installs the APK, grants its permissions (camera, display over other apps, usage access), copies `config.json` to the TV and starts it.
Hold up an open palm. 🎉

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

## Performance

On the Philips test TV (4× Cortex-A73, Mali-G52):

- **Nobody gesturing:** 8 checks per second, about a third of one CPU core.
- **Hand in view or gestures on:** about 24 checks per second, about three-quarters of one core, only for those seconds.
- **TV in standby:** the camera is switched off.
- **Memory:** about 150–200 MB.

## How it works

- `GestureService`: a foreground service that keeps running behind YouTube and Netflix.
  - It reads the webcam with CameraX and runs MediaPipe's gesture recognizer. This uses version 0.10.14, the last release that ships 32-bit ARM libraries, which many TVs need.
  - It adds a few custom poses from the hand landmarks: 👌, three fingers.
- `GestureEngine`: turns per-frame labels into actions.
  - Hold timers, a short debounce against flicker, and stillness checks.
  - Drag sliders for volume and arrows; fast movements are ignored.
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
- The Philips JointSpace pairing method is as documented by [pylips](https://github.com/eslavnov/pylips).
- Not affiliated with Philips, TP Vision, Google or Netflix. Use at your own risk.
