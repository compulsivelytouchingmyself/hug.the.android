package com.example.data

import com.example.model.FieldCustomization
import com.example.model.FieldValue
import com.example.model.SpaceProfile
import com.example.security.TokenRedactor
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes user Space profiles and generation presets strictly separated from SpaceManifest.
 * Explicitly excludes all credentials/tokens during export and sanitizes on import.
 */
object ProfileCodec {

    fun encodeProfile(profile: SpaceProfile): String {
        val root = JSONObject()
        root.put("schema", "spacedeck_profile_v1")
        root.put("credentialsIncluded", false)
        root.put("spaceId", profile.spaceId)
        root.put("favoriteEndpoints", JSONArray(profile.favoriteEndpoints))
        root.put("updatedAtMs", profile.updatedAtMs)
        val custObj = JSONObject()
        profile.customizations.forEach { (fieldId, c) ->
            custObj.put(fieldId, JSONObject().apply {
                put("fieldId", c.fieldId)
                put("orderIndex", c.orderIndex)
                put("hidden", c.hidden)
                put("locked", c.locked)
                put("advanced", c.advanced)
                c.customDefaultJson?.let { put("customDefaultJson", TokenRedactor.sanitize(it)) }
            })
        }
        root.put("customizations", custObj)
        return root.toString(2)
    }

    fun decodeProfile(json: String, fallbackSpaceId: String = ""): SpaceProfile {
        val root = JSONObject(json)
        val spaceId = root.optString("spaceId", fallbackSpaceId).ifBlank { fallbackSpaceId }
        val favArr = root.optJSONArray("favoriteEndpoints") ?: JSONArray()
        val favs = (0 until favArr.length()).map { favArr.optString(it) }.filter { it.isNotBlank() }
        val custObj = root.optJSONObject("customizations") ?: JSONObject()
        val map = mutableMapOf<String, FieldCustomization>()
        val keys = custObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val c = custObj.optJSONObject(k) ?: continue
            map[k] = FieldCustomization(
                fieldId = c.optString("fieldId", k),
                orderIndex = c.optInt("orderIndex", 0),
                hidden = c.optBoolean("hidden", false),
                locked = c.optBoolean("locked", false),
                advanced = c.optBoolean("advanced", false),
                customDefaultJson = if (c.has("customDefaultJson") && !c.isNull("customDefaultJson")) {
                    c.getString("customDefaultJson")
                } else null
            )
        }
        return SpaceProfile(
            spaceId = spaceId,
            favoriteEndpoints = favs,
            customizations = map,
            updatedAtMs = root.optLong("updatedAtMs", System.currentTimeMillis())
        )
    }

    fun encodeFieldValues(values: Map<String, FieldValue>): String {
        val obj = JSONObject()
        values.forEach { (key, value) ->
            when (value) {
                is FieldValue.Text -> obj.put(key, JSONObject().put("t", "text").put("v", TokenRedactor.sanitize(value.value)))
                is FieldValue.NumberVal -> obj.put(key, JSONObject().put("t", "num").put("v", value.value))
                is FieldValue.BoolVal -> obj.put(key, JSONObject().put("t", "bool").put("v", value.value))
                is FieldValue.MultiSelect -> obj.put(key, JSONObject().put("t", "multi").put("v", JSONArray(value.values)))
                is FieldValue.StructuredJson -> obj.put(key, JSONObject().put("t", "json").put("v", TokenRedactor.sanitize(value.rawJson)))
                is FieldValue.Multimodal -> obj.put(key, JSONObject().put("t", "mm").put("v", TokenRedactor.sanitize(value.text)))
                is FieldValue.MediaRef -> {
                    // Only store local media library reference ID (never credentials)
                    value.mediaAssetId?.let { id ->
                        obj.put(key, JSONObject().put("t", "asset").put("id", id).put("name", value.fileName).put("mime", value.mimeType).put("path", value.localPath ?: ""))
                    }
                }
                FieldValue.Empty -> Unit
            }
        }
        return obj.toString()
    }

    fun decodeFieldValues(json: String): Map<String, FieldValue> {
        val result = mutableMapOf<String, FieldValue>()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return result
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val entry = obj.optJSONObject(k) ?: continue
            when (entry.optString("t")) {
                "text" -> result[k] = FieldValue.Text(entry.optString("v", ""))
                "num" -> result[k] = FieldValue.NumberVal(entry.optDouble("v", 0.0))
                "bool" -> result[k] = FieldValue.BoolVal(entry.optBoolean("v", false))
                "multi" -> {
                    val arr = entry.optJSONArray("v") ?: JSONArray()
                    result[k] = FieldValue.MultiSelect((0 until arr.length()).map { arr.optString(it) })
                }
                "json" -> result[k] = FieldValue.StructuredJson(entry.optString("v", ""))
                "mm" -> result[k] = FieldValue.Multimodal(text = entry.optString("v", ""))
                "asset" -> {
                    val path = entry.optString("path", "").takeIf { it.isNotBlank() }
                    result[k] = FieldValue.MediaRef(
                        localPath = path,
                        fileName = entry.optString("name", "asset"),
                        mimeType = entry.optString("mime", "application/octet-stream"),
                        mediaAssetId = entry.optString("id", "").takeIf { it.isNotBlank() }
                    )
                }
            }
        }
        return result
    }
}
