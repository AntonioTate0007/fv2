package com.thumbshade.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class CardBg(val label: String) { THEME("Theme colour"), CUSTOM("Custom colour"), NOTIFICATION("Notification colour (tinted)"), GRADIENT("Gradient") }

@Serializable
enum class HeaderIcon(val label: String) { SMALL("Notification icon"), APP("App icon"), SENDER("Sender / group picture"), NONE("None") }

@Serializable
enum class TextAlignChoice(val label: String) { START("Start"), CENTER("Centre"), END("End") }

/**
 * How notification cards look in the shade. For colours, 0 means automatic: text follows the
 * theme, accents follow the notification's own colour.
 */
@Serializable
data class CardStyle(
    // Background and border
    val bgSource: CardBg = CardBg.THEME,
    val bgColor: Long = 0xFF1E2226,
    val gradientEnd: Long = 0xFF2A3D4A,
    val borderWidthDp: Int = 0,
    val borderColor: Long = 0x40FFFFFF,
    val borderFromNotification: Boolean = false,
    val fallbackColor: Long = 0,
    val paddingDp: Int = 14,

    // Header
    val showAppName: Boolean = true,
    val appNameSp: Int = 12,
    val appNameBold: Boolean = false,
    val appNameColor: Long = 0,
    val headerIcon: HeaderIcon = HeaderIcon.SMALL,
    val headerIconDp: Int = 18,
    val appBadge: Boolean = true,
    val headerLines: Int = 1,
    val showSubtitle: Boolean = true,
    val subtitleInHeader: Boolean = true,
    val subtitleSp: Int = 12,
    val subtitleBold: Boolean = false,
    val subtitleColor: Long = 0,

    // Title
    val showTitle: Boolean = true,
    val titleSp: Int = 15,
    val titleBold: Boolean = true,
    val titleColor: Long = 0,
    val titleLines: Int = 2,
    val titleAlign: TextAlignChoice = TextAlignChoice.START,

    // Body
    val showBody: Boolean = true,
    val bodySp: Int = 14,
    val bodyBold: Boolean = false,
    val bodyColor: Long = 0,
    val bodyAlign: TextAlignChoice = TextAlignChoice.START,
    val limitBodyLines: Boolean = true,
    val hideTitleFromBody: Boolean = true,

    // Time
    val showTime: Boolean = true,
    val timeSp: Int = 11,
    val timeBold: Boolean = false,
    val timeColor: Long = 0,
    val clockTime: Boolean = false,

    // Large icon
    val largeIconDp: Int = 44,
    val roundLargeIcon: Boolean = false,
    val senderPicture: Boolean = true,
    val hideSenderIfInHeader: Boolean = true,

    // Buttons and progress bar
    val buttonSp: Int = 14,
    val buttonBold: Boolean = false,
    val buttonColor: Long = 0,
    val buttonBackground: Boolean = false,
    val buttonBgColor: Long = 0x22FFFFFF,
    val buttonBorder: Boolean = false,
    val buttonBorderColor: Long = 0x66FFFFFF,
    val buttonBorderDp: Int = 1,
    val buttonCornerDp: Int = 14,
    val buttonPaddingDp: Int = 8,
    val progressColor: Long = 0,

    // Media player
    val mediaTint: Long = 0,
    val mediaNextTrack: Boolean = true,
)
