# MotionPad

Turns an Android phone into a **Joy-Con (R) with gyroscope** for the Nintendo Switch, over Bluetooth, **no root required**. Built to play *Switch Sports*.

> ⚠️ Experimental project, not affiliated with Nintendo. "Joy-Con" and "Nintendo Switch" are trademarks of Nintendo; the name is used only because the Switch requires that Bluetooth name to accept the controller.

## Status

| Part | State |
|---|---|
| Pairing with the Switch | ✅ works (on HyperOS it needed help via adb, see below) |
| Full handshake (info, SPI, 0x30 mode, IMU, lights, rumble) | ✅ |
| Buttons and analog stick | ✅ reach the Switch |
| Gyroscope/accelerometer (IMU mode 1 and **mode 2 / quaternion**) | ✅ implemented, ⏳ still needs in-game validation |
| Rumble (HD Rumble → phone vibrator) | ✅ approximated |
| Automatic reconnection to the last Switch | ✅ |
| **Switch Sports** | ❌ doesn't get past the controllers screen yet: the game requires a **pair** of Joy-Cons (L + R) for player 1, and one phone only emulates one side. See [Next steps](#next-steps). |

Tested on: POCO F6 Pro (HyperOS 3, Android 16) + Nintendo Switch Lite.

## How it works

```
Phone (Android 9+)                                Nintendo Switch
┌───────────────────────────┐   Bluetooth HID    ┌──────────────────┐
│ MotionPad                 │ ◄────────────────► │ looks for "Joy-  │
│  ├ BluetoothHidDevice     │  0x30 report       │ Con (R)" in      │
│  ├ Joy-Con protocol       │  every 15 ms       │ Change Grip/Order│
│  ├ gyroscope 480 Hz       │  0x01 subcommands  │                  │
│  └ rumble                 │  0x10 rumble       │                  │
└───────────────────────────┘                    └──────────────────┘
```

- **Bluetooth:** uses the public `BluetoothHidDevice` API (Android 9+). The app registers an HID descriptor identical to the official controller's and changes the Bluetooth name to `Joy-Con (R)`.
- **Protocol:** answers the Switch's subcommands (0x02 device info, 0x10 SPI read with calibration, 0x03 mode, 0x40 IMU, 0x48 rumble, 0x30 lights...). The SPI memory is emulated in [`SpiFlash.kt`](app/src/main/java/com/motionpad/app/SpiFlash.kt) with "clean" calibration (4096 = 1 G; 13371/936 LSB per °/s).
- **Motion:** [`MotionSensor.kt`](app/src/main/java/com/motionpad/app/MotionSensor.kt) **integrates** the gyroscope (angle rotated) instead of sending only the angular velocity. This way the total rotation received by the Switch is exact even with Bluetooth timing jitter.
  - **Mode 1** (raw data): 3 samples of acceleration + angular velocity per report.
  - **Mode 2** (what the current Switch asks for): the controller integrates the orientation into a **quaternion** and sends it bit-packed. Format ported from the [switch-pico](https://github.com/jyapayne/switch-pico) project (MIT). Sending mode 1 data when the Switch asks for mode 2 makes the game see random rotation.
- **Axes:** phone held upright, screen facing up, top pointing at the TV = Joy-Con pointing at the TV. `Joy-Con X = phone Y`, `Joy-Con Y = phone X`, `Joy-Con Z = −phone Z`. The "Alternate axes" option rotates 180° around the long axis.

### Code

| File | What it does |
|---|---|
| [`JoyConEmulator.kt`](app/src/main/java/com/motionpad/app/JoyConEmulator.kt) | Bluetooth HID, subcommands, sending every 15 ms, IMU encoding (mode 1 and 2) |
| [`SpiFlash.kt`](app/src/main/java/com/motionpad/app/SpiFlash.kt) | Virtual SPI memory (calibration, colors, parameters) |
| [`MotionSensor.kt`](app/src/main/java/com/motionpad/app/MotionSensor.kt) | Gyroscope/accelerometer at the maximum rate, integration |
| [`Rumble.kt`](app/src/main/java/com/motionpad/app/Rumble.kt) | HD Rumble → phone vibrator |
| [`MainActivity.kt`](app/src/main/java/com/motionpad/app/MainActivity.kt), [`PadViews.kt`](app/src/main/java/com/motionpad/app/PadViews.kt) | UI: buttons, analog stick, diagnostic log |
| [`tools/adb-helpers/`](tools/adb-helpers) | Programs that run as `shell` via adb (see below) |

## Build

Requirements: JDK 17+ and Android SDK (platform 35, build-tools 35). Gradle 8.10+.

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
gradle assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Install the APK and open MotionPad. Allow "Nearby devices".
2. On the Switch: **Controllers → Change Grip/Order**.
3. In the app: tap **Pair** and allow the phone to be visible. Go back to the app.
4. If "Pair with Nintendo Switch?" appears, accept and go back to the app.
5. Next times: open the app and tap **Reconnect** (or it reconnects on its own).

**Keep MotionPad open on screen.** Android cancels controller (HID) mode as soon as the app leaves the foreground (e.g. opening the camera). The app re-registers on its own when it comes back.

## Problems found (and solutions)

Documented in the order they showed up, for anyone trying on other devices.

1. **"HID registered=false" after tapping Pair.** The system "make visible" dialog takes the app out of the foreground and `HidDeviceService` unregisters the app. → The app re-registers in `onResume`.
2. **Bluetooth Class of Device (CoD) = phone (0x5A020C).** On HyperOS 3 / Android 16 it comes from the read-only property `bluetooth.device.class_of_device`; `setprop` is denied, `settings put global bluetooth_class_of_device` is denied (no `WRITE_SECURE_SETTINGS`) and `IBluetooth.setBluetoothClass` no longer exists. **But that wasn't the problem:** the Switch finds and connects even with a phone class — it filters by **name**.
3. **HyperOS kept changing the name back** (e.g. when opening Settings → Bluetooth). → The app reapplies `Joy-Con (R)` when it returns to the screen.
4. **Pairing silently cancelled.** The HyperOS Settings app (`BluetoothPairingRequest.onReceive`) cancels any pairing from devices on the **Bluetooth block list** (SharedPreferences `BlackfileForBluetoothDevice`). The Switch was on it. → Remove the Switch from the list in Settings → Bluetooth → blocked devices. The screen can be opened via adb:
   ```bash
   adb shell am start -n com.android.settings/.SubSettings --es :settings:show_fragment com.android.settings.bluetooth.BluetoothBlacklistFragment
   ```
5. **Confirming the pairing automatically.** The adb `shell` user has `BLUETOOTH_PRIVILEGED`, so [`PairHelper`](tools/adb-helpers/PairHelper.java) calls `setPairingConfirmation(true)` as soon as the Switch starts pairing.
6. **Switch repeated subcommand 0x21 (~400×).** The reply to the NFC/IR MCU configuration was wrong. → Reply with just the `0x80` ACK (same as switch-pico).
7. **Game frozen/random motion.** The Switch enables the IMU with `0x40 0x02` (quaternion mode) and the app was sending the mode 1 format. → Implemented mode 2.
8. **Switch Sports asks for L + R.** See below.

### Wireless adb (HyperOS)

On the POCO the "Allow USB debugging?" dialog never appeared. It worked through **Wireless debugging**: open "Pair device with pairing code" on the phone and use adb's mDNS to find the port:

```bash
adb mdns services          # shows _adb-tls-pairing._tcp  IP:PORT
adb pair IP:PORT CODE
adb connect IP:MAIN_SCREEN_PORT
```

### adb helpers

```bash
ANDROID_HOME=/path/to/sdk tools/adb-helpers/build.sh
# confirm pairing for 5 min (the Switch address shows up in logcat when it connects):
adb shell CLASSPATH=/data/local/tmp/pairhelper.dex app_process /system/bin PairHelper XX:XX:XX:XX:XX:XX 300
```

`SetCod.java` was the attempt to change the Bluetooth class; it **doesn't work on Android 16** (method removed) and is kept only for the record.

## Next steps

- [ ] **Joy-Con (L) mode** in the app (D-pad, L/ZL/−/Capture, left-side axes), to use **two phones** as a pair and get past the Switch Sports controllers screen. Alternative: a real Joy-Con (L) + phone as (R).
- [ ] Validate IMU mode 2 in-game and tune sensitivity/axes.
- [ ] Show on the app screen which buttons the Switch is receiving.
- [ ] Better icon and UI.

## Credits

- [dekuNukem/Nintendo_Switch_Reverse_Engineering](https://github.com/dekuNukem/Nintendo_Switch_Reverse_Engineering) — Bluetooth protocol, subcommands, SPI.
- [jyapayne/switch-pico](https://github.com/jyapayne/switch-pico) (MIT) — IMU mode 2 (quaternion) format and the 0x21 reply.
- [ndeadly/MissionControl](https://github.com/ndeadly/MissionControl) — reference for how the Switch filters controllers by name/class.
- HID descriptor and SPI based on joycontrol / nxbt.
