package com.ghghuser.fusenizetxt.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghghuser.fusenizetxt.data.MemoRepo

/**
 * UTF-8として読めなかったファイルの扱いを尋ねる。
 *  - このまま開く: 元の文字コードを保ったまま編集・保存する（ファイルは変更しない）
 *  - UTF-8に変換: 元のバイト列を端末内にバックアップしてからUTF-8で書き直す
 */
@Composable
fun EncodingDialog(
    bytes: ByteArray,
    onOpenAs: (String) -> Unit,
    onConvert: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var sel by remember(bytes) { mutableStateOf(MemoRepo.guessEncoding(bytes)) }
    val canUse = remember(bytes, sel) { MemoRepo.tryDecode(bytes, sel) != null }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("UTF-8ではないファイルです") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "文字化けしない文字コードを選んでください。選ぶだけではファイルは変更されません。",
                    style = MaterialTheme.typography.bodySmall,
                )
                MemoRepo.ENCODINGS.forEach { (id, label) ->
                    val preview = remember(bytes, id) {
                        MemoRepo.tryDecode(bytes, id)?.take(60)?.replace('\n', ' ')
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { sel = id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sel == id, onClick = { sel = id })
                        Column {
                            Text(label)
                            Text(
                                preview ?: "（この文字コードでは読めません）",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(enabled = canUse, onClick = { onOpenAs(sel) }) {
                    Text("このまま開く（ファイルは変更しない）")
                }
                TextButton(enabled = canUse, onClick = { onConvert(sel) }) {
                    Text("UTF-8に変換して開く（元は端末内にバックアップ）")
                }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } },
    )
}
