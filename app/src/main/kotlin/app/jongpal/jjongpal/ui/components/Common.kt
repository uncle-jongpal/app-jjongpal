package app.jongpal.jjongpal.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.jongpal.jjongpal.ui.theme.JpText
import app.jongpal.jjongpal.ui.theme.JpTheme

// ds.css 공통 컴포넌트 포팅

@Composable
fun JpCard(
    modifier: Modifier = Modifier,
    background: Color = JpTheme.colors.surface,
    border: Boolean = true,
    radius: Int = 20,
    elevation: Int = 6,
    content: @Composable () -> Unit,
) {
    val c = JpTheme.colors
    val shape = RoundedCornerShape(radius.dp)
    val shadowColor = if (c.isDark) Color.Black else Color(0xFF3C2E18)
    Box(
        modifier
            .shadow(
                elevation = elevation.dp,
                shape = shape,
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor,
            )
            .clip(shape)
            .background(background)
            .then(if (border) Modifier.border(1.dp, c.line, shape) else Modifier)
    ) { content() }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = JpText.section, color = JpTheme.colors.ink2, modifier = modifier)
}

// 출처 태그 (통화·알림·직접 + 상대)
@Composable
fun SrcTag(label: String, who: String?, sage: Boolean = false) {
    val c = JpTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (sage) c.sage else c.accent)
        )
        Text(
            buildString { append(label); if (!who.isNullOrBlank()) append(" · $who") },
            style = JpText.tag,
            color = c.ink3,
        )
    }
}

// 체크 동그라미
@Composable
fun Tick(done: Boolean, modifier: Modifier = Modifier) {
    val c = JpTheme.colors
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (done) c.sage else Color.Transparent)
            .border(2.dp, if (done) c.sage else c.lineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (done) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

// 삼촌 아바타
@Composable
fun UncleAvatar(large: Boolean = false) {
    val c = JpTheme.colors
    val sz = if (large) 52.dp else 40.dp
    Box(
        Modifier
            .size(sz)
            .clip(RoundedCornerShape(if (large) 17.dp else 13.dp))
            .background(c.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text("쫑", color = c.accentInk, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = JpTheme.colors
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) c.accent else c.surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = JpText.button, color = if (enabled) c.accentInk else c.ink3)
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    val c = JpTheme.colors
    Box(
        modifier
            .height(if (small) 36.dp else 46.dp)
            .clip(RoundedCornerShape(if (small) 11.dp else 14.dp))
            .background(c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(if (small) 11.dp else 14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (small) JpText.body.copy(fontWeight = FontWeight.Bold) else JpText.button, color = c.ink)
    }
}

// 작은 색 버튼 (제안 분류용: 할 일로 / 약속으로)
@Composable
fun RowScope.PillAction(
    text: String,
    accent: Boolean,
    onClick: () -> Unit,
) {
    val c = JpTheme.colors
    val bg = if (accent) c.accent else c.surface2
    val fg = if (accent) c.accentInk else c.ink
    Box(
        Modifier
            .weight(1f)
            .height(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .then(if (accent) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(11.dp)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = JpText.body.copy(fontWeight = FontWeight.Bold), color = fg)
    }
}

@Composable
fun HLine() {
    Spacer(Modifier.height(1.dp).background(JpTheme.colors.line))
}
