#!/usr/bin/env python3
"""
GestureTV setup helper. Needs Python 3 and adb on your PATH.

  python3 tools/gesturetv.py --tv 192.168.1.50 check     # can this TV run it?
  python3 tools/gesturetv.py --tv 192.168.1.50 pair      # Philips only: pair with the TV (PIN on screen)
  python3 tools/gesturetv.py --tv 192.168.1.50 install   # install APK, grant permissions, push config, start
  python3 tools/gesturetv.py --tv 192.168.1.50 config    # push an edited config.json and restart the app
  python3 tools/gesturetv.py --tv 192.168.1.50 log       # show the app's event log
  python3 tools/gesturetv.py --tv 192.168.1.50 uninstall
"""
import argparse, base64, hashlib, hmac, json, os, random, ssl, string, subprocess, sys, urllib.request

PKG = "app.gesturetv"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "config.json")
EXAMPLE = os.path.join(ROOT, "config.example.json")
DEFAULT_APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
REMOTE_DIR = f"/sdcard/Android/data/{PKG}/files"

# Shared secret every Philips JointSpace client uses to sign the pairing PIN
# (as published by the pylips project, https://github.com/eslavnov/pylips).
JS_SECRET = base64.b64decode("ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA==")


def adb(serial, *args, check=True, capture=False):
    cmd = ["adb", "-s", serial, *args]
    r = subprocess.run(cmd, text=True, capture_output=capture)
    if check and r.returncode != 0:
        sys.exit(f"adb failed: {' '.join(args)}\n{r.stderr if capture else ''}")
    return r.stdout if capture else ""


def connect(tv):
    serial = f"{tv}:5555"
    out = subprocess.run(["adb", "connect", serial], text=True, capture_output=True).stdout
    print(out.strip())
    state = subprocess.run(["adb", "-s", serial, "get-state"], text=True, capture_output=True)
    if state.stdout.strip() != "device":
        sys.exit("Not connected. On the TV: enable Developer options + USB/network debugging, "
                 "then accept the 'Allow debugging?' prompt and run this again.")
    return serial


def load_config():
    path = CONFIG if os.path.exists(CONFIG) else EXAMPLE
    with open(path) as f:
        return json.load(f)


def save_config(c):
    with open(CONFIG, "w") as f:
        json.dump(c, f, indent=2, ensure_ascii=False)
    os.chmod(CONFIG, 0o600)


# ---------- commands ----------

def cmd_check(tv):
    s = connect(tv)
    feats = adb(s, "shell", "pm", "list", "features", capture=True)
    abi = adb(s, "shell", "getprop", "ro.product.cpu.abilist", capture=True).strip()
    sdk = adb(s, "shell", "getprop", "ro.build.version.sdk", capture=True).strip()
    cams = adb(s, "shell", "dumpsys media.camera | grep 'Number of camera devices'", capture=True, check=False).strip()
    ok = lambda b: "✅" if b else "❌"
    print(f"{ok(int(sdk or 0) >= 26)} Android API {sdk} (needs 26+, Android 8)")
    print(f"{ok('android.hardware.usb.host' in feats)} USB host")
    print(f"{ok('android.hardware.camera.external' in feats)} External (USB) camera support")
    print(f"{ok('armeabi-v7a' in abi or 'arm64-v8a' in abi)} CPU: {abi}")
    print(f"   {cams or 'camera service: unknown'}  (plug the webcam in; should be 1 or more)")
    try:
        ctx = ssl._create_unverified_context()
        urllib.request.urlopen(f"https://{tv}:1926/6/system", context=ctx, timeout=5)
        print("✅ Philips JointSpace API found: run 'pair' for arrow keys / OK / Back")
    except Exception:
        print("ℹ️  No Philips JointSpace API: volume, mute, Home, app shortcuts and the timer work; "
              "arrow keys / OK / Back need a Philips Android TV")


def _post(url, body, auth=None):
    ctx = ssl._create_unverified_context()
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    handlers = [urllib.request.HTTPSHandler(context=ctx)]
    if auth:
        pm = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        pm.add_password(None, url, *auth)
        handlers.append(urllib.request.HTTPDigestAuthHandler(pm))
    return json.loads(urllib.request.build_opener(*handlers).open(req, timeout=15).read().decode() or "{}")


def cmd_pair(tv):
    base = f"https://{tv}:1926/6"
    dev_id = "".join(random.choices(string.ascii_letters + string.digits, k=16))
    device = {"device_name": "gesturetv", "device_os": "Android", "app_id": "app.gesturetv",
              "app_name": "GestureTV", "type": "native", "id": dev_id}
    r = _post(base + "/pair/request", {"scope": ["read", "write", "control"], "device": device})
    if "auth_key" not in r:
        sys.exit(f"Pairing request failed: {r}")
    pin = input("Type the 4-digit PIN shown on the TV (within 60 s): ").strip()
    sig = base64.b64encode(hmac.new(JS_SECRET, (str(r["timestamp"]) + pin).encode(), hashlib.sha1)
                           .hexdigest().encode()).decode()
    body = {"auth": {"auth_AppId": "1", "pin": pin, "auth_timestamp": r["timestamp"], "auth_signature": sig},
            "device": device}
    g = _post(base + "/pair/grant", body, auth=(dev_id, r["auth_key"]))
    if g.get("error_id") != "SUCCESS":
        sys.exit(f"Pairing failed: {g}")
    c = load_config()
    c.setdefault("tv", {})
    c["tv"].update({"host": "127.0.0.1", "user": dev_id, "password": r["auth_key"]})
    save_config(c)
    print(f"Paired. Saved to {CONFIG} (keep it private). Now run: install  (or: config)")


def push_config(s):
    if not os.path.exists(CONFIG):
        save_config(load_config())          # start from the example
        print(f"Created {CONFIG} from the example (not paired).")
    adb(s, "shell", "mkdir", "-p", REMOTE_DIR)
    adb(s, "push", CONFIG, f"{REMOTE_DIR}/config.json")


def restart(s):
    adb(s, "shell", "am", "force-stop", PKG)
    adb(s, "shell", "am", "start", "-n", f"{PKG}/.MainActivity")


def cmd_install(tv, apk):
    s = connect(tv)
    if not os.path.exists(apk):
        sys.exit(f"APK not found: {apk}\nBuild it first: ./gradlew assembleDebug")
    adb(s, "install", "-r", apk)
    adb(s, "shell", "pm", "grant", PKG, "android.permission.CAMERA")
    adb(s, "shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")   # status icon + messages + app shortcuts
    adb(s, "shell", "appops", "set", PKG, "GET_USAGE_STATS", "allow")       # show ✋ icon only on the home screen
    push_config(s)
    restart(s)
    print("Installed and started. Hold an open palm in front of the camera for 1 s to begin.")


def cmd_config(tv):
    s = connect(tv)
    push_config(s)
    restart(s)
    print("Config pushed and app restarted.")


def cmd_log(tv):
    s = connect(tv)
    print(adb(s, "shell", "cat", f"{REMOTE_DIR}/events.log", capture=True, check=False)[-6000:])


def cmd_uninstall(tv):
    s = connect(tv)
    adb(s, "uninstall", PKG, check=False)
    print("Uninstalled.")


def main():
    ap = argparse.ArgumentParser(description="GestureTV setup helper")
    ap.add_argument("--tv", required=True, help="TV IP address, e.g. 192.168.1.50")
    ap.add_argument("--apk", default=DEFAULT_APK, help="APK to install (default: the debug build)")
    ap.add_argument("command", choices=["check", "pair", "install", "config", "log", "uninstall"])
    a = ap.parse_args()
    {"check": lambda: cmd_check(a.tv), "pair": lambda: cmd_pair(a.tv),
     "install": lambda: cmd_install(a.tv, a.apk), "config": lambda: cmd_config(a.tv),
     "log": lambda: cmd_log(a.tv), "uninstall": lambda: cmd_uninstall(a.tv)}[a.command]()


if __name__ == "__main__":
    main()
