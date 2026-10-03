package com.thumbshade.app.rules

import kotlinx.serialization.Serializable
import java.util.UUID

/** What kind of notification this is, from Notification.category plus a few heuristics. */
@Serializable
enum class Cat(val label: String) {
    CALL("Call"), MISSED_CALL("Missed call"), MESSAGE("Message"), EMAIL("Email"), SOCIAL("Social"),
    EVENT("Calendar event"), REMINDER("Reminder"), ALARM("Alarm"), PROMO("Promotion"),
    PROGRESS("Progress"), TRANSPORT("Media / transport"), NAVIGATION("Navigation"),
    SYSTEM("System"), SERVICE("Background service"), ERROR("Error"), STATUS("Status"),
    RECOMMENDATION("Recommendation"), WORKOUT("Workout"), STOPWATCH("Stopwatch / timer"), OTHER("Other"),
}

@Serializable
enum class CondType(val label: String) {
    TEXT("Words in the notification"),
    CATEGORY("Category"),
    IMPORTANCE("Importance"),
    FLAG("Kind of notification"),
    TIME("Time of day"),
    LENGTH("Text length"),
    DEVICE("What the phone is doing"),
}

@Serializable
enum class TextField(val label: String) { ANY("Title or text"), TITLE("Title"), TEXT("Text"), APP("App name") }

@Serializable
enum class WordMode(val label: String) { CONTAINS("Contains any of"), WHOLE_WORD("Whole word, any of"), REGEX("Matches pattern") }

@Serializable
enum class Flag(val label: String) {
    GROUP_CHAT("Group chat"),
    FROM_CONTACT("From a saved contact"),
    HAS_PICTURE("Has a picture"),
    HAS_REPLY("Can be replied to"),
    ONGOING("Ongoing / persistent"),
    SILENT("Already silent"),
    REACTION("Message reaction"),
}

@Serializable
enum class DeviceAspect(val label: String) { SCREEN_ON("Screen is on"), IN_CALL("In a call"), RINGER_NORMAL("Ringer on"), RINGER_VIBRATE("Ringer on vibrate"), RINGER_SILENT("Ringer silent"), DND_ON("Do Not Disturb on"), CHARGING("Charging") }

@Serializable
data class Cond(
    val type: CondType = CondType.TEXT,
    val negate: Boolean = false,
    // TEXT
    val words: String = "",
    val field: TextField = TextField.ANY,
    val wordMode: WordMode = WordMode.CONTAINS,
    // CATEGORY
    val categories: Set<Cat> = emptySet(),
    // IMPORTANCE (NotificationManager.IMPORTANCE_* values 0..5)
    val minImportance: Int = 0,
    val maxImportance: Int = 5,
    // FLAG
    val flag: Flag = Flag.GROUP_CHAT,
    // TIME: minutes after midnight; end < start wraps past midnight. days: 1=Mon .. 7=Sun
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    // LENGTH
    val minLength: Int = 0,
    val maxLength: Int = 100_000,
    // DEVICE
    val device: DeviceAspect = DeviceAspect.SCREEN_ON,
)

@Serializable
data class CondGroup(
    val matchAll: Boolean = true,
    val negate: Boolean = false,
    val conditions: List<Cond> = emptyList(),
    val groups: List<CondGroup> = emptyList(),
) {
    val size: Int get() = conditions.size + groups.sumOf { it.size }
}

@Serializable
enum class ActionType(val label: String, val terminal: Boolean = false) {
    MUTE("Silence it"),
    SNOOZE("Snooze it", terminal = true),
    DISMISS("Dismiss it", terminal = true),
    HOLD("Hold it and deliver in a batch", terminal = true),
    FIRST_THEN_QUIET("Let the first through, quiet the rest"),
    REMIND("Remind me later if I don't deal with it"),
    CUSTOM_ALERT("Alert with my own sound / vibration"),
    TORCH("Flash the torch"),
    SET_RINGER("Change the ringer"),
    SET_DND("Change Do Not Disturb"),
    SPEAK("Read it aloud"),
    COPY_CODE("Copy the verification code"),
    PRESS_BUTTON("Press one of its buttons"),
    REPLY("Send a reply"),
    OPEN("Open it"),
    EDGE_LIGHT("Light up the screen in a special style"),
}

@Serializable
enum class Vibe(val label: String, val pattern: LongArray) {
    NONE("No vibration", longArrayOf()),
    SHORT("Short", longArrayOf(0, 120)),
    DOUBLE("Double", longArrayOf(0, 120, 120, 120)),
    LONG("Long", longArrayOf(0, 700)),
    HEARTBEAT("Heartbeat", longArrayOf(0, 90, 120, 90, 500, 90, 120, 90)),
    SOS("SOS", longArrayOf(0, 100, 100, 100, 100, 100, 300, 300, 100, 300, 100, 300, 300, 100, 100, 100, 100, 100)),
}

@Serializable
enum class RingerChoice(val label: String) { NORMAL("Sound"), VIBRATE("Vibrate"), SILENT("Silent") }

@Serializable
enum class BatchMode(val label: String) { TIMES("At times I choose"), INTERVAL("Every N minutes") }

@Serializable
data class RuleAction(
    val type: ActionType = ActionType.MUTE,
    // SNOOZE / REMIND / FIRST_THEN_QUIET window
    val minutes: Int = 30,
    // HOLD
    val batchMode: BatchMode = BatchMode.TIMES,
    val batchTimes: List<Int> = listOf(9 * 60, 17 * 60),
    val intervalMinutes: Int = 60,
    // FIRST_THEN_QUIET: what to do with the rest
    val quietDismiss: Boolean = false,
    // CUSTOM_ALERT
    val soundUri: String = "",
    val vibe: Vibe = Vibe.DOUBLE,
    // TORCH
    val flashes: Int = 3,
    // SET_RINGER / SET_DND
    val ringer: RingerChoice = RingerChoice.VIBRATE,
    val dndOn: Boolean = true,
    // PRESS_BUTTON label / REPLY text / SPEAK template
    val text: String = "",
    // EDGE_LIGHT
    val edgeStyle: String = "GLOW",
    val color: Long = 0xFFFF5252,
)

@Serializable
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New rule",
    val enabled: Boolean = true,
    val apps: Set<String> = emptySet(),
    val excludeApps: Set<String> = emptySet(),
    val root: CondGroup = CondGroup(),
    val actions: List<RuleAction> = listOf(RuleAction()),
    val stopAfterMatch: Boolean = false,
) {
    val scopeLabel: String
        get() = when {
            apps.isEmpty() -> "Any app"
            apps.size == 1 -> "1 app"
            else -> "${apps.size} apps"
        }
}

/** The facts about one notification that rules can see. Plain data so it is unit-testable. */
data class NotifFacts(
    val key: String,
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    val category: Cat,
    val importance: Int,
    val isGroupChat: Boolean,
    val fromContact: Boolean,
    val hasPicture: Boolean,
    val hasReply: Boolean,
    val ongoing: Boolean,
    val silent: Boolean,
    val isReaction: Boolean,
)

/** What the phone is doing right now. */
data class DeviceSnapshot(
    val screenOn: Boolean = true,
    val inCall: Boolean = false,
    val ringerMode: Int = 2, // AudioManager.RINGER_MODE_NORMAL
    val dndOn: Boolean = false,
    val charging: Boolean = false,
    /** 1 = Monday .. 7 = Sunday */
    val dayOfWeek: Int = 1,
    val minuteOfDay: Int = 0,
)
