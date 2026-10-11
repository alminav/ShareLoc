# Permissions & Security

## 🔒 Permissions Used

**ShareLoc** requires specific system permissions to monitor telemetry and send alerts:

| Permission | Purpose |
| :--- | :--- |
| `INTERNET` | Communication with remote REST API backend. |
| `ACCESS_NETWORK_STATE` | Inspection of active network interface (Wi-Fi vs Cellular). |
| `READ_PHONE_STATE` | Querying cellular data status via `TelephonyManager`. |
| `ACCESS_FINE_LOCATION` | High-accuracy GPS location retrieval. |
| `ACCESS_COARSE_LOCATION` | Network-based location fallback. |
| `ACCESS_BACKGROUND_LOCATION` | Background location tracking during WorkManager execution. |
| `SEND_SMS` | Sending SMS alert messages from background worker or UI dialog. |
| `POST_NOTIFICATIONS` | Showing system alerts on Android 13+ (API 33+). |

---

## 🛡️ Security & Privacy Considerations

* **SMS Sending**: The app explicitly requests `SEND_SMS` permission at runtime and presents user-controlled confirmation dialogs.
* **Background Location Rationale**: An explicit rationale dialog is presented prior to requesting `ACCESS_BACKGROUND_LOCATION` permission in accordance with Google Play developer policies.
* **SharedPreferences**: Preference data (target phone number and check intervals) are stored locally in private application preferences (`checker_prefs`).
