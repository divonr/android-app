package com.example.ApI.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.data.model.BranchInfo

/**
 * A composable that displays branch navigation controls.
 * Shows arrows to navigate between variants and the current variant number.
 * 
 * Example display: < 2/3 >
 */
@Composable
fun BranchNavigator(
    branchInfo: BranchInfo,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val disabledColor = contentColor.copy(alpha = 0.3f)
    
    Surface(
        modifier = modifier,
        color = containerColor,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 4.dp else 8.dp, vertical = if (compact) 2.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Left arrow (<) - goes to PREVIOUS variant (lower number)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "ענף קודם",
                modifier = Modifier
                    .size(if (compact) 18.dp else 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = branchInfo.hasPrevious) { onPrevious() }
                    .padding(2.dp),
                tint = if (branchInfo.hasPrevious) contentColor else disabledColor
            )
            
            // Variant counter
            Text(
                text = branchInfo.displayText,
                fontSize = if (compact) 11.sp else 13.sp,
                color = contentColor,
                modifier = Modifier.padding(horizontal = if (compact) 4.dp else 8.dp)
            )
            
            // Right arrow (>) - goes to NEXT variant (higher number)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "ענף הבא",
                modifier = Modifier
                    .size(if (compact) 18.dp else 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = branchInfo.hasNext) { onNext() }
                    .padding(2.dp),
                tint = if (branchInfo.hasNext) contentColor else disabledColor
            )
        }
    }
}

/**
 * A composable that conditionally shows the BranchNavigator if the message has branches.
 */
@Composable
fun ConditionalBranchNavigator(
    branchInfo: BranchInfo?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    // Only show if there are multiple variants (branchInfo is non-null when totalVariants > 1)
    if (branchInfo != null) {
        BranchNavigator(
            branchInfo = branchInfo,
            onPrevious = onPrevious,
            onNext = onNext,
            modifier = modifier,
            compact = compact
        )
    }
}
