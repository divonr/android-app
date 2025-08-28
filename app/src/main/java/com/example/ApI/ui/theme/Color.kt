package com.example.ApI.ui.theme

import androidx.compose.ui.graphics.Color

// Modern minimalist dark theme colors
val Primary = Color(0xFF6C7CE7)      // Soft purple-blue (modern accent)
val Secondary = Color(0xFF5B6EF7)    // Complementary purple
val Tertiary = Color(0xFF9C88FF)     // Light purple accent

// Background and surfaces - Ultra minimalist
val Background = Color(0xFF0D0E14)    // Very dark blue-black
val Surface = Color(0xFF1A1B26)       // Subtle dark blue-gray
val SurfaceVariant = Color(0xFF20212C) // Slightly lighter surface
val OnPrimary = Color(0xFFFFFFFF)     // White text on primary
val OnSecondary = Color(0xFFFFFFFF)   // White text on secondary  
val OnTertiary = Color(0xFF000000)    // Black text on tertiary
val OnBackground = Color(0xFFE8E8F2)  // Soft light gray text
val OnSurface = Color(0xFFE8E8F2)     // Soft light gray text
val OnSurfaceVariant = Color(0xFFBDBDBD) // Medium gray text

// Chat specific colors - Minimalist and elegant
val UserMessageBubble = Color(0xFF6C7CE7)    // Primary color for user
val AssistantMessageBubble = Color(0xFF2A2B3A) // Subtle dark for assistant
val SystemMessageBubble = Color(0xFF3A3B4A)    // Slightly lighter for system
val MessageBorder = Color(0xFF34354A)         // Subtle borders

// Accent colors for UI elements
val AccentBlue = Color(0xFF5DADE2)     // Calm blue
val AccentGreen = Color(0xFF58D68D)    // Success green
val AccentRed = Color(0xFFEC7063)      // Error red
val AccentYellow = Color(0xFFF7DC6F)   // Warning yellow

// Subtle grays for minimal design
val Gray50 = Color(0xFFF8F9FA)
val Gray100 = Color(0xFFE9ECEF) 
val Gray200 = Color(0xFFDEE2E6)
val Gray300 = Color(0xFFCED4DA)
val Gray400 = Color(0xFFADB5BD)
val Gray500 = Color(0xFF6C757D)
val Gray600 = Color(0xFF495057)
val Gray700 = Color(0xFF343A40)
val Gray800 = Color(0xFF212529)
val Gray900 = Color(0xFF0F1419)

// Text colors for consistency
val TextPrimary = OnSurface
val TextSecondary = OnSurfaceVariant
val Border = Gray500
val ChatBubbleOutline = Gray400
val ChatBubble = Gray600