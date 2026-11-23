package cn.xux.cloudworkbench

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

internal data class ReferenceRate(val currency: String, val rate: String)
private enum class ServerDataSection { Resources, Rates, Trade }

internal data class ReferenceRates(val date: LocalDate, val rates: List<ReferenceRate>, val stale: Boolean) {
    companion object {
        private val currencyCode = Regex("[A-Z]{3}")
        private val decimalRate = Regex("""\d{1,12}(\.\d{1,12})?""")

        fun parse(payload: JSONObject): ReferenceRates {
            val entries = payload.getJSONArray("rates")
            require(entries.length() in 1..100)
            val rates = entries.objects().map {
                val currency = it.getString("currency")
                val rate = it.getString("rate")
                require(currency != "EUR" && currencyCode.matches(currency) &&
                    decimalRate.matches(rate) && rate.toBigDecimal().signum() > 0)
                ReferenceRate(currency, rate)
            }
            require(rates.size == entries.length() && rates.distinctBy { it.currency }.size == rates.size)
            return ReferenceRates(LocalDate.parse(payload.getString("date")), rates, payload.getBoolean("stale"))
        }
    }
}

@Composable
internal fun ServerDataPage(
    vm: WorkbenchModel, visible: Boolean, menuButton: (@Composable () -> Unit)?, onBack: () -> Unit
) {
    val connected = vm.serverDataConnected
    val rates = vm.ecbRates
    val loading = vm.referenceRatesLoading
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(ServerDataSection.Resources) }
    BackHandler(enabled = visible, onBack = onBack)
    LaunchedEffect(visible, connected, section) {
        if (visible && connected && section == ServerDataSection.Rates) vm.refreshReferenceRates()
    }
    Column(Modifier.fillMaxSize().testTag("serverDataPage")) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                menuButton?.invoke()
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回浏览") }
                Text("服务器数据", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (section == ServerDataSection.Rates) {
                    TextButton(onClick = { scope.launch { vm.refreshReferenceRates() } }, enabled = connected && !loading,
                        modifier = Modifier.testTag("referenceRatesRefresh")) {
                        Text("刷新")
                    }
                }
            }
        }
        HorizontalDivider(color = Outline)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            FilterChip(selected = section == ServerDataSection.Resources, onClick = { section = ServerDataSection.Resources },
                label = { Text("按需获取") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = section == ServerDataSection.Rates, onClick = { section = ServerDataSection.Rates },
                label = { Text("欧元汇率") }, modifier = Modifier.testTag("openEcbRates"))
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = section == ServerDataSection.Trade, onClick = { section = ServerDataSection.Trade },
                label = { Text("国际贸易") },
                modifier = Modifier.testTag("openWorldBankTrade"))
        }
        when (section) {
            ServerDataSection.Resources -> {
                ResourceDataPage(vm.onDemandResources, connected, visible)
                return@Column
            }
            ServerDataSection.Trade -> {
                WorldBankTradePage(vm.worldBankTrade, connected, visible)
                return@Column
            }
            ServerDataSection.Rates -> Unit
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = LocalPagePadding.current, vertical = 16.dp)
        ) {
            item {
                Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    Text("欧元参考汇率", style = MaterialTheme.typography.titleLarge)
                    Text("来源：欧洲央行（ECB）", Modifier.testTag("referenceRatesSource"), color = Muted)
                    Text(rates?.let { "数据日期：${it.date}" } ?: "数据日期：待获取",
                        Modifier.testTag("referenceRatesDate"), color = Muted)
                    Text("每 1 欧元对应的外币金额，仅供参考。", style = MaterialTheme.typography.bodySmall, color = Muted)
                    val status = when {
                        !connected && rates != null -> "SSH 未连接，显示上次取得的数据"
                        !connected -> "连接云端后可获取参考汇率"
                        vm.referenceRatesUnavailable && rates != null -> "刷新失败，显示上次取得的数据"
                        vm.referenceRatesUnavailable -> "暂时无法获取参考汇率，请重试"
                        rates?.stale == true -> "欧洲央行数据暂不可达，显示服务器缓存"
                        loading && rates == null -> "正在从服务器读取…"
                        else -> null
                    }
                    status?.let { Text(it, Modifier.testTag("referenceRatesStatus"), color = Amber) }
                }
            }
            items(rates?.rates.orEmpty(), key = { it.currency }) { entry ->
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(entry.currency)
                        Text(entry.rate, fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider(color = Outline)
                }
            }
        }
    }
}
