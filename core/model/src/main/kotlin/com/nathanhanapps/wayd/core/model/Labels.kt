package com.nathanhanapps.wayd.core.model

/** Descriptive roles, independent of risk severity and policy. UNKNOWN never means safe. */
enum class ConnectionRole { FUNCTIONAL, LOGIN, LOCATION, PUSH, ANALYTICS, DIAGNOSTICS, ADS, MEDIA, CONTENT, UPDATE, CARRIER, SYSTEM, UNKNOWN }

/** Where a local role came from. NAME_PATTERN is a hostname-word heuristic and is shown as weaker than a curated entry. */
enum class RoleSource { CURATED, TRACKER_LIST, NAME_PATTERN, NONE }

/** Advice only: applying a block still requires the existing target/identity/consent checks. */
enum class BlockAdvice { TRY_TEMPORARY, MAY_AFFECT_FUNCTION, INSUFFICIENT }

/** Deterministic facts behind algorithm advice. They explain a suggestion; they are not severity. */
enum class AdviceReason { TRACKING_ROLE, FEATURE_ROLE, PLATFORM_ROLE, UNKNOWN_ROLE, NO_HOST_EVIDENCE, NO_BYTES, UNATTRIBUTED, SCREEN_OFF, UPLOAD_HEAVY, ALREADY_BLOCKED, NAME_ONLY }

enum class PermissionMeaning {
    LOCATION, CONTACTS, ACCOUNTS, PHONE, CALLS, CALL_LOG, SMS, CALENDAR, CAMERA, MICROPHONE, MEDIA, MEDIA_LOCATION, SENSORS, ACTIVITY_RECOGNITION, CLIPBOARD,
    BLUETOOTH, WIFI, NFC, NOTIFICATIONS, NOTIFICATION_ACCESS, PUSH, ACCESSIBILITY, OVERLAY, APP_LIST, USAGE_STATS, AD_ID,
    INSTALL_APPS, REMOVE_APPS, REBOOT, BOOT, NETWORK, STORAGE, UNKNOWN,
}

/**
 * Rule-computed attention for one destination of one app, combining the connection role, related permissions/components from the
 * APK, permission use at the same time and rule findings (D-063). Deterministic; the model never sets it. Separate from finding
 * [Severity]: it orders what to look at first and never claims a leak.
 */
enum class Attention { NONE, NOTE, REVIEW, ACT }

/** How a connection host and an APK fact are related. None of these proves the fact caused the connection. */
enum class RelationStrength {
    /** AppOps recorded the permission in use while records of this host were open, and it fits the host's role. */
    USED_TOGETHER,
    /** AppOps recorded the permission in use while records of this host were open. */
    SAME_TIME,
    /** The app declares a permission that fits the host's role. */
    DECLARED,
    /** A component or SDK shares words or its company with the host. */
    NAME_MATCH,
}

/** What the user could do next; each is only a pointer, nothing is applied. AppOps and Thanox are external apps. */
enum class SuggestionKind { BLOCK_TRIAL, APPOPS_RESTRICT, THANOX_REDIRECT, THANOX_DISABLE }
