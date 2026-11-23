package cn.xux.cloudworkbench

import org.json.JSONObject

internal data class OverviewService(val name: String, val state: String) {
    val order: Int get() = when (state) {
        "failed" -> 0
        "inactive", "unknown", "not-found", "maintenance" -> 1
        "active" -> 3
        else -> 2
    }
}

internal data class OverviewIncident(val service: OverviewService, val observedAt: Long)

internal class ServerOverview private constructor(val metrics: JSONObject, val sampledAt: Long,
    val services: List<OverviewService>, val incidents: List<OverviewIncident>?) {
    companion object {
        fun parse(sample: JSONObject): ServerOverview {
            val timestamp = sample.getLong("timestamp")
            require(timestamp > 0)
            sample.getJSONArray("load").getDouble(0)
            sample.getJSONObject("memory").apply { getDouble("used"); getDouble("total") }
            sample.getJSONObject("disk").apply { getDouble("total"); getDouble("available") }
            sample.getJSONObject("swap").getDouble("used")
            sample.getInt("cpus")
            sample.getLong("uptime")
            fun service(entry: JSONObject) = OverviewService(entry.getString("name"), entry.getString("state"))
            val services = sample.getJSONArray("services").objects().map(::service)
            val incidents = sample.optJSONArray("incidents")?.objects()?.map {
                OverviewIncident(service(it), it.getLong("observedAt"))
            }
            val keys = listOf("timestamp", "load", "memory", "disk", "swap", "cpus", "uptime", "services", "incidents")
            val cache = JSONObject().apply { keys.forEach { key -> sample.opt(key)?.let { put(key, it) } } }
            return ServerOverview(cache, timestamp, services, incidents)
        }
    }
}
