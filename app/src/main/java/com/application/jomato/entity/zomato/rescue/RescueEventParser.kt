package com.application.jomato.entity.zomato.rescue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Best-effort extraction of deal details from a food-rescue MQTT payload.
 *
 * We intentionally do NOT call `food-rescue/create-cart` to enrich every event: that
 * endpoint creates a rescue cart on the user's account, so calling it for deals the
 * user never opened could litter their Zomato cart. If the payload carries the details
 * we use them; otherwise the history shows the deal without a price instead of guessing.
 */
object RescueEventParser {

    private const val MAX_DEPTH = 8

    private val NAME_KEYS = setOf(
        "restaurant_name", "res_name", "resname", "store_name", "outlet_name", "vendor_name"
    )
    private val ID_KEYS = setOf("res_id", "restaurant_id", "store_id", "vendor_id")
    private val CATALOG_KEYS = setOf(
        "catalog_total_cost", "catalog_total", "cart_total", "total_cost", "mrp"
    )
    private val FINAL_KEYS = setOf(
        "cart_final_cost", "final_cost", "rescue_price", "discounted_price", "offer_price"
    )
    private val ITEM_LIST_KEYS = setOf("items", "item_list")
    private val ITEM_NAME_KEYS = setOf("name", "item_name", "title")
    private val DEEPLINK_KEYS = setOf("deeplink", "deep_link", "action_url", "button_url")

    data class Parsed(
        val restaurantName: String? = null,
        val restaurantId: String? = null,
        val catalogTotal: Double? = null,
        val finalCost: Double? = null,
        val items: List<String> = emptyList(),
        val deeplink: String? = null,
        val eventTime: Long? = null
    )

    fun parse(root: JsonObject): Parsed {
        val strings = mutableMapOf<String, String>()
        val numbers = mutableMapOf<String, Double>()
        var items: List<String> = emptyList()
        var deeplink: String? = null

        fun walk(element: JsonElement, depth: Int) {
            if (depth > MAX_DEPTH) return
            when (element) {
                is JsonObject -> {
                    for ((rawKey, value) in element) {
                        val key = rawKey.lowercase()
                        if (value is JsonPrimitive) {
                            if (value.isString) {
                                val text = value.content
                                if (key in NAME_KEYS) strings.putIfAbsent(key, text)
                                if (key in ID_KEYS) strings.putIfAbsent(key, text)
                                if (key in DEEPLINK_KEYS && isZomatoLink(text)) {
                                    if (deeplink == null) deeplink = text
                                }
                            } else {
                                val number = value.doubleOrNull
                                if (key in CATALOG_KEYS || key in FINAL_KEYS) {
                                    if (number != null && number > 0) numbers.putIfAbsent(key, number)
                                }
                            }
                        } else if (value is JsonArray) {
                            if (key in ITEM_LIST_KEYS && items.isEmpty()) {
                                items = parseItems(value)
                            }
                            value.forEach { walk(it, depth + 1) }
                        } else {
                            walk(value, depth + 1)
                        }
                    }
                }
                is JsonArray -> element.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }

        walk(root, 0)

        fun first(keys: Set<String>): String? = keys.firstNotNullOfOrNull { strings[it] }
        fun number(keys: Set<String>): Double? = keys.firstNotNullOfOrNull { numbers[it] }

        return Parsed(
            restaurantName = first(NAME_KEYS)?.takeIf { it.isNotBlank() },
            restaurantId = first(ID_KEYS)?.takeIf { it.isNotBlank() },
            catalogTotal = number(CATALOG_KEYS),
            finalCost = number(FINAL_KEYS),
            items = items,
            deeplink = deeplink,
            eventTime = findTimestamp(root)
        )
    }

    private fun parseItems(array: JsonArray): List<String> {
        val out = mutableListOf<String>()
        for (element in array) {
            when (element) {
                is JsonPrimitive -> if (element.isString && element.content.isNotBlank()) {
                    out.add(element.content.trim())
                }
                is JsonObject -> {
                    val name = ITEM_NAME_KEYS.firstNotNullOfOrNull { key ->
                        (element[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    }
                    if (!name.isNullOrBlank()) out.add(name.trim())
                }
                else -> Unit
            }
            if (out.size >= 12) break
        }
        return out
    }

    private fun isZomatoLink(url: String): Boolean =
        url.startsWith("zomato://") || url.contains("zomato.com", ignoreCase = true)

    /** Top-level event timestamp, seconds or millis. */
    private fun findTimestamp(root: JsonObject): Long? {
        val primitive = root["timestamp"] as? JsonPrimitive ?: return null
        val raw = primitive.longOrNull ?: primitive.content.toLongOrNull() ?: return null
        return if (raw < 10_000_000_000L) raw * 1000 else raw
    }
}
