package com.github.kihi78.limitout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LimitOutMainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitOutMainScreen() {
    // 状態管理（MVP用の仮置き。後でSharedPreferences等と連携します）
    var isEnabled by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf("未選択") }
    var limitTime by remember { mutableStateOf("5") }
    var snoozeTime by remember { mutableStateOf("10") }
    var expanded by remember { mutableStateOf(false) }

    val limitOptions = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "20", "25", "30", "40", "50", "60")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(text = "LimitOut", style = MaterialTheme.typography.headlineLarge)

        // 1. マスタースイッチ
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "制限機能の有効化", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = isEnabled,
                onCheckedChange = { isEnabled = it }
            )
        }

        Divider()

        // 2. アプリ選択
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = "対象アプリ", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { /* TODO: アプリ選択画面へ遷移 */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = selectedApp)
            }
        }

        // 3. 制限時間選択 (ドロップダウン)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = "$limitTime 分",
                onValueChange = {},
                readOnly = true,
                label = { Text("制限時間") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                limitOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text("$option 分") },
                        onClick = {
                            limitTime = option
                            expanded = false
                        }
                    )
                }
            }
        }

        // 4. スヌーズ時間設定
        OutlinedTextField(
            value = snoozeTime,
            onValueChange = {
                // 数字のみ入力を許可
                if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                    snoozeTime = it
                }
            },
            label = { Text("スヌーズ時間 (分)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}
