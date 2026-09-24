#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <WiFi.h>
#include <WebSocketsServer.h>  // "WebSockets" by Markus Sattler

// Service and Characteristic UUIDs
#define SERVICE_UUID        "4fa8c2d0-1cc0-11e8-ba89-0ed5f89f718b"
#define CHARACTERISTIC_SSID "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_PASS "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_STAT "6e400004-b5a3-f393-e0a9-e50e24dcca9e"
#define CHARACTERISTIC_LED  "6e400005-b5a3-f393-e0a9-e50e24dcca9e"

// ESP32-S3 N16R8 DevKit: onboard LED is a WS2812 RGB LED on GPIO 48.
// It needs rgbLedWrite() (a data signal) — digitalWrite() HIGH/LOW does nothing to it.
#ifdef RGB_BUILTIN
#define LED_PIN RGB_BUILTIN
#else
#define LED_PIN 48
#endif

static bool ledOn = false;

static void setLED(bool on) {
    ledOn = on;
    const uint8_t v = on ? 40 : 0;
    rgbLedWrite(LED_PIN, v, v, v);
}

// WebSocket LED control, started once Wi-Fi is up.
// Text frames: on | off | toggle | state  ->  replies/broadcasts {"on":true}
static WebSocketsServer ws(81);
static bool wsStarted = false;

static String ledStateJson() {
    return ledOn ? "{\"on\":true}" : "{\"on\":false}";
}

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
            Serial.print("[ws] command: ");
            Serial.println(cmd);

            if (cmd == "on") {
                setLED(true);
            } else if (cmd == "off") {
                setLED(false);
            } else if (cmd == "toggle") {
                setLED(!ledOn);
            } else if (cmd != "state") {
                ws.sendTXT(client, "{\"error\":\"unknown command\"}");
                break;
            }
            String s = ledStateJson();
            ws.broadcastTXT(s);  // keep every connected client in sync
            break;
        }
        default:
            break;
    }
}

static String wifiSSID = "";
static String wifiPass = "";
static bool newCredentialsReceived = false;

static BLECharacteristic *pStatusCharacteristic = nullptr;

// Callback when SSID is written over BLE
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

// Callback when Password is written over BLE
class PasswordCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) override {
        String value = pCharacteristic->getValue().c_str();
        if (value.length() > 0) {
            wifiPass = value;
            Serial.println("Received Password.");
            newCredentialsReceived = true; // Set flag to trigger connection attempt
        }
    }
};

// Callback when LED command is written over BLE
class LEDCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) override {
        String value = pCharacteristic->getValue().c_str();
        Serial.print("Received LED command: ");
        Serial.println(value);

        if (value == "ON") {
            setLED(true);
        } else if (value == "OFF") {
            setLED(false);
        }
    }
};

class MyServerCallbacks : public BLEServerCallbacks {
    void onDisconnect(BLEServer* pServer) override {
        Serial.println("Client disconnected, restarting advertising");
        pServer->getAdvertising()->start();
    }
};

void initBLEProvisioning(const char* deviceName) {
    BLEDevice::init(deviceName);
    BLEServer *pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());
    BLEService *pService = pServer->createService(SERVICE_UUID);

    // SSID Characteristic (Write Only)
    BLECharacteristic *pSSIDChar = pService->createCharacteristic(
        CHARACTERISTIC_SSID,
        BLECharacteristic::PROPERTY_WRITE
    );
    pSSIDChar->setCallbacks(new SSIDCallbacks());

    // Password Characteristic (Write Only)
    BLECharacteristic *pPassChar = pService->createCharacteristic(
        CHARACTERISTIC_PASS,
        BLECharacteristic::PROPERTY_WRITE
    );
    pPassChar->setCallbacks(new PasswordCallbacks());

    // Status Characteristic (Read / Notify)
    pStatusCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_STAT,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    pStatusCharacteristic->addDescriptor(new BLE2902());
    pStatusCharacteristic->setValue("DISCONNECTED");

    // LED Characteristic (Write Only) — send "ON" or "OFF"
    BLECharacteristic *pLEDChar = pService->createCharacteristic(
        CHARACTERISTIC_LED,
        BLECharacteristic::PROPERTY_WRITE
    );
    pLEDChar->setCallbacks(new LEDCallbacks());

    pService->start();

    // Start BLE Advertising
    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    BLEDevice::startAdvertising();

    Serial.println("BLE Provisioning Service Started. Advertising...");
}

void checkAndConnectWiFi() {
    if (!newCredentialsReceived) return;

    newCredentialsReceived = false;
    Serial.println("Attempting Wi-Fi Connection...");

    pStatusCharacteristic->setValue("CONNECTING");
    pStatusCharacteristic->notify();

    WiFi.disconnect();
    WiFi.begin(wifiSSID.c_str(), wifiPass.c_str());

    int retries = 0;
    while (WiFi.status() != WL_CONNECTED && retries < 20) {
        delay(500);
        Serial.print(".");
        retries++;
    }

    if (WiFi.status() == WL_CONNECTED) {
        Serial.println("\nWiFi Connected!");
        Serial.print("IP Address: ");
        Serial.println(WiFi.localIP());

        if (!wsStarted) {
            ws.begin();
            ws.onEvent(onWsEvent);
            wsStarted = true;
            Serial.println("WebSocket server started on port 81");
        }

        // The app reads the IP from here to open ws://<ip>:81/
        String status = "CONNECTED:" + WiFi.localIP().toString();
        pStatusCharacteristic->setValue(status.c_str());
        pStatusCharacteristic->notify();
    } else {
        Serial.println("\nWiFi Connection Failed.");
        pStatusCharacteristic->setValue("FAILED");
        pStatusCharacteristic->notify();
    }
}

void setup() {
    Serial.begin(115200);
    setLED(false);
    initBLEProvisioning("ESP32_Provisioner");
}

void loop() {
    checkAndConnectWiFi();
    if (wsStarted) ws.loop();
    delay(10);
}