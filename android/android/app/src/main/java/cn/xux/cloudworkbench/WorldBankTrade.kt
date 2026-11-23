package cn.xux.cloudworkbench

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

internal data class TradeCountry(val code: String, val name: String, val displayName: String)

internal data class TradeObservation(
    val country: String, val year: Int, val updatedAt: LocalDate?, val stale: Boolean,
    val tradePercent: Double?, val exportsUsd: Double?, val importsUsd: Double?
) {
    val hasData get() = tradePercent != null || exportsUsd != null || importsUsd != null

    companion object {
        fun parse(json: JSONObject): TradeObservation {
            fun value(key: String): Double? {
                require(json.has(key))
                return if (json.isNull(key)) null else json.getDouble(key).also { require(it.isFinite()) }
            }
            val country = json.getString("country")
            val year = json.getInt("year")
            require(country.matches(Regex("[A-Z]{3}")) && year in 1960..LocalDate.now().year)
            require(json.has("updatedAt"))
            val updatedAt = if (json.isNull("updatedAt")) null else LocalDate.parse(json.getString("updatedAt"))
            return TradeObservation(country, year, updatedAt,
                json.getBoolean("stale"), value("tradePercent"), value("exportsUsd"), value("importsUsd"))
        }
    }
}

internal class WorldBankTrade(
    private val prefs: SharedPreferences, private val connection: () -> WorkbenchConnection?
) {
    private val catalogCache = "world-bank-country-catalog"
    private val tradeCache = "world-bank-trade-observation"
    var countries by mutableStateOf(readCache(catalogCache, ::parseCountries).orEmpty()); private set
    var result by mutableStateOf(readCache(tradeCache, TradeObservation::parse)); private set
    var countriesLoading by mutableStateOf(false); private set
    var countriesStale by mutableStateOf(false); private set
    var countriesUnavailable by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var failedQuery by mutableStateOf<Pair<String, Int>?>(null); private set

    private fun <T> readCache(key: String, parse: (JSONObject) -> T): T? =
        prefs.getString(key, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

    private fun parseCountries(json: JSONObject): List<TradeCountry> {
        val rows = json.getJSONArray("countries")
        require(rows.length() in 1..400)
        return rows.objects().map {
            val code = it.getString("code")
            val iso2 = it.getString("iso2")
            val name = it.getString("name")
            require(code.matches(Regex("[A-Z]{3}")) && iso2.matches(Regex("[A-Z]{2}")) && name.isNotBlank())
            val localized = Locale.Builder().setRegion(iso2).build().getDisplayCountry(Locale.SIMPLIFIED_CHINESE)
            TradeCountry(code, name, localized.takeIf { it.isNotBlank() && it != iso2 } ?: name)
        }
    }

    suspend fun loadCountries() {
        val transport = connection() ?: return
        if (countriesLoading) return
        countriesLoading = true
        countriesUnavailable = false
        try {
            val payload = transport.sync("/v1/server-data/world-bank/countries")
            val list = parseCountries(payload)
            if (transport === connection()) {
                countries = list
                countriesStale = payload.getBoolean("stale")
                prefs.edit().putString(catalogCache, payload.toString()).apply()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (transport === connection()) countriesUnavailable = true
        } finally {
            countriesLoading = false
        }
    }

    suspend fun query(country: String, year: Int) {
        val transport = connection() ?: return
        if (loading || countries.none { it.code == country } || year !in 1960..LocalDate.now().year) return
        loading = true
        failedQuery = null
        try {
            val payload = transport.sync("/v1/server-data/world-bank/trade?country=$country&year=$year")
            val response = TradeObservation.parse(payload)
            require(response.country == country && response.year == year)
            if (transport === connection()) {
                result = response
                prefs.edit().putString(tradeCache, payload.toString()).apply()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (transport === connection()) failedQuery = country to year
        } finally {
            loading = false
        }
    }
}
