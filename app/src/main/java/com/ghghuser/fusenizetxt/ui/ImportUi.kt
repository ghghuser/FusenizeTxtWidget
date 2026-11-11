package com.ghghuser.fusenizetxt.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ghghuser.fusenizetxt.data.Importer
import com.ghghuser.fusenizetxt.data.MemoRepo
import kotlinx.coroutines.CompletableDeferred

class ConflictAsk(
    val name: String,
    val remaining: Int,
    val result: CompletableDeferred<Importer.ConflictDecision?>,
)

/** 取り込み中に「同名ファイルがあります」をユーザーに尋ねるための橋渡し */
class ImportCoordinator {
    var ask by mutableStateOf<ConflictAsk?>(null)

    suspend fun askConflict(name: String, remaining: Int): Importer.ConflictDecision? {
        val d = CompletableDeferred<Importer.ConflictDecision?>()
        ask = ConflictAsk(name, remaining, d)
        return try {
            d.await()
        } finally {
            ask = null
        }
    }
}

@Composable
fun ConflictDialog(c: ImportCoordinator) {
    val a = c.ask ?: return
    var applyAll by remember(a) { mutableStateOf(false) }
    var confirming by remember(a) { mutableStateOf(false) }

    if (!confirming) {
        AlertDialog(
            onDismissRequest = { a.result.complete(null) },
            title = { Text("同じ名前のファイルがあります") },
            text = {
                Column {
                    Text("「${a.name}」は保存先にすでにあります。どうしますか？")
                    if (a.remaining > 0) {
                        Row(
                            Modifier.fillMaxWidth().clickable { applyAll = !applyAll },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                            Text("残り${a.remaining}件の重複にも同じ方法を適用")
                        }
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        a.result.complete(Importer.ConflictDecision(MemoRepo.OnConflict.RENAME, applyAll))
                    }) { Text("名前を変更して保存（(2)(3)…）") }
                    TextButton(onClick = { confirming = true }) { Text("上書きする") }
                }
            },
            dismissButton = { TextButton(onClick = { a.result.complete(null) }) { Text("中止") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("本当に上書きしますか？") },
            text = {
                Text(
                    "「${a.name}」の内容は元に戻せません。" +
                        if (applyAll) "\n残りの重複ファイルもすべて上書きされます。" else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    a.result.complete(Importer.ConflictDecision(MemoRepo.OnConflict.OVERWRITE, applyAll))
                }) { Text("上書きする") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("戻る") } },
        )
    }
}
