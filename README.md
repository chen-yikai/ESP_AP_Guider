# ESP AP Guider — Thermal Haptics

An ESP32-S3 with an **MLX90640 thermal camera**, **two vibration motors** and a **push button**,
controlled from an **Android app**.

- **See** the live thermal camera (32×24, ~16 FPS) on the phone, with hot objects boxed.
- **Feel** heat: the view is split down the middle and each half drives one motor (left / right).
  The closer or hotter the object, the stronger the buzz.
- **Control** the motors from the phone: *Auto* (the camera decides) or *Manual* (a slider per motor),
  plus the trigger temperature, an enable switch per motor and a test buzz.
- **Input**: presses of the physical button show up on the phone live (state, count, how long it was held).

The phone finds the board over **Bluetooth LE**, sends it your Wi-Fi name and password, then
streams everything over a **WebSocket** on the local network. The board saves the Wi-Fi details,
so after a reboot it rejoins by itself.

```
 ┌──────────── Android app ─────────────┐          ┌──────────── ESP32-S3 ─────────────┐
 │ Guider ── BLE scan "ESP32_Provisioner"│── BLE ──►│ SSID / password / LED / status    │
 │ Dashboard ◄── ws://<ip>:81 frames ────│◄─ Wi-Fi ─│ MLX90640 (I²C) → motors L/R (PWM) │
 │           ──► thr / en / vib / mode ──│─────────►│ push button (GPIO 6)              │
 └───────────────────────────────────────┘          └───────────────────────────────────┘
```

## Wire list

Board: **ESP32-S3 DevKit (N16R8)**, powered over USB-C.

| # | From (part · pin) | To (ESP32-S3) | Signal | Notes |
|---|---|---|---|---|
| 1 | MLX90640 · VIN | 3V3 | Power | 3.3 V only. Do not use 5 V. |
| 2 | MLX90640 · GND | GND | Ground | |
| 3 | MLX90640 · SDA | GPIO 8 | I²C data | Keep under ~10–15 cm; the bus runs at 1 MHz. |
| 4 | MLX90640 · SCL | GPIO 9 | I²C clock | Most breakouts already have pull-ups. |
| 5 | Left motor driver · IN | GPIO 4 | PWM 20 kHz | Left half of the (mirrored) view. |
| 6 | Right motor driver · IN | GPIO 5 | PWM 20 kHz | Right half of the view. |
| 7 | Motor drivers · VCC | 5V (or 3V3) | Motor power | Use 5V to keep motor current off the 3.3 V regulator. |
| 8 | Motor drivers · GND | GND | Ground | Must share GND with the ESP32. |
| 9 | Push button · leg 1 | GPIO 6 | Digital in | Internal pull-up; reads LOW when pressed. |
| 10 | Push button · leg 2 | GND | Ground | |
| — | Onboard RGB LED | GPIO 48 | WS2812 | Built in, no wiring needed. |

> ⚠️ **Never connect a bare motor straight to a GPIO.** A coin motor draws 60–100 mA and kicks back a
> voltage spike when it turns off. Use a vibration-motor module (it has its own transistor and diode),
> or build one switch per motor:
>
> ```
>  5V ──────┬─────────────┐
>         Motor      Diode 1N4148 / 1N5819 (stripe → 5V)
>           ├─────────────┘
>        Drain/Collector
>  GPIO 4/5 ──[1 kΩ]── Gate/Base     (logic-level N-MOSFET e.g. AO3400, or NPN 2N2222 / S8050)
>        Source/Emitter
>  GND ─────┘
> ```

**Pin notes**
- None of the pins above are strapping pins (0, 3, 45, 46), and none are used by the N16R8's octal PSRAM/flash (26–37).
- To use the onboard **BOOT** button instead of an external one, set `BUTTON_PIN 0` in the sketch.
- Every pin is a `#define` near the top of `firmware/thermal_haptics/thermal_haptics.ino`.

### Parts

| Part | Qty | Notes |
|---|---|---|
| ESP32-S3 DevKit N16R8 | 1 | Any ESP32-S3 works; LED pin may differ. |
| MLX90640 breakout (55° or 110°) | 1 | I²C address 0x33. |
| Coin / ERM vibration motor, 3 V | 2 | |
| Vibration-motor module, or MOSFET/NPN + 1 kΩ + flyback diode | 2 | One per motor. |
| Momentary push button | 1 | |

## Project layout

```
app/                                Android app (Kotlin, Jetpack Compose)
  src/main/java/dev/eliaschen/espapguider/
    MainActivity.kt                 permissions, Guider ⇄ Dashboard switch
    ble/BLTService.kt               BLE scan/connect, GATT queue, Wi-Fi provisioning, status
    device/Protocol.kt              WebSocket frame format (ThermalFrame, MotorState)
    device/ThermalSocket.kt         ws://<ip>:81 client, commands, auto-reconnect
    ui/guider/                      "looking for your device" screen
    ui/dashboard/                   Dashboard + one file per card
  src/test/                         protocol unit tests (JVM)
  src/androidTest/                  end-to-end tests against a fake ESP32
firmware/thermal_haptics/
  thermal_haptics.ino               ESP32-S3 sketch
```

## Firmware

1. Arduino IDE, or `arduino-cli`, with **esp32 core 3.x**. Board: **ESP32S3 Dev Module**.
2. Libraries: **Adafruit MLX90640** (with Adafruit BusIO) and **WebSockets** by Markus Sattler.
3. Open `firmware/thermal_haptics/thermal_haptics.ino` and upload. In the Serial Monitor (115200 baud) you should see
   `MLX90640 ready` and `BLE Provisioning Service Started`.

```sh
arduino-cli compile --fqbn esp32:esp32:esp32s3 firmware/thermal_haptics
arduino-cli upload  --fqbn esp32:esp32:esp32s3 -p /dev/cu.usbmodem* firmware/thermal_haptics
```

If the camera isn't found, the board keeps running: BLE, the LED, the motors (manual and test) and the button
all still work, and the app shows "MLX90640 not found".

## App

Open the project in Android Studio and run `app` (minSdk 35). On first launch, grant **Nearby devices**
and **Local network**. The app finds the board by itself. On the dashboard, enter your Wi-Fi details once.
The board saves them and rejoins after a reboot.

| Card | What it does |
|---|---|
| Connection | Wi-Fi/IP, link status and FPS; send or change Wi-Fi credentials |
| Thermal camera | Live image, hot-object boxes, min/center/max; mirror, smoothing, °F |
| Push button | Pressed/released live, presses this session, recent presses with hold time |
| Vibration motors | Auto (camera) / Manual mode, Stop |
| Left / Right motor | Intensity %, peak temp, hot pixels, enable switch (Auto), slider (Manual), test buzz |
| Vibration trigger | 25–150 °C, saved on the board (Auto mode) |
| Onboard LED | On/off over Wi-Fi, or over BLE when Wi-Fi is down |

## Protocol

BLE service `4fa8c2d0-1cc0-11e8-ba89-0ed5f89f718b`

| Characteristic | UUID | Access | Payload |
|---|---|---|---|
| SSID | `6e400002-…` | write | Wi-Fi name |
| Password | `6e400003-…` | write | Wi-Fi password; writing it starts the connection |
| Status | `6e400004-…` | read / notify | `CONNECTING` · `CONNECTED:<ip>` · `FAILED` · `DISCONNECTED` |
| LED | `6e400005-…` | write | `ON` / `OFF` |

WebSocket `ws://<ip>:81/`
- **Board → phone, binary**: `int16` little-endian `[768 + 14]`. Pixels are °C×100, followed by
  duty L/R, hot pixels L/R, peak L/R ×100, mirror, trigger ×100, enabled bits, LED, mode, button pressed,
  button count, camera OK.
- **Phone → board, text**:

| Command | Meaning |
|---|---|
| `on` / `off` / `toggle` | LED |
| `thr:55.5` | trigger temperature, °C (saved) |
| `en:<0\|1>:<0\|1>` | enable/disable the left (0) or right (1) motor in Auto (saved) |
| `test:<0\|1>` | 400 ms full-power buzz |
| `mode:auto` / `mode:manual` | who drives the motors (not saved; the board boots in Auto) |
| `vib:<0\|1>:<0-255>` | Manual-mode duty |

**Safety:** in Manual mode, motors stop when no phone is connected. In Auto mode, they stop if the camera
stops sending frames for 500 ms.

## Tests

```sh
./gradlew :app:testDebugUnitTest            # wire-format unit tests (no device)
./gradlew :app:connectedDebugAndroidTest    # end-to-end on an emulator or phone
```

The end-to-end suite runs the real dashboard and WebSocket client against `FakeEsp32`
(`app/src/androidTest/.../fake/FakeEsp32.kt`). The fake speaks the firmware's protocol and copies its
motor/button logic. It covers the camera stream, hot object → matching motor, the trigger slider, the enable
switch, test buzz, Manual mode, button presses, the LED, reconnecting after a drop, a missing camera, and the
Wi-Fi form. **Not covered:** BLE pairing and the real hardware. Check those by hand with the board. If you change
the protocol, update the firmware, `device/Protocol.kt` and `FakeEsp32` together.
