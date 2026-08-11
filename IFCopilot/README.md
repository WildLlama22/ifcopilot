# IF Copilot

An unofficial Android companion app for **Infinite Flight**, built on its
**Connect API** (a local network API the sim exposes for third-party tools —
this is not an in-sim plugin, since Infinite Flight has no plugin/mod SDK).

## Features

- **TOGA button** — ramps `simulator/throttle` from its current value to 95%
  over 3 seconds (eased, not linear) instead of snapping instantly.
- **V-speed reference panel** — V1 / VR / V2 per aircraft type, computed at a
  **fixed reference weight** (see Limitations below).
- **Gear warning** — repeats "Gear, Gear" if altitude AGL drops below 500ft
  while airborne and the gear isn't down.
- **Retard callout** — for Airbus types, calls "Retard" inside 20ft AGL on
  approach (see Limitations — this is altitude-only, not thrust-lever-based,
  in this first version).
- **Windshear warning** — tracks the aircraft's instantaneous headwind
  component (from `environment/wind_velocity` / `wind_direction_true` vs.
  `heading_true`) and fires if headwind drops 15kt+ either between 50–1300ft
  AGL on approach, or within 2 minutes of rotation.
- **Copilot callouts** — 80kt (Boeing-style types) or 100kt (Airbus-style),
  V1, Rotate, Positive rate — thresholds are per-aircraft-profile.
- **Confirmations** — "Gear up"/"Gear down" and "Flaps N" spoken whenever the
  gear lever or flap detent changes.
- All callouts use Android's built-in **text-to-speech**, no audio assets
  needed.

## Setup

1. In Infinite Flight: **Settings → General → Enable Infinite Flight Connect**.
2. Make sure your Android device is on the **same WiFi network** as the
   device running Infinite Flight.
3. Open IF Copilot, tap **Auto-discover** (listens for IF's UDP broadcast on
   port 15000), or type the IP/port manually if discovery doesn't find it.
4. Tap **Connect**. The app fetches the state manifest and tries to
   auto-detect the aircraft type from `aircraft/0/name` to pick a V-speed
   profile; you can override it in the dropdown.
5. Toggle whichever callouts/warnings you want in the Features card.

## Building entirely on your phone (no computer)

You can't run Android Studio on a phone, but you don't need to — this
project includes a GitHub Actions workflow (`.github/workflows/build.yml`)
that builds the debug APK for you in the cloud. You just need to get the
project onto GitHub and download the result, both doable from a phone
browser plus one small app.

**1. Install Termux.** Get it from **F-Droid** (f-droid.org/packages/com.termux),
not the Play Store version — the Play Store build is outdated and network
access is unreliable on it. Termux gives you a real Linux command line and
`git` on your phone.

**2. In Termux, install git:**
```
pkg update && pkg install git
```

**3. Create a GitHub account and a new empty repository** at github.com in
your phone's browser (sign up if needed, then the "+" → "New repository").
Name it e.g. `ifcopilot`, keep it **Public** (Actions minutes are free and
unlimited for public repos) or Private (free tier includes plenty of
Actions minutes too), don't initialize it with a README.

**4. Create a Personal Access Token** so Termux can push to GitHub: on
github.com go to Settings → Developer settings → Personal access tokens →
Tokens (classic) → Generate new token, check the "repo" scope, generate,
and copy the token somewhere safe (you'll paste it once).

**5. Get the project files into Termux.** The simplest way: in your phone's
browser, download `IFCopilot.zip` (already in your Downloads), then in
Termux:
```
pkg install unzip
cd ~
unzip /sdcard/Download/IFCopilot.zip
cd IFCopilot
```
(Termux may ask you to run `termux-setup-storage` first to access
Downloads — do that if the unzip command can't find the file.)

**6. Push it to GitHub:**
```
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/ifcopilot.git
git push -u origin main
```
When it asks for a password, paste the **Personal Access Token** from step 4
(not your GitHub password).

**7. Watch it build.** In your phone's browser, go to your repo on
github.com → the **Actions** tab. You'll see a "Build debug APK" run start
automatically. It takes a few minutes. Wait for the green checkmark.

**8. Download the APK.** Click into the finished run, scroll down to
**Artifacts**, tap `IFCopilot-debug-apk` — it downloads a zip containing
`app-debug.apk` straight to your phone.

**9. Install it.** Unzip that download (your phone's Files app can usually
do this, or install a simple unzip app), tap `app-debug.apk`. Android will
ask you to allow installs from that source (Settings → apps → allow
"install unknown apps" for whichever app you opened it with) — allow it,
then install.

**10. From here on it's the same as before:** open Infinite Flight →
Settings → General → enable Connect, open IF Copilot, skip Auto-discover,
enter Host `127.0.0.1` and Port `10112`, tap Connect, then fly. Use the
**TOGA button on the persistent notification** to engage TOGA without
switching away from Infinite Flight.

One thing to know: every time you want to change the code and rebuild,
repeat steps 5-9 (or if you keep the repo, just edit files directly on
github.com and each push auto-triggers a new build).

## Opening the project on a computer (alternative to the phone-only path above)

This is a standard Gradle Android project (Kotlin + Jetpack Compose). Open
the root folder in **Android Studio** (Koala/2024.1+ recommended) — it will
generate the Gradle wrapper automatically on first sync. Minimum SDK 26.

## Building and running it on a computer

**1. Install Android Studio** (free, from developer.android.com/studio) if
you don't already have it. During setup, let it install the Android SDK —
you don't need to install anything else separately.

**2. Open the project.** Unzip `IFCopilot.zip`, then in Android Studio:
`File → Open` → select the unzipped `IFCopilot` folder (the one containing
`settings.gradle.kts`) → OK.

**3. Let Gradle sync.** The first open takes a few minutes — Android Studio
downloads the Gradle wrapper and dependencies automatically. If it prompts
"Gradle wrapper not found, generate one?", say yes. Watch the status bar at
the bottom; wait until it says "Gradle sync finished" before doing anything
else. If sync fails on a missing SDK component, click the "Install missing
SDK package(s)" link it offers.

**4. Get a device to run it on.** If you have two devices, use a second
phone/tablet on the same WiFi and skip to the two-device instructions below.

**If you only have one device**, Infinite Flight and IF Copilot both run on
it — that's fine, since the Connect API is just a local socket and works
over loopback:
   - Build and install IF Copilot on the same device Infinite Flight is on
     (steps 2-3 above, run via USB from Android Studio, then you can
     disconnect the cable — the app stays installed).
   - In IF Copilot, skip Auto-discover (broadcast discovery to yourself
     isn't reliable) and instead enter Host = `127.0.0.1`, Port = `10112`
     (or whatever port IF's Connect settings screen shows), then Connect.
   - Once connected, put Infinite Flight in the foreground and fly — the
     monitor keeps running and speaking callouts in the background via the
     foreground service, even with IF Copilot's UI not visible.
   - For the **TOGA button**, you obviously can't tap the in-app button
     while IF is fullscreen in front of it — so I added a **TOGA action
     button directly on the persistent notification**. Pull down the
     notification shade over Infinite Flight and tap "TOGA" right there,
     no app switching needed.

**On a real two-device setup:**
   - On the IF Copilot phone: Settings → About phone → tap "Build number" 7
     times to unlock Developer options → back out → Settings → Developer
     options → enable **USB debugging**.
   - Plug that phone into your computer via USB, accept the debugging
     prompt, then it should appear in Android Studio's device dropdown.

**5. Run it.** Click the green ▶ Run button (or Shift+F10). Android Studio
builds the APK and installs it on your phone. First launch may prompt for
the notification permission (needed for the foreground service on Android
13+) — allow it.

**6. Test against Infinite Flight.**
   - Put your phone and the device running Infinite Flight on the **same
     WiFi network**.
   - In Infinite Flight: Settings → General → enable **Infinite Flight
     Connect**.
   - Open IF Copilot on your phone, tap **Auto-discover**. If it doesn't
     find IF within a few seconds, some routers block UDP broadcast between
     devices ("client isolation") — in that case, find IF's IP manually (the
     Connect settings screen in IF shows it) and type it into the Host field
     with port `10112`, then tap Connect.
   - Once connected, the status line should show the detected aircraft and
     which profile it picked. Try the individual features one at a time:
     taxi out and watch the live status card update, then do a takeoff roll
     to hear the speed callouts, retract the gear to hear the confirmation,
     etc.

**7. If something doesn't work, check Logcat.** In Android Studio, open the
Logcat panel (bottom of the window) while the app is running and filter by
`com.ifcopilot.app`. This is the fastest way to see connection errors or
parsing exceptions. If state values come back as garbage/NaN once connected,
that's almost certainly the byte-order assumption mentioned above — try
flipping `Proto.ENDIAN` in `ConnectApiClient.kt` from `LITTLE_ENDIAN` to
`BIG_ENDIAN`, then re-run.

## Important limitations / things to verify

- **Byte order assumption**: Infinite Flight's official Connect API docs
  describe the message *shape* (ID, then a bool for get/set, then the typed
  value; strings length-prefixed) but don't publish the exact endianness on
  the wire. `ConnectApiClient.kt` assumes **little-endian**, matching how
  reference samples built with .NET's `BitConverter` behave on essentially
  all real-world CPUs. If values come back as garbage/NaN when you test on
  your network, that's the first thing to try flipping — change
  `Proto.ENDIAN` to `ByteOrder.BIG_ENDIAN`.
- **No live weight/W&B in the API**: the Connect API manifest doesn't expose
  gross weight, ZFW, or payload. Per your call, this build uses a single
  fixed reference weight per aircraft type — the V-speeds shown are
  reasonable *approximations*, not computed from your actual load. If you
  ever want it more accurate, the only path is either manual weight entry in
  the UI (I can add this) or accepting the fixed-weight approximation as-is.
- **Reference weight = OEW/MTOW midpoint** (`OEW + 0.5 * (MTOW - OEW)`).
  This isn't a certified limit like MLW — nobody dispatches "at the
  midpoint" — but it's self-consistent: it lands at roughly the same
  ~75–78% of MTOW for every aircraft here, which is arguably a better proxy
  for "a generically, moderately loaded flight" than MLW, whose fraction of
  MTOW swings 72–85% across types for structural-design reasons unrelated
  to typical payload.
- **V-speeds are scaled, not pulled from a real performance manual.**
  Starting from a heavier baseline figure, they're adjusted to the new
  reference weight using sqrt(weight ratio) — a standard rough approximation
  for how reference speeds move with weight, not manufacturer FCOM/AFM data.
  IF doesn't publish official V-speed data either way. Treat these as
  immersion, not gospel.
- **Retard callout** currently triggers on altitude alone (1–20ft AGL) for
  Airbus profiles, not on actual thrust lever position, since that wiring
  isn't connected in this first pass. The manifest does expose
  `aircraft/0/systems/engines/{n}/throttle_lever` per engine if you want to
  gate it on "still above idle" — happy to wire that in as a follow-up.
- **Windshear detection** is a heuristic (instantaneous headwind component
  sampled continuously against a captured baseline), not a real
  microburst/shear model — Infinite Flight doesn't expose a wind profile by
  altitude, only the wind at the aircraft's current position.
- The service auto-starts on app launch and keeps polling at ~5Hz over TCP;
  it uses a low-priority foreground notification so Android doesn't kill it.

## Suggested next steps

- Wire the throttle-lever check into the retard callout.
- Add a manual ZFW/fuel entry field if you want closer-to-real V-speeds.
- Persist feature toggle preferences (currently reset each launch).
- Add per-engine TOGA control for twin/quad throttle levers instead of the
  single global throttle state.
