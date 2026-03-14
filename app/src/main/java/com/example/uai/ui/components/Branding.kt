package com.example.uai.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.uai.R

@Composable
fun BrandMarkIcon(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Icon(
        painter = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_launcher_monochrome)),
        contentDescription = null,
        tint = tint,
        modifier = modifier
    )
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun BrandMarkBadge(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Image(
        painter = painterResource(R.drawable.ic_screenagent_brand_tile),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(15.dp))
            .then(modifier)
    )
}

@Composable
fun ProductEmptyStateCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    titleAlign: TextAlign = TextAlign.Start,
    bodyAlign: TextAlign = TextAlign.Start
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = horizontalAlignment
        ) {
            BrandMarkBadge()
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = titleAlign
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = bodyAlign
            )
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
