package cn.xux.cloudworkbench

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.CurrencyExchange
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal enum class ServerDataSection(val title: String, val description: String, val tag: String, val icon: ImageVector) {
    News("国际资讯", "阅读要闻、时事和经贸动态", "openInternationalNews", Icons.AutoMirrored.Outlined.Article),
    Rates("实时汇率", "获取当前汇率并换算金额", "openExchangeRates", Icons.Outlined.CurrencyExchange)
}

@Composable
internal fun ServerDataPage(
    vm: WorkbenchModel, section: ServerDataSection, visible: Boolean, menuButton: (@Composable () -> Unit)?, onBack: () -> Unit
) {
    val connected = vm.serverDataConnected
    BackHandler(enabled = visible, onBack = onBack)
    Column(Modifier.fillMaxSize().testTag("serverDataPage")) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                menuButton?.invoke()
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回浏览") }
                Text(section.title, Modifier.weight(1f).testTag("serverDataTitle"), style = MaterialTheme.typography.titleLarge)
            }
        }
        HorizontalDivider(color = Outline)
        when (section) {
            ServerDataSection.News -> NewsFeedPage(vm.newsFeed, connected, visible)
            ServerDataSection.Rates -> LiveExchangeRatesPage(vm.liveExchangeRates, visible, connected)
        }
    }
}
