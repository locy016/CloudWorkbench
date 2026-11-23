package cn.xux.cloudworkbench

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

internal data class ExchangeRateSnapshot(val fetchedAt: Instant, val rates: Map<String, BigDecimal>, val stale: Boolean) {
    fun convert(amount: BigDecimal, from: String, to: String): BigDecimal {
        return amount.multiply(rates.getValue(to)).divide(rates.getValue(from), MathContext.DECIMAL128)
    }

    companion object {
        fun parse(payload: JSONObject): ExchangeRateSnapshot {
            require(payload.getString("base") == "USD" && payload.getString("provider") == "Coinbase")
            val data = payload.getJSONObject("rates")
            val codes = data.keys().asSequence().toList()
            require(codes.size in 2..40 && codes.all { it.matches(Regex("[A-Z]{3}")) })
            val rates = codes.associateWith { code ->
                val value = data.getString(code)
                require(value.matches(Regex("""\d{1,15}(\.\d{1,32})?""")))
                value.toBigDecimal().also { require(it.signum() > 0) }
            }
            require(rates.getValue("USD").compareTo(BigDecimal.ONE) == 0 && "CNY" in rates && "EUR" in rates)
            return ExchangeRateSnapshot(Instant.parse(payload.getString("fetchedAt")), rates, payload.getBoolean("stale"))
        }
    }
}

internal class LiveExchangeRates(private val prefs: SharedPreferences, private val connection: () -> WorkbenchConnection?) {
    private val cacheKey = "last-live-exchange-rates"
    var snapshot by mutableStateOf(prefs.getString(cacheKey, null)?.let {
        runCatching { ExchangeRateSnapshot.parse(JSONObject(it)) }.getOrNull()
    }); private set
    var loading by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set

    init {
        if (prefs.contains("last-ecb-reference-rates")) prefs.edit { remove("last-ecb-reference-rates") }
    }

    suspend fun refresh(force: Boolean = false) {
        val transport = connection() ?: return
        if (loading) return
        loading = true
        failed = false
        try {
            val path = "/v1/server-data/exchange-rates" + if (force) "?refresh=1" else ""
            val payload = transport.sync(path)
            val quotes = ExchangeRateSnapshot.parse(payload)
            if (transport === connection()) {
                snapshot = quotes
                prefs.edit { putString(cacheKey, payload.toString()) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (transport === connection()) failed = true
        } finally {
            loading = false
        }
    }
}

private val quoteTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val amountPattern = Regex("""\d{0,12}(?:\.\d{0,8})?""")
private fun currencyName(code: String) =
    runCatching { Currency.getInstance(code).getDisplayName(Locale.SIMPLIFIED_CHINESE) }.getOrDefault(code)
private fun BigDecimal.quoteText() = setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

@Composable
internal fun LiveExchangeRatesPage(rates: LiveExchangeRates, visible: Boolean, connected: Boolean) {
    val scope = rememberCoroutineScope()
    val snapshot = rates.snapshot
    var amount by rememberSaveable { mutableStateOf("1") }
    var from by rememberSaveable { mutableStateOf("USD") }
    var to by rememberSaveable { mutableStateOf("CNY") }
    var pickingFrom by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val fromCode = from.takeIf { snapshot == null || it in snapshot.rates } ?: "USD"
    val toCode = to.takeIf { snapshot == null || it in snapshot.rates } ?: "CNY"
    val value = amount.toBigDecimalOrNull()
    val converted = if (snapshot != null && value != null) snapshot.convert(value, fromCode, toCode) else null

    LaunchedEffect(visible, connected) {
        if (visible && connected) while (true) {
            rates.refresh()
            delay(30_000)
        }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("exchangeRatePanel"), horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = LocalPagePadding.current, vertical = 16.dp)) {
        item {
            Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("汇率换算", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { scope.launch { rates.refresh(force = true) } }, enabled = connected && !rates.loading,
                        modifier = Modifier.testTag("exchangeRateRefresh")) { Text("刷新") }
                }
                OutlinedTextField(amount, { if (amountPattern.matches(it)) amount = it },
                    modifier = Modifier.fillMaxWidth().testTag("exchangeRateAmount"), singleLine = true,
                    label = { Text("金额") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickingFrom = true }, enabled = snapshot != null,
                        modifier = Modifier.weight(1f).testTag("exchangeRateFrom")) {
                        Text("$fromCode · ${currencyName(fromCode)}", maxLines = 1)
                    }
                    IconButton(onClick = { from = toCode; to = fromCode }, enabled = snapshot != null,
                        modifier = Modifier.testTag("exchangeRateSwap")) { Icon(Icons.Outlined.SwapHoriz, "交换币种") }
                    OutlinedButton(onClick = { pickingFrom = false }, enabled = snapshot != null,
                        modifier = Modifier.weight(1f).testTag("exchangeRateTo")) {
                        Text("$toCode · ${currencyName(toCode)}", maxLines = 1)
                    }
                }
                converted?.let {
                    Text("${it.quoteText()} $toCode", Modifier.testTag("exchangeRateResult"),
                        style = MaterialTheme.typography.headlineSmall)
                }
                if (snapshot != null) {
                    val unit = snapshot.convert(BigDecimal.ONE, fromCode, toCode).quoteText()
                    Text("1 $fromCode ≈ $unit $toCode", Modifier.testTag("exchangeRateUnit"), color = Muted)
                }
                Text("来源：Coinbase", Modifier.testTag("exchangeRateSource"), color = Muted)
                snapshot?.let {
                    val time = quoteTime.format(it.fetchedAt.atZone(ZoneId.systemDefault()))
                    Text("取得时间：$time", Modifier.testTag("exchangeRateTime"), color = Muted)
                }
                Text("汇率仅供换算参考。", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (rates.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val status = when {
                    !connected && snapshot != null -> "SSH 未连接，使用上次取得的汇率"
                    !connected -> "连接云端后可取得汇率"
                    rates.failed && snapshot != null -> "刷新失败，使用上次取得的汇率"
                    rates.failed -> "暂时无法取得汇率，请重试"
                    snapshot?.stale == true -> "报价源暂不可达，使用服务器缓存"
                    snapshot == null && rates.loading -> "正在取得当前汇率…"
                    else -> null
                }
                status?.let { Text(it, color = Amber, modifier = Modifier.testTag("exchangeRateStatus")) }
            }
        }
    }
    val chooseFrom = pickingFrom
    if (chooseFrom != null && snapshot != null && visible) {
        ExchangeCurrencyPicker(snapshot.rates.keys, onClose = { pickingFrom = null }) { code ->
            if (chooseFrom) from = code else to = code
            pickingFrom = null
        }
    }
}

@Composable
private fun ExchangeCurrencyPicker(codes: Set<String>, onClose: () -> Unit, onSelect: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = codes.filter { it.contains(query.trim(), ignoreCase = true) || currencyName(it).contains(query.trim()) }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("选择币种", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(query, { query = it.take(40) }, modifier = Modifier.fillMaxWidth().testTag("exchangeRateCurrencySearch"),
                    label = { Text("币种或代码") }, singleLine = true)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    if (filtered.isEmpty()) item { Text("找不到匹配币种", Modifier.padding(vertical = 16.dp)) }
                    items(filtered, key = { it }) { code ->
                        ListItem(headlineContent = { Text(currencyName(code)) }, trailingContent = { Text(code) },
                            modifier = Modifier.testTag("exchangeRateCurrency-$code").clickable { onSelect(code) })
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("取消") }
            }
        }
    }
}
