package com.thumbshade.app.rules

data class Template(val title: String, val description: String, val rule: Rule)

object Templates {
    private const val WEEKDAYS_MIN = 9 * 60
    private const val WEEKDAYS_MAX = 17 * 60
    private val weekdays = setOf(1, 2, 3, 4, 5)
    private val social = setOf(Cat.SOCIAL)
    private val messages = setOf(Cat.MESSAGE)

    private fun textCond(words: String, mode: WordMode = WordMode.CONTAINS, field: TextField = TextField.ANY) =
        Cond(type = CondType.TEXT, words = words, wordMode = mode, field = field)

    private fun cat(vararg c: Cat) = Cond(type = CondType.CATEGORY, categories = c.toSet())
    private fun time(start: Int, end: Int, days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7)) =
        Cond(type = CondType.TIME, startMinute = start, endMinute = end, days = days)

    private fun rule(name: String, conds: List<Cond>, vararg actions: RuleAction, matchAll: Boolean = true) =
        Rule(name = name, root = CondGroup(matchAll = matchAll, conditions = conds), actions = actions.toList())

    val all: List<Template>
        get() = listOf(
            Template(
                "Copy verification codes", "Puts one-time codes on the clipboard as they arrive.",
                rule("Copy verification codes", listOf(textCond("code|otp|verification|passcode|pin")), RuleAction(ActionType.COPY_CODE)),
            ),
            Template(
                "Silence message reactions", "No sound for \"liked your message\" and emoji reactions.",
                rule("Silence message reactions", listOf(Cond(type = CondType.FLAG, flag = Flag.REACTION)), RuleAction(ActionType.MUTE)),
            ),
            Template(
                "Hold email until twice a day", "Email arrives in two batches, at 9:00 and 17:00.",
                rule("Hold email until twice a day", listOf(cat(Cat.EMAIL)), RuleAction(ActionType.HOLD, batchTimes = listOf(9 * 60, 17 * 60))),
            ),
            Template(
                "Collect social into an hourly batch", "Social notifications are delivered once an hour.",
                rule("Collect social into an hourly batch", listOf(cat(Cat.SOCIAL)), RuleAction(ActionType.HOLD, batchMode = BatchMode.INTERVAL, intervalMinutes = 60)),
            ),
            Template(
                "Silence messages overnight", "Messages stay quiet from 22:00 to 07:00.",
                rule("Silence messages overnight", listOf(cat(Cat.MESSAGE), time(22 * 60, 7 * 60)), RuleAction(ActionType.MUTE)),
            ),
            Template(
                "Silence social during work hours", "Weekdays 9:00 to 17:00.",
                rule("Silence social during work hours", listOf(Cond(type = CondType.CATEGORY, categories = social), time(WEEKDAYS_MIN, WEEKDAYS_MAX, weekdays)), RuleAction(ActionType.MUTE)),
            ),
            Template(
                "Silence notifications during calls", "Nothing makes a sound while you're on a call.",
                rule("Silence notifications during calls", listOf(Cond(type = CondType.DEVICE, device = DeviceAspect.IN_CALL), Cond(type = CondType.CATEGORY, categories = setOf(Cat.CALL), negate = true)), RuleAction(ActionType.MUTE)),
            ),
            Template(
                "Silence group chats", "Group conversations never make a sound.",
                rule("Silence group chats", listOf(Cond(type = CondType.FLAG, flag = Flag.GROUP_CHAT)), RuleAction(ActionType.MUTE)),
            ),
            Template(
                "Busy chat: first one only", "The first message interrupts; the rest stay quiet for 10 minutes.",
                rule("Busy chat: first one only", listOf(cat(Cat.MESSAGE)), RuleAction(ActionType.FIRST_THEN_QUIET, minutes = 10)),
            ),
            Template(
                "Dismiss promotions", "Sales, deals and offers are cleared as they arrive.",
                rule("Dismiss promotions", listOf(cat(Cat.PROMO), textCond("sale|discount|deal|% off|offer|coupon|promo")), RuleAction(ActionType.DISMISS), matchAll = false),
            ),
            Template(
                "Read messages aloud while charging", "Hands-free on the desk or in the car.",
                rule("Read messages aloud while charging", listOf(cat(Cat.MESSAGE), Cond(type = CondType.DEVICE, device = DeviceAspect.CHARGING)), RuleAction(ActionType.SPEAK)),
            ),
            Template(
                "Flash the torch for calls on silent", "See calls when the ringer is off.",
                rule("Flash the torch for calls on silent", listOf(cat(Cat.CALL), Cond(type = CondType.DEVICE, device = DeviceAspect.RINGER_NORMAL, negate = true)), RuleAction(ActionType.TORCH, flashes = 5)),
            ),
            Template(
                "Remind me about missed calls", "A reminder 30 minutes later if it's still there.",
                rule("Remind me about missed calls", listOf(cat(Cat.MISSED_CALL)), RuleAction(ActionType.REMIND, minutes = 30)),
            ),
            Template(
                "Urgent words break through", "Anything saying \"urgent\" or \"emergency\" alerts loudly.",
                rule("Urgent words break through", listOf(textCond("urgent|emergency|asap|911", WordMode.WHOLE_WORD)), RuleAction(ActionType.CUSTOM_ALERT, vibe = Vibe.SOS), RuleAction(ActionType.EDGE_LIGHT, edgeStyle = "HEARTBEAT", color = 0xFFFF1744)),
            ),
            Template(
                "Snooze shopping and delivery updates", "Out of the way for an hour.",
                rule("Snooze shopping and delivery updates", listOf(textCond("out for delivery|shipped|order|package|parcel")), RuleAction(ActionType.SNOOZE, minutes = 60)),
            ),
            Template(
                "Quiet low-importance notifications", "Anything marked low importance makes no sound.",
                rule("Quiet low-importance notifications", listOf(Cond(type = CondType.IMPORTANCE, minImportance = 0, maxImportance = 2)), RuleAction(ActionType.MUTE)),
            ),
            Template(
                "Silence when the screen is on", "You're already looking: no sound for messages.",
                rule("Silence when the screen is on", listOf(cat(Cat.MESSAGE), Cond(type = CondType.DEVICE, device = DeviceAspect.SCREEN_ON)), RuleAction(ActionType.MUTE)),
            ),
            Template(
                "Dismiss \"running in background\" notices", "Clears the noise many apps post.",
                rule("Dismiss background notices", listOf(textCond("running in the background|is running|tap for more information", WordMode.CONTAINS)), RuleAction(ActionType.DISMISS)),
            ),
            Template(
                "Hold everything at night", "Delivered at 07:00.",
                rule("Hold everything at night", listOf(time(23 * 60, 7 * 60), Cond(type = CondType.CATEGORY, categories = setOf(Cat.CALL, Cat.ALARM), negate = true)), RuleAction(ActionType.HOLD, batchTimes = listOf(7 * 60))),
            ),
            Template(
                "Light up for messages from contacts", "A special screen glow for people you know.",
                rule("Light up for contacts", listOf(cat(Cat.MESSAGE), Cond(type = CondType.FLAG, flag = Flag.FROM_CONTACT)), RuleAction(ActionType.EDGE_LIGHT, edgeStyle = "MULTICOLOUR")),
            ),
            Template(
                "Auto-reply while driving", "Replies \"Driving, will reply later\" when Do Not Disturb is on.",
                rule("Auto-reply while driving", listOf(cat(Cat.MESSAGE), Cond(type = CondType.DEVICE, device = DeviceAspect.DND_ON), Cond(type = CondType.FLAG, flag = Flag.HAS_REPLY)), RuleAction(ActionType.REPLY, text = "Driving, will reply later.")),
            ),
        )
}
