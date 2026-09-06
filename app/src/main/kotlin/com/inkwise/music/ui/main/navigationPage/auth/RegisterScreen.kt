/*
 * 注册页（RegisterScreen）
 *
 * 提供用户名、密码（至少 6 位）、可选邮箱的输入与注册按钮。
 * 注册逻辑在 AuthViewModel.register()：注册成功后自动登录并回调 onSuccess，
 * 点击"返回登录"通过 onNavigateToLogin 回到登录页。
 */
package com.inkwise.music.ui.main.navigationPage.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** 注册页主界面：表单输入、注册提交与跳转登录 */
@Composable
fun RegisterScreen(
    onNavigateToLogin: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "注册",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 24.dp)
        )

        // 用户名输入框，双向绑定到 ViewModel 状态
        OutlinedTextField(
            value = uiState.username,
            onValueChange = { viewModel.onUsernameChanged(it) },
            label = { Text("用户名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 密码输入框（注册要求至少 6 位，由 ViewModel 校验）
        OutlinedTextField(
            value = uiState.password,
            onValueChange = { viewModel.onPasswordChanged(it) },
            label = { Text("密码 (至少6个字符)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 邮箱为可选项，非必填
        OutlinedTextField(
            value = uiState.email,
            onValueChange = { viewModel.onEmailChanged(it) },
            label = { Text("邮箱 (可选)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // 展示注册结果消息（错误用红色，成功用主色）
        val message = uiState.message
        if (message != null) {
            Text(
                text = message,
                color = if (uiState.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 注册按钮：加载中禁用并在按钮内显示小转圈
        Button(
            onClick = { viewModel.register(onSuccess) },
            enabled = !uiState.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.width(16.dp).height(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("注册")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 切换到登录页的入口
        TextButton(onClick = onNavigateToLogin) {
            Text("已有账号？返回登录")
        }
    }
}
