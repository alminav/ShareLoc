# Setup & Installation

## 📋 Prerequisites

Before building **ShareLoc**, ensure you have:
* **Android Studio**: Ladybug (2024.2.1) or newer.
* **JDK**: Version 17.
* **Target Android SDK**: API 35/37 (Minimum API level 24 - Android 7.0).
* **Physical Device or Emulator**: Physical device recommended for testing `SEND_SMS`, cellular data detection, and GPS location services.

---

## 🔨 Building the Project

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/alminav/ShareLoc.git
   cd ShareLoc
   ```

2. **Open in Android Studio**:
   - Launch Android Studio.
   - Select **Open** and navigate to the cloned project folder.
   - Allow Gradle to sync dependencies.

3. **Compile and Run**:
   - Connect an Android device with USB Debugging enabled.
   - Click **Run 'app'** (`Shift + F10`) or run from terminal:
     ```bash
     ./gradlew assembleDebug
     ```

---

## ⚙️ Initial App Configuration

1. Open the app on your device.
2. Grant requested runtime permissions (SMS, Location, Phone State, Notifications).
3. Click the **Settings** icon (top right) to set:
   - **SMS Phone Number**: Target phone number for alerts.
   - **Check Interval**: Worker interval in minutes (minimum 15 minutes).
