package app.jongpal.jjongpal.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.jongpal.jjongpal.ui.components.PrimaryButton
import app.jongpal.jjongpal.ui.components.UncleAvatar
import app.jongpal.jjongpal.ui.theme.JpText
import app.jongpal.jjongpal.ui.theme.JpTheme

@Composable
fun LoginScreen(
    loading: Boolean,
    errorMessage: String?,
    onLogin: (email: String, password: String) -> Unit,
) {
    val c = JpTheme.colors
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UncleAvatar(large = true)
        Spacer(Modifier.height(18.dp))
        Text("쫑팔이삼촌", style = JpText.title, color = c.ink)
        Spacer(Modifier.height(6.dp))
        Text("통화·알림 속 할 일을 삼촌이 챙겨드려요", style = JpText.body, color = c.ink2)
        Spacer(Modifier.height(36.dp))

        LoginField(email, { email = it }, "이메일", KeyboardType.Email, enabled = !loading)
        Spacer(Modifier.height(12.dp))
        LoginField(password, { password = it }, "비밀번호", KeyboardType.Password, password = true, enabled = !loading)
        Spacer(Modifier.height(24.dp))

        PrimaryButton(
            text = if (loading) "" else "로그인",
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
        ) { onLogin(email, password) }
        if (loading) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
        }

        if (!errorMessage.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(errorMessage, style = JpText.body, color = c.danger)
        }
    }
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    password: Boolean = false,
    enabled: Boolean = true,
) {
    val c = JpTheme.colors
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(label, style = JpText.body, color = c.ink3) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.surface2,
            unfocusedContainerColor = c.surface2,
            disabledContainerColor = c.surface2,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = c.ink,
            unfocusedTextColor = c.ink,
        ),
    )
}
