package cn.xux.cloudworkbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.text.Collator
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

@Composable
internal fun WorldBankTradePage(trade: WorldBankTrade, connected: Boolean, visible: Boolean) {
    val scope = rememberCoroutineScope()
    val latestYear = LocalDate.now().year
    var countryCode by rememberSaveable { mutableStateOf(trade.result?.country ?: "CHN") }
    var yearText by rememberSaveable { mutableStateOf((trade.result?.year ?: (latestYear - 1)).toString()) }
    var choosingCountry by rememberSaveable { mutableStateOf(false) }
    val selected = trade.countries.firstOrNull { it.code == countryCode }
    val year = yearText.toIntOrNull()?.takeIf { it in 1960..latestYear }
    val result = trade.result?.takeIf { it.country == countryCode && it.year == year }
    val failed = trade.failedQuery == (countryCode to year)

    LaunchedEffect(visible, connected) {
        if (visible && connected) {
            trade.loadCountries()
            year?.let { trade.query(countryCode, it) }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("worldBankTradePanel"), horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = LocalPagePadding.current, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text("国际贸易数据", style = MaterialTheme.typography.titleLarge)
                Text("来源：世界银行《世界发展指标》（WDI）", Modifier.testTag("tradeSource"), color = Muted)
                Text("按年度统计货物和服务贸易。金额按美元取整，比重保留两位小数。",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { choosingCountry = true }, enabled = trade.countries.isNotEmpty() && !trade.loading,
                    modifier = Modifier.fillMaxWidth().testTag("tradeCountry")) {
                    Text(selected?.let { "${it.displayName}（${it.code}）" } ?: "选择国家或经济体")
                }
                OutlinedTextField(
                    yearText, { yearText = it.filter { digit -> digit in '0'..'9' }.take(4) },
                    modifier = Modifier.fillMaxWidth().testTag("tradeYear"), enabled = !trade.loading && !trade.countriesLoading,
                    label = { Text("统计年份") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = yearText.length == 4 && year == null
                )
                if (yearText.length == 4 && year == null) {
                    Text("请输入 1960 至 $latestYear 年", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = { year?.let { scope.launch { trade.query(countryCode, it) } } },
                    enabled = connected && !trade.loading && !trade.countriesLoading && year != null && selected != null,
                    modifier = Modifier.testTag("tradeQuery")
                ) { Text(if (trade.loading) "正在查询…" else "查询") }
                if (trade.countriesLoading || trade.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (connected && trade.countriesUnavailable && trade.countries.isEmpty()) {
                    TextButton(onClick = { scope.launch { trade.loadCountries() } }) { Text("重试国家目录") }
                }
                val status = when {
                    !connected && result != null -> "SSH 未连接，显示上次取得的数据"
                    !connected -> "连接云端后可查询贸易数据"
                    failed && result != null -> "查询失败，显示上次取得的数据"
                    failed -> "暂时无法取得该国家和年份的数据，请重试"
                    result?.stale == true -> "世界银行数据暂不可达，显示服务器缓存"
                    trade.countriesUnavailable || trade.countriesStale -> "国家目录暂不可更新"
                    else -> null
                }
                status?.let { Text(it, Modifier.testTag("tradeStatus"), color = Amber) }
                if (connected && result != null && !result.hasData && result.year > 1960) {
                    TextButton(onClick = {
                        val previous = result.year - 1
                        yearText = previous.toString()
                        scope.launch { trade.query(countryCode, previous) }
                    }, enabled = !trade.loading, modifier = Modifier.testTag("tradePreviousYear")) {
                        Text("查询 ${result.year - 1} 年")
                    }
                }
            }
        }
        if (result != null) item { TradeResult(result) }
    }
    if (choosingCountry && visible) TradeCountryPicker(trade.countries, onClose = { choosingCountry = false }) {
        countryCode = it.code
        choosingCountry = false
    }
}

@Composable
private fun TradeResult(result: TradeObservation) {
    val decimal = remember {
        NumberFormat.getNumberInstance(Locale.SIMPLIFIED_CHINESE).apply { maximumFractionDigits = 2; minimumFractionDigits = 2 }
    }
    val usd = remember {
        NumberFormat.getNumberInstance(Locale.SIMPLIFIED_CHINESE).apply { maximumFractionDigits = 0 }
    }
    Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("tradeResult")) {
        Text("统计年度：${result.year} 年", Modifier.testTag("tradeDataYear"), style = MaterialTheme.typography.titleMedium)
        Text("数据集更新：${result.updatedAt ?: "来源未提供"}", Modifier.testTag("tradeUpdatedAt"), color = Muted)
        if (!result.hasData) {
            Text("世界银行暂无该国家或经济体在该年度的数据", Modifier.testTag("tradeNoData"))
        } else {
            Text("贸易总额占 GDP 比重：${result.tradePercent?.let { "${decimal.format(it)}%" } ?: "暂无数据"}",
                Modifier.testTag("tradePercent"))
            Text("出口额（现价美元）：${result.exportsUsd?.let(usd::format) ?: "暂无数据"}",
                Modifier.testTag("tradeExports"))
            Text("进口额（现价美元）：${result.importsUsd?.let(usd::format) ?: "暂无数据"}",
                Modifier.testTag("tradeImports"))
        }
    }
}

@Composable
private fun TradeCountryPicker(countries: List<TradeCountry>, onClose: () -> Unit, onSelect: (TradeCountry) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val sorted = remember(countries) {
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        countries.sortedWith { first, second -> collator.compare(first.displayName, second.displayName) }
    }
    val matches = remember(sorted, search) {
        val words = search.trim()
        sorted.filter { it.displayName.contains(words, ignoreCase = true) ||
            it.name.contains(words, ignoreCase = true) || it.code.contains(words, ignoreCase = true) }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(16.dp)) {
                Text("选择国家或经济体", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(search, { search = it.take(60) },
                    modifier = Modifier.fillMaxWidth().testTag("tradeCountrySearch"),
                    label = { Text("国家、经济体或代码") }, singleLine = true)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    if (matches.isEmpty()) item {
                        Text("找不到匹配的国家或经济体", Modifier.padding(vertical = 16.dp))
                    }
                    items(matches, key = { it.code }) { country ->
                        ListItem(
                            headlineContent = { Text(country.displayName) },
                            trailingContent = { Text(country.code) },
                            modifier = Modifier.testTag("tradeOption-${country.code}").clickable { onSelect(country) }
                        )
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("取消") }
            }
        }
    }
}
