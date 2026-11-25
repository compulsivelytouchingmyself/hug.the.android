package com.example.network

import com.example.model.SpaceIdentifier
import java.net.URI

data class ReadmeMetadata(
    val title: String? = null,
    val emoji: String? = null,
    val sdk: String? = null,
    val sdkVersion: String? = null,
    val appFile: String? = null,
    val suggestedHardware: String? = null,
    val shortDescription: String? = null
)

object SpaceIdentifierParser {

    fun parse(rawInput: String): SpaceIdentifier? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        // Handle direct *.hf.space URLs or hostnames
        val normalizedUrl = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains(".hf.space", ignoreCase = true) ||
                trimmed.contains("huggingface.co/", ignoreCase = true) ||
                trimmed.contains("hf.co/", ignoreCase = true) -> "https://$trimmed"
            else -> null
        }

        if (normalizedUrl != null) {
            return parseUrl(normalizedUrl)
        }

        // Handle author/repo identifier
        val cleaned = trimmed.trim('/').removePrefix("spaces/")
        val parts = cleaned.split("/").filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val author = parts[0]
            val repo = parts[1]
            if (isValidRepoSegment(author) && isValidRepoSegment(repo)) {
                val subdomain = computeSubdomain(author, repo)
                return SpaceIdentifier(
                    spaceId = "$author/$repo",
                    author = author,
                    repoName = repo,
                    subdomain = subdomain
                )
            }
        }
        return null
    }

    private fun parseUrl(urlString: String): SpaceIdentifier? {
        return try {
            val uri = URI(urlString)
            val host = uri.host?.lowercase() ?: return null
            val pathSegments = (uri.path ?: "").split("/").filter { it.isNotBlank() }

            if (host == "huggingface.co" || host == "www.huggingface.co" || host == "hf.co") {
                val startIndex = if (pathSegments.firstOrNull()?.equals("spaces", ignoreCase = true) == true) 1 else 0
                if (pathSegments.size >= startIndex + 2) {
                    val author = pathSegments[startIndex]
                    val repo = pathSegments[startIndex + 1]
                    if (isValidRepoSegment(author) && isValidRepoSegment(repo)) {
                        return SpaceIdentifier(
                            spaceId = "$author/$repo",
                            author = author,
                            repoName = repo,
                            subdomain = computeSubdomain(author, repo)
                        )
                    }
                }
            }

            if (host.endsWith(".hf.space")) {
                val subdomain = host.removeSuffix(".hf.space")
                val dashIdx = subdomain.indexOf('-')
                val author = if (dashIdx > 0) subdomain.substring(0, dashIdx) else subdomain
                val repo = if (dashIdx > 0 && dashIdx < subdomain.length - 1) subdomain.substring(dashIdx + 1) else subdomain
                return SpaceIdentifier(
                    spaceId = "$author/$repo",
                    author = author,
                    repoName = repo,
                    subdomain = subdomain,
                    directHostUrl = "https://$host"
                )
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun computeSubdomain(author: String, repo: String): String {
        val raw = "$author-$repo".lowercase()
        return raw.replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    private fun isValidRepoSegment(segment: String): Boolean {
        return segment.matches(Regex("^[A-Za-z0-9._-]+$"))
    }

    fun parseReadmeFrontmatter(readmeText: String): ReadmeMetadata {
        val lines = readmeText.lines()
        if (lines.isEmpty() || lines.first().trim() != "---") return ReadmeMetadata()

        val kv = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.trim() == "---") break
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0 && !line.startsWith(" ") && !line.startsWith("\t")) {
                val key = line.substring(0, colonIdx).trim().lowercase()
                val value = line.substring(colonIdx + 1).trim().trim('"', '\'')
                if (value.isNotEmpty()) kv[key] = value
            }
        }
        return ReadmeMetadata(
            title = kv["title"],
            emoji = kv["emoji"],
            sdk = kv["sdk"],
            sdkVersion = kv["sdk_version"],
            appFile = kv["app_file"],
            suggestedHardware = kv["suggested_hardware"],
            shortDescription = kv["short_description"]
        )
    }
}
