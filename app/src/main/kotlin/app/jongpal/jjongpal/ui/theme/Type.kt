package app.jongpal.jjongpal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import app.jongpal.jjongpal.R

// ds.css 타이포 스케일 — Pretendard 번들.
private val JpFont = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
)

object JpText {
    val greet = TextStyle(fontFamily = JpFont, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontFamily = JpFont, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, lineHeight = 30.sp)
    val titleSm = TextStyle(fontFamily = JpFont, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
    val section = TextStyle(fontFamily = JpFont, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
    val card = TextStyle(fontFamily = JpFont, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp)
    val body = TextStyle(fontFamily = JpFont, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
    val meta = TextStyle(fontFamily = JpFont, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
    val tag = TextStyle(fontFamily = JpFont, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
    val button = TextStyle(fontFamily = JpFont, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val strike = TextStyle(textDecoration = TextDecoration.LineThrough)
}

val JpTypography = Typography(
    titleLarge = JpText.title,
    titleMedium = JpText.card,
    bodyMedium = JpText.body,
    labelSmall = JpText.meta,
)
