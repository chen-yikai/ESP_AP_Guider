/*
 * ESP32-S3 (N16R8) — BLE Wi-Fi provisioning + LED + MLX90640 thermal haptics
 *
 * Libraries (Arduino Library Manager):
 *   - Adafruit MLX90640   (+ Adafruit BusIO)
 *   - WebSockets          by Markus Sattler (Links2004)
 *
 * Wiring:
 *   MLX90640  VIN -> 3V3, GND -> GND, SDA -> GPIO 8, SCL -> GPIO 9  (short wires, 1 MHz I2C)
 *   Motors    GPIO 4 -> left motor driver IN, GPIO 5 -> right motor driver IN
 *             Never drive a bare motor from a GPIO: use a motor module or a MOSFET/NPN + flyback diode.
 *   Button    GPIO 6 -> push button -> GND (internal pull-up). Set BUTTON_PIN 0 to use the BOOT button.
 *   See README.md for the full wire list.
 *
 * Flow:
 *   1. Phone connects over BLE, writes SSID + password. Credentials are saved in flash,
 *      so the board reconnects to Wi-Fi by itself after a reboot.
 *   2. Status characteristic reports "CONNECTED:<ip>"; the app opens ws://<ip>:81/.
 *   3. The WebSocket streams thermal frames (binary) and accepts commands (text).
 *
 * WebSocket protocol (port 81)
 *   -> binary frame: int16[768 + 14]  (sent at sensor rate; ~10 Hz with zeroed pixels if no camera)
 *        [0..767]  temperatures, °C x 100, raw sensor order (row-major, 32 x 24)
 *        [768+0,1] motor duty L, R (0-255)
 *        [768+2,3] hot pixels L, R
 *        [768+4,5] peak temp L, R (°C x 100)
 *        [768+6]   MIRROR_VIEW (1 = left motor = left of the mirrored image)
 *        [768+7]   trigger temp (°C x 100)
 *        [768+8]   enabled bits (bit0 = left, bit1 = right)
 *        [768+9]   LED on (0/1)
 *        [768+10]  motor mode (0 = auto: camera drives motors, 1 = manual: phone drives motors)
 *        [768+11]  button pressed (0/1)
 *        [768+12]  button press count (wraps)
 *        [768+13]  camera OK (0/1)
 *   -> text: {"on":true|false}            LED state (sent on connect and on change)
 *   <- text: on | off | toggle | state    LED
 *            thr:55.5                     set vibration trigger °C (saved)
 *            en:0:1                       enable(1)/disable(0) motor 0 = left, 1 = right (saved)
 *            test:0                       buzz one motor for 400 ms
 *            mode:auto | mode:manual      who drives the motors (not saved; boots in auto)
 *            vib:0:180                    manual mode: set motor 0/1 duty 0-255
 *                                         (manual duties drop to 0 if every client disconnects)
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <WiFi.h>
#include <Wire.h>
#include <Preferences.h>
#include <WebSocketsServer.h>  // "WebSockets" by Markus Sattler
#include <Adafruit_MLX90640.h>

// ---------- BLE ----------
#define SERVICE_UUID        "4fa8c2d0-1cc0-11e8-ba89-0ed5f89f718b"
#define CHARACTERISTIC_SSID "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_PASS "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_STAT "6e400004-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_LED  "6e400005-b5a3-f393-e0a9-e50e24dcca9e"

// ---------- Hardware config ----------
// ESP32-S3 N16R8 DevKit: onboard LED is a WS2812 RGB LED on GPIO 48 (needs rgbLedWrite()).
#ifdef RGB_BUILTIN
#define LED_PIN RGB_BUILTIN
#else
#define LED_PIN 48
#endif

#define I2C_SDA 8
#define I2C_SCL 9
#define I2C_FREQ 1000000               // 1 MHz needed for 16 Hz
#define SENSOR_RATE MLX90640_16_HZ     // try MLX90640_8_HZ if you get read errors

#define MOTOR_L_PIN 4
#define MOTOR_R_PIN 5
#define MIRROR_VIEW true               // left motor = left of the mirrored image
#define HOT_C_DEFAULT 50.0f
#define HOT_C_MIN 25.0f
#define HOT_C_MAX 150.0f
#define HOT_HYSTERESIS_C 2.0f          // stop 2 °C below the trigger
#define NEAR_PIXELS 96                 // hot pixels in one half that count as "right in front"
#define NEAR_TEMP_SPAN_C 70.0f         // peak this far above the trigger counts as "right in front"
#define MOTOR_MIN_DUTY 90              // lowest duty that reliably spins the motor
#define MOTOR_PWM_FREQ 20000
#define MOTOR_TIMEOUT_MS 500           // stop motors if the sensor stops delivering frames
#define MOTOR_TEST_MS 400

#define BUTTON_PIN 6                   // push button to GND; 0 = onboard BOOT button
#define BUTTON_DEBOUNCE_MS 30

#define W 32
#define H 24
#define N (W * H)
#define EXTRA 14

// ---------- Shared state ----------
static Preferences prefs;
static Adafruit_MLX90640 mlx;
static bool mlxReady = false;

static int16_t sharedFrame[N];                     // °C x 100
static volatile uint32_t frameSeq = 0;
static SemaphoreHandle_t frameMutex;
static volatile uint8_t motorLevel[2] = {0, 0};
static volatile int16_t sideHot[2] = {0, 0};
static volatile int16_t sidePeak[2] = {0, 0};
static volatile float hotC = HOT_C_DEFAULT;
static volatile bool motorEnabled[2] = {true, true};
static volatile uint32_t motorTestUntil[2] = {0, 0}; // millis(); manual buzz from the dashboard
static volatile bool manualMode = false;            // phone drives the motors directly
static volatile uint8_t manualDuty[2] = {0, 0};

static volatile bool buttonPressed = false;
static volatile uint16_t buttonCount = 0;

static volatile bool ledOn = false;
static volatile bool ledDirty = false;  // broadcast from loop(); BLE callbacks run on another task

static WebSocketsServer ws(81);
static bool wsStarted = false;

static String wifiSSID = "";
static String wifiPass = "";
static volatile bool newCredentialsReceived = false;
static BLECharacteristic *pStatusCharacteristic = nullptr;

// ---------------- LED ----------------
static String ledStateJson() {
    return ledOn ? "{\"on\":true}" : "{\"on\":false}";
}

static void setLED(bool on) {
    ledOn = on;
    const uint8_t v = on ? 40 : 0;
    rgbLedWrite(LED_PIN, v, v, v);
    ledDirty = true;
}

// ---------------- Vibration motors ----------------
static const uint8_t motorPins[2] = {MOTOR_L_PIN, MOTOR_R_PIN};

static void motorsBegin() {
    for (int s = 0; s < 2; s++) ledcAttach(motorPins[s], MOTOR_PWM_FREQ, 8);
}

static void motorWrite(int side, uint8_t duty) {
    ledcWrite(motorPins[side], duty);
    motorLevel[side] = duty;
}

static void motorsOff() {
    motorWrite(0, 0);
    motorWrite(1, 0);
}

static bool motorTesting(int side) {
    return (int32_t)(motorTestUntil[side] - millis()) > 0;
}

// Split the frame down the middle; vibrate the side that sees something > hotC,
// stronger the closer the hot object appears (bigger and/or hotter).
static void updateMotors(const float *frame) {
    static bool active[2] = {false, false};
    static float level[2] = {0, 0};
    const float trigger = hotC, release = trigger - HOT_HYSTERESIS_C;
    int hot[2] = {0, 0};
    float peak[2] = {-273, -273};

    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            float t = frame[y * W + x];
            if (isnan(t)) continue;
            bool rawLeft = x < W / 2;
            int side = (rawLeft != MIRROR_VIEW) ? 0 : 1;
            if (t > peak[side]) peak[side] = t;
            if (t > (active[side] ? release : trigger)) hot[side]++;
        }
    }

    for (int s = 0; s < 2; s++) {
        sideHot[s] = hot[s];
        sidePeak[s] = (int16_t)(peak[s] * 100.0f);
        active[s] = hot[s] > 0;
        if (manualMode || motorTesting(s)) continue;   // loop() owns the motor right now
        if (!active[s] || !motorEnabled[s]) {
            level[s] = 0;
            motorWrite(s, 0);
            continue;
        }
        float bySize = sqrtf((float)hot[s] / NEAR_PIXELS);
        float byTemp = (peak[s] - trigger) / NEAR_TEMP_SPAN_C;
        float closeness = constrain(max(bySize, byTemp), 0.0f, 1.0f);
        float target = MOTOR_MIN_DUTY + (255 - MOTOR_MIN_DUTY) * closeness;
        level[s] = level[s] == 0 ? target : level[s] * 0.5f + target * 0.5f;
        motorWrite(s, (uint8_t)level[s]);
    }
}

static void startMotorTest(int side) {
    motorTestUntil[side] = millis() + MOTOR_TEST_MS;
    motorWrite(side, 255);
    Serial.printf("Motor test: %s\n", side ? "right" : "left");
}

static void setManualMode(bool manual) {
    if (manualMode == manual) return;
    manualDuty[0] = manualDuty[1] = 0;
    manualMode = manual;
    for (int s = 0; s < 2; s++) if (!motorTesting(s)) motorWrite(s, 0);
    Serial.printf("Motor mode: %s\n", manual ? "manual" : "auto");
}

// Ends test buzzes and applies manual duties (runs in loop()).
static void serviceMotors() {
    static bool wasTesting[2] = {false, false};
    // Fail-safe: nobody is watching, so don't keep buzzing on a stale manual command.
    if (manualMode && (!wsStarted || ws.connectedClients() == 0)) manualDuty[0] = manualDuty[1] = 0;
    for (int s = 0; s < 2; s++) {
        bool t = motorTesting(s);
        if (t) {
            wasTesting[s] = true;
            continue;
        }
        if (manualMode) {
            if (motorLevel[s] != manualDuty[s]) motorWrite(s, manualDuty[s]);
        } else if (wasTesting[s]) {
            motorWrite(s, 0);  // the next sensor frame takes over
        }
        wasTesting[s] = false;
    }
}

// ---------------- Button ----------------
static void serviceButton() {
    static bool stable = false, lastRaw = false;
    static uint32_t changedAt = 0;
    bool raw = digitalRead(BUTTON_PIN) == LOW;   // active low
    if (raw != lastRaw) {
        lastRaw = raw;
        changedAt = millis();
    }
    if (raw != stable && millis() - changedAt >= BUTTON_DEBOUNCE_MS) {
        stable = raw;
        buttonPressed = raw;
        if (raw) buttonCount++;
        Serial.printf("Button %s (count %u)\n", raw ? "pressed" : "released", buttonCount);
    }
}

// ---------------- Sensor task (core 0) ----------------
static void sensorTask(void *) {
    static float frame[N];
    static int16_t local[N];
    uint32_t count = 0, errors = 0, t0 = millis(), lastGood = millis();

    for (;;) {
        if (mlx.getFrame(frame) == 0) {
            lastGood = millis();
            updateMotors(frame);
            for (int i = 0; i < N; i++) {
                local[i] = isnan(frame[i]) ? local[i] : (int16_t)(frame[i] * 100.0f);
            }
            xSemaphoreTake(frameMutex, portMAX_DELAY);
            memcpy(sharedFrame, local, sizeof(local));
            frameSeq++;
            xSemaphoreGive(frameMutex);
            count++;
        } else {
            errors++;
            if (!manualMode && millis() - lastGood > MOTOR_TIMEOUT_MS) {
                for (int s = 0; s < 2; s++) if (!motorTesting(s)) motorWrite(s, 0);
            }
            vTaskDelay(pdMS_TO_TICKS(5));
        }

        uint32_t now = millis();
        if (now - t0 >= 5000) {
            Serial.printf("Sensor: %.1f FPS, %lu errors\n", count * 1000.0f / (now - t0), errors);
            count = 0; errors = 0; t0 = now;
        }
        vTaskDelay(1);
    }
}

// ---------------- Settings ----------------
static void setHotC(float c) {
    c = constrain(c, HOT_C_MIN, HOT_C_MAX);
    if (c == hotC) return;
    hotC = c;
    prefs.putFloat("hotC", c);
    Serial.printf("Vibrate trigger set to %.1f C\n", c);
}

static void setMotorEnabled(int side, bool en) {
    if (motorEnabled[side] == en) return;
    motorEnabled[side] = en;
    if (!en && !motorTesting(side)) motorWrite(side, 0);
    prefs.putBool(side ? "enR" : "enL", en);
    Serial.printf("%s motor %s\n", side ? "Right" : "Left", en ? "enabled" : "disabled");
}

// ---------------- WebSocket ----------------
static void onWsEvent(uint8_t client, WStype_t type, uint8_t *payload, size_t len) {
    switch (type) {
        case WStype_CONNECTED: {
            Serial.printf("[ws] #%u connected from %s\n", client, ws.remoteIP(client).toString().c_str());
            String s = ledStateJson();
            ws.sendTXT(client, s);
            break;
        }
        case WStype_DISCONNECTED:
            Serial.printf("[ws] #%u disconnected\n", client);
            break;
        case WStype_TEXT: {
            String cmd = String((char *)payload, len);
            cmd.trim();

            if (cmd == "on") setLED(true);
            else if (cmd == "off") setLED(false);
            else if (cmd == "toggle") setLED(!ledOn);
            else if (cmd == "state") ledDirty = true;
            else if (cmd.startsWith("thr:")) setHotC(cmd.substring(4).toFloat());
            else if (cmd.length() == 6 && cmd.startsWith("en:") && cmd[4] == ':') {
                int side = cmd[3] - '0';
                if (side == 0 || side == 1) setMotorEnabled(side, cmd[5] == '1');
            } else if (cmd.length() == 6 && cmd.startsWith("test:")) {
                int side = cmd[5] - '0';
                if (side == 0 || side == 1) startMotorTest(side);
            } else if (cmd == "mode:auto" || cmd == "mode:manual") {
                setManualMode(cmd == "mode:manual");
            } else if (cmd.startsWith("vib:") && cmd.length() >= 7 && cmd[5] == ':') {
                int side = cmd[4] - '0';
                if ((side == 0 || side == 1) && manualMode) manualDuty[side] = constrain(cmd.substring(6).toInt(), 0, 255);
            } else {
                ws.sendTXT(client, "{\"error\":\"unknown command\"}");
            }
            break;
        }
        default:
            break;
    }
}

static void pushFrameIfNew() {
    static uint32_t lastSent = 0;
    static uint32_t lastSentAt = 0;
    static int16_t copy[N + EXTRA];
    if (ws.connectedClients() == 0) return;
    if (mlxReady) {
        if (frameSeq == lastSent) return;
        xSemaphoreTake(frameMutex, portMAX_DELAY);
        memcpy(copy, sharedFrame, sizeof(sharedFrame));
        lastSent = frameSeq;
        xSemaphoreGive(frameMutex);
    } else {
        // No camera: still stream motor/button/LED state so the phone controls keep working.
        if (millis() - lastSentAt < 100) return;
        memset(copy, 0, sizeof(sharedFrame));
    }
    lastSentAt = millis();
    copy[N + 0] = motorLevel[0];
    copy[N + 1] = motorLevel[1];
    copy[N + 2] = sideHot[0];
    copy[N + 3] = sideHot[1];
    copy[N + 4] = sidePeak[0];
    copy[N + 5] = sidePeak[1];
    copy[N + 6] = MIRROR_VIEW ? 1 : 0;
    copy[N + 7] = (int16_t)(hotC * 100.0f);
    copy[N + 8] = (motorEnabled[0] ? 1 : 0) | (motorEnabled[1] ? 2 : 0);
    copy[N + 9] = ledOn ? 1 : 0;
    copy[N + 10] = manualMode ? 1 : 0;
    copy[N + 11] = buttonPressed ? 1 : 0;
    copy[N + 12] = (int16_t)buttonCount;
    copy[N + 13] = mlxReady ? 1 : 0;

    ws.broadcastBIN((uint8_t *)copy, sizeof(copy));
}

// ---------------- BLE provisioning ----------------
class SSIDCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) override {
        String value = pCharacteristic->getValue().c_str();
        if (value.length() > 0) {
            wifiSSID = value;
            Serial.print("Received SSID: ");
            Serial.println(wifiSSID);
        }
    }
};

class PasswordCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) override {
        // Empty password is allowed (open network)
        wifiPass = pCharacteristic->getValue().c_str();
        Serial.println("Received Password.");
        newCredentialsReceived = true;
    }
};

class LEDCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) override {
        String value = pCharacteristic->getValue().c_str();
        Serial.print("Received LED command: ");
        Serial.println(value);
        if (value == "ON") setLED(true);
        else if (value == "OFF") setLED(false);
    }
};

class MyServerCallbacks : public BLEServerCallbacks {
    void onDisconnect(BLEServer *pServer) override {
        Serial.println("Client disconnected, restarting advertising");
        pServer->getAdvertising()->start();
    }
};

static void setStatus(const String &s) {
    if (!pStatusCharacteristic) return;
    pStatusCharacteristic->setValue(s.c_str());
    pStatusCharacteristic->notify();
}

void initBLEProvisioning(const char *deviceName) {
    BLEDevice::init(deviceName);
    BLEServer *pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());
    BLEService *pService = pServer->createService(SERVICE_UUID);

    pService->createCharacteristic(CHARACTERISTIC_SSID, BLECharacteristic::PROPERTY_WRITE)
        ->setCallbacks(new SSIDCallbacks());
    pService->createCharacteristic(CHARACTERISTIC_PASS, BLECharacteristic::PROPERTY_WRITE)
        ->setCallbacks(new PasswordCallbacks());

    pStatusCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_STAT,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    pStatusCharacteristic->addDescriptor(new BLE2902());
    pStatusCharacteristic->setValue("DISCONNECTED");

    pService->createCharacteristic(CHARACTERISTIC_LED, BLECharacteristic::PROPERTY_WRITE)
        ->setCallbacks(new LEDCallbacks());

    pService->start();

    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    BLEDevice::startAdvertising();

    Serial.println("BLE Provisioning Service Started. Advertising...");
}

// ---------------- Wi-Fi ----------------
static bool connectWiFi(const String &ssid, const String &pass) {
    setStatus("CONNECTING");
    WiFi.disconnect();
    WiFi.mode(WIFI_STA);
    WiFi.setSleep(false);  // power save adds big latency spikes to the frame stream
    WiFi.begin(ssid.c_str(), pass.c_str());

    int retries = 0;
    while (WiFi.status() != WL_CONNECTED && retries < 30) {
        delay(500);
        Serial.print(".");
        retries++;
    }

    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("\nWiFi Connection Failed.");
        setStatus("FAILED");
        return false;
    }

    Serial.print("\nWiFi Connected! IP: ");
    Serial.println(WiFi.localIP());
    if (!wsStarted) {
        ws.begin();
        ws.onEvent(onWsEvent);
        wsStarted = true;
        Serial.println("WebSocket server started on port 81");
    }
    setStatus("CONNECTED:" + WiFi.localIP().toString());  // app opens ws://<ip>:81/
    return true;
}

static void checkAndConnectWiFi() {
    if (!newCredentialsReceived) return;
    newCredentialsReceived = false;
    Serial.println("Attempting Wi-Fi Connection...");
    if (connectWiFi(wifiSSID, wifiPass)) {
        prefs.putString("ssid", wifiSSID);
        prefs.putString("pass", wifiPass);
    }
}

// Keep the status characteristic honest if the AP drops us.
static void watchWiFi() {
    static bool wasConnected = false;
    static uint32_t lastCheck = 0;
    if (millis() - lastCheck < 1000) return;
    lastCheck = millis();
    bool c = WiFi.status() == WL_CONNECTED;
    if (c != wasConnected && wsStarted) {
        setStatus(c ? "CONNECTED:" + WiFi.localIP().toString() : String("DISCONNECTED"));
    }
    wasConnected = c;
}

// ---------------- Setup / loop ----------------
void setup() {
    Serial.begin(115200);
    setLED(false);

    motorsBegin();
    motorsOff();
    pinMode(BUTTON_PIN, INPUT_PULLUP);

    prefs.begin("haptics", false);
    hotC = constrain(prefs.getFloat("hotC", HOT_C_DEFAULT), HOT_C_MIN, HOT_C_MAX);
    motorEnabled[0] = prefs.getBool("enL", true);
    motorEnabled[1] = prefs.getBool("enR", true);

    frameMutex = xSemaphoreCreateMutex();
    Wire.begin(I2C_SDA, I2C_SCL);
    Wire.setClock(I2C_FREQ);
    if (mlx.begin(MLX90640_I2CADDR_DEFAULT, &Wire)) {
        mlx.setMode(MLX90640_CHESS);
        mlx.setResolution(MLX90640_ADC_18BIT);
        mlx.setRefreshRate(SENSOR_RATE);
        mlxReady = true;
        xTaskCreatePinnedToCore(sensorTask, "mlx", 8192, nullptr, 2, nullptr, 0);
        Serial.println("MLX90640 ready");
    } else {
        // Keep BLE/LED working without the camera.
        Serial.println("MLX90640 not found! Check wiring (SDA/SCL/3V3/GND).");
    }

    initBLEProvisioning("ESP32_Provisioner");

    // Reconnect with saved credentials, if any.
    String ssid = prefs.getString("ssid", "");
    if (ssid.length() > 0) {
        Serial.printf("Using saved Wi-Fi \"%s\"\n", ssid.c_str());
        connectWiFi(ssid, prefs.getString("pass", ""));
    }
}

void loop() {
    checkAndConnectWiFi();
    serviceMotors();
    serviceButton();
    watchWiFi();
    if (wsStarted) {
        ws.loop();
        if (ledDirty) {
            ledDirty = false;
            String s = ledStateJson();
            ws.broadcastTXT(s);
        }
        pushFrameIfNew();
    }
    delay(1);
}
