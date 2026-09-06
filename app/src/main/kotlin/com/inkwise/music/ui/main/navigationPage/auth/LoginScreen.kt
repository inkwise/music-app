/*
 * 登录页（LoginScreen）
 *
 * 提供用户名/密码输入、记住密码开关、错误信息展示与登录按钮。
 * 登录逻辑在 AuthViewModel.login()：成功回调 onSuccess 进入主界面，
 * 点击"立即注册"通过 onNavigateToRegister 切换到注册页。
 */
package com.inkwise.music.ui.main.navigationPage.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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

/** 登录页主界面：表单输入、记住密码、提交登录与跳转注册 */
@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit,
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
            text = "登录",
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

        // 密码输入框，使用掩码隐藏明文
        OutlinedTextField(
            value = uiState.password,
            onValueChange = { viewModel.onPasswordChanged(it) },
            label = { Text("密码") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )

        // 记住密码勾选框
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = uiState.rememberPassword,
                onCheckedChange = { viewModel.onRememberPasswordChanged(it) }
            )
            Text(
                text = "记住密码",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // 展示登录结果消息（错误用红色，成功用主色）
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

        // 登录按钮：加载中禁用并在按钮内显示小转圈
        Button(
            onClick = { viewModel.login(onSuccess) },
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
            Text("登录")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 切换到注册页的入口
        TextButton(onClick = onNavigateToRegister) {
            Text("没有账号？立即注册")
        }
    }
}
