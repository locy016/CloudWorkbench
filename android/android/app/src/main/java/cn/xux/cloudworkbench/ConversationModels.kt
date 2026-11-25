package cn.xux.cloudworkbench

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class ConversationModel(val id: String, val name: String, val default: Boolean)

@Composable
internal fun ConversationModelPicker(vm: WorkbenchModel, modifier: Modifier = Modifier) {
    var open by remember(vm.draftKey) { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = {
            open = true
            vm.loadConversationModels()
        }, enabled = vm.canChooseConversationModel, modifier = Modifier.testTag("conversationModelPicker")) {
            Text("模型 · ${vm.conversationModelLabel}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            when {
                vm.modelsLoading -> DropdownMenuItem(text = { Text("正在加载模型…") }, onClick = {}, enabled = false)
                vm.modelError != null -> DropdownMenuItem(text = { Text("模型读取失败，点此重试") },
                    onClick = vm::loadConversationModels)
                vm.models.isEmpty() -> DropdownMenuItem(text = { Text("服务器暂无可选模型") }, onClick = {}, enabled = false)
                else -> vm.models.forEach { model ->
                    DropdownMenuItem(text = { Text(if (model.default) "${model.name} · 服务器默认" else model.name) },
                        leadingIcon = { if (model.id == vm.conversationModel ||
                            model.default && vm.currentId == null && vm.conversationModel == null) {
                            Icon(Icons.Outlined.Check, "当前选择")
                        } },
                        onClick = { vm.chooseConversationModel(model); open = false },
                        enabled = vm.canChooseConversationModel,
                        modifier = Modifier.testTag("conversationModel-${model.id}"))
                }
            }
        }
    }
}
