# Changelog

All notable changes to the MobileDataChecker project will be documented in this file.

## [1.0.2] - 2026-10-06

### Added
- **SMS Direct Reply in Notification**:
  - Implemented inline Direct Reply action (`RemoteInput`) on SMS notifications in `SmsReceiver`, enabling quick replies directly from the notification shade.
  - Added `ACTION_REPLY_SMS` intent handler in `SmsReceiver` to dispatch SMS replies via `SmsManager` and display status confirmation notifications.
- **Enhanced Notification UI**:
  - Added large icon (`setLargeIcon`) to incoming SMS notifications using Kotlin KTX `Drawable.toBitmap()`.
  - Added SMS sender address and message content extraction via `Telephony.Sms.Intents.getMessagesFromIntent()`.

### Fixed & Improved
- **Android 10+ Background Activity Start Compliance**:
  - Replaced legacy direct `startActivity()` call in `SmsReceiver` with high-priority system notifications (`sms_notifications_channel`), ensuring compliance with API 29+ background execution restrictions.
- **SMS Permissions & Google Play Policy Hygiene**:
  - Added `Manifest.permission.RECEIVE_SMS` to runtime permission request flow in `MainActivity`.
  - Added `tools:ignore="SmsAndCallLogPolicy"` to `RECEIVE_SMS` in `AndroidManifest.xml` for Google Play policy lint compliance.
- **Android 13+ Notification Safety**:
  - Added `POST_NOTIFICATIONS` runtime permission checks before displaying notifications in `SmsReceiver`.

## [1.0.1] - 2025-02-16

### Added
- **Notification Preference Support**:
  - Added `KEY_NETSTATE_NOTIFICATIONS_ENABLED` to `Constants`.
  - Added `getNotificationsEnabled()` and `setNotificationsEnabled()` methods to `PreferenceManager`.
  - Exposed `notificationsEnabled` StateFlow and `setNotificationsEnabled()` action in `MainViewModel`.
  - Added UI switch toggle in `MainActivity` to enable/disable notifications.
  - Added `enable_notifications_label` string resources in English and German (`strings.xml` and `values-de/strings.xml`).
- **Worker Notification Check**:
  - Updated `MobileDataCheckWorker` to respect the notification preference (`isNotificationEnabled`) when mobile data is active.

### Fixed
- Fixed compilation error: `Unresolved reference 'notificationsEnabled'` in `MainActivity.kt`.
