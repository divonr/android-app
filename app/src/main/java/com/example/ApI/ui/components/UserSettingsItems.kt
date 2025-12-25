package com.example.ApI.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ApI.ui.theme.*

@Composable
fun IntegrationsNavigationItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Surface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "אינטגרציות (MCPs)",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = ">",
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurface.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold
            )
        }
    }
}
