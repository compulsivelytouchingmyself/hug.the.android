package com.example.network

import com.example.model.ComponentField
import com.example.model.ComponentKind
import com.example.model.EndpointManifest
import com.example.model.ZeroGpuDecorator

data class StaticSourceAnalysis(
    val decorators: List<ZeroGpuDecorator>,
    val declaredComponents: Map<String, ComponentField>,
    val provisionalEndpoints: List<EndpointManifest>,
    val detectedImportsZeroGpu: Boolean
)

object PythonSourceAnalyzer {

    private val gpuDecoratorStartRegex = Regex("""^\s*@(?:spaces\.)?GPU\b(.*)$""")
    private val defFunctionRegex = Regex("""^\s*(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""")
    private val componentAssignRegex = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*gr\.([A-Za-z0-9_]+)\s*\((.*)""")
    private val eventWireRegex = Regex("""\.(click|submit|change|then)\s*\(([^;]{1,600})\)""", RegexOption.DOT_MATCHES_ALL)
    private val grInterfaceRegex = Regex("""gr\.(?:Chat)?Interface\s*\(([^;]{1,800})\)""", RegexOption.DOT_MATCHES_ALL)

    fun analyze(sourceCode: String, fileName: String = "app.py"): StaticSourceAnalysis {
        val lines = sourceCode.lines()
        val decorators = extractZeroGpuDecorators(lines)
        val components = extractComponents(lines)
        val endpoints = extractProvisionalEndpoints(sourceCode, components, decorators, fileName)
        val importsZeroGpu = sourceCode.contains("import spaces") || sourceCode.contains("from spaces")

        return StaticSourceAnalysis(
            decorators = decorators,
            declaredComponents = components,
            provisionalEndpoints = endpoints,
            detectedImportsZeroGpu = importsZeroGpu || decorators.isNotEmpty()
        )
    }

    fun extractZeroGpuDecorators(lines: List<String>): List<ZeroGpuDecorator> {
        val results = mutableListOf<ZeroGpuDecorator>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val match = gpuDecoratorStartRegex.find(line)
            if (match != null) {
                val startLine = i + 1
                val sb = StringBuilder(line.trim())
                var openParens = countChar(line, '(') - countChar(line, ')')
                var j = i + 1
                while (openParens > 0 && j < lines.size && j - i < 12) {
                    val nextLine = lines[j].trim()
                    sb.append(" ").append(nextLine)
                    openParens += countChar(nextLine, '(') - countChar(nextLine, ')')
                    j++
                }
                val rawDec = sb.toString()

                // Look ahead up to 10 lines for the decorated function def
                var fnName = "unknown_fn"
                var k = j
                while (k < lines.size && k < j + 10) {
                    val trimmed = lines[k].trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("@")) {
                        k++
                        continue
                    }
                    val fnMatch = defFunctionRegex.find(lines[k])
                    if (fnMatch != null) {
                        fnName = fnMatch.groupValues[1]
                    }
                    break
                }

                val parsed = parseGpuDecoratorArgs(rawDec, fnName, startLine)
                results.add(parsed)
                i = j
            } else {
                i++
            }
        }
        return results
    }

    private fun parseGpuDecoratorArgs(raw: String, fnName: String, lineNumber: Int): ZeroGpuDecorator {
        val parenStart = raw.indexOf('(')
        val parenEnd = raw.lastIndexOf(')')
        if (parenStart < 0 || parenEnd <= parenStart) {
            // Bare @spaces.GPU defaults to 60s on Hugging Face ZeroGPU
            return ZeroGpuDecorator(
                functionName = fnName,
                lineNumber = lineNumber,
                declaredDurationSeconds = 60,
                isDynamicDuration = false,
                allocationSize = "default",
                rawDecorator = raw
            )
        }
        val inside = raw.substring(parenStart + 1, parenEnd).trim()
        val durationRaw = extractKeywordArg(inside, "duration")
        val sizeRaw = extractKeywordArg(inside, "size")?.trim('"', '\'')

        var declaredSeconds: Int? = 60
        var isDynamic = false
        var dynamicExpr: String? = null

        if (durationRaw != null) {
            val cleaned = durationRaw.trim()
            val asInt = cleaned.toIntOrNull()
            if (asInt != null) {
                declaredSeconds = asInt
            } else if (cleaned == "None") {
                declaredSeconds = null
            } else {
                // Dynamic duration callable, lambda, variable, or expression
                declaredSeconds = null
                isDynamic = true
                dynamicExpr = cleaned
            }
        }

        return ZeroGpuDecorator(
            functionName = fnName,
            lineNumber = lineNumber,
            declaredDurationSeconds = declaredSeconds,
            isDynamicDuration = isDynamic,
            dynamicDurationExpression = dynamicExpr,
            allocationSize = sizeRaw,
            rawDecorator = raw
        )
    }

    private fun extractComponents(lines: List<String>): Map<String, ComponentField> {
        val map = linkedMapOf<String, ComponentField>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val match = componentAssignRegex.find(line)
            if (match != null) {
                val varName = match.groupValues[1]
                val grClass = match.groupValues[2]
                if (grClass in setOf("Blocks", "Row", "Column", "Tab", "TabItem", "Accordion", "Group", "Box", "Button", "Examples", "Interface", "ChatInterface")) {
                    i++
                    continue
                }
                val sb = StringBuilder(match.groupValues[3])
                var balance = 1 + countChar(match.groupValues[3], '(') - countChar(match.groupValues[3], ')')
                var j = i + 1
                while (balance > 0 && j < lines.size && j - i < 15) {
                    val nextLine = lines[j].trim()
                    sb.append(" ").append(nextLine)
                    balance += countChar(nextLine, '(') - countChar(nextLine, ')')
                    j++
                }
                val argsText = sb.toString().substringBeforeLast(')')
                val gradioType = grClass.lowercase()
                val kind = mapGradioTypeToKind(gradioType, "")
                val label = extractQuotedArg(argsText, "label") ?: varName.replace('_', ' ').replaceFirstChar { it.uppercase() }
                val placeholder = extractQuotedArg(argsText, "placeholder") ?: ""
                val defaultVal = extractKeywordArg(argsText, "value")
                val min = extractKeywordArg(argsText, "minimum")?.toDoubleOrNull()
                val max = extractKeywordArg(argsText, "maximum")?.toDoubleOrNull()
                val step = extractKeywordArg(argsText, "step")?.toDoubleOrNull()
                val linesCount = extractKeywordArg(argsText, "lines")?.toIntOrNull() ?: 1
                val multiSelect = extractKeywordArg(argsText, "multiselect")?.equals("True", ignoreCase = true) == true
                val choices = extractListStrings(extractKeywordArg(argsText, "choices"))

                map[varName] = ComponentField(
                    id = "src_$varName",
                    parameterName = varName,
                    label = label,
                    gradioType = gradioType,
                    apiDataType = inferApiType(kind),
                    kind = kind,
                    defaultValueJson = cleanPythonLiteralToJson(defaultVal),
                    min = min,
                    max = max,
                    step = step,
                    choices = choices,
                    multiSelect = multiSelect,
                    lines = linesCount,
                    placeholder = placeholder,
                    isHiddenState = kind == ComponentKind.STATE
                )
                i = j
            } else {
                i++
            }
        }
        return map
    }

    private fun extractProvisionalEndpoints(
        sourceCode: String,
        components: Map<String, ComponentField>,
        decorators: List<ZeroGpuDecorator>,
        fileName: String
    ): List<EndpointManifest> {
        val endpoints = mutableListOf<EndpointManifest>()
        val decByFn = decorators.associateBy { it.functionName }

        // Check event wiring (.click, .submit)
        var idx = 0
        eventWireRegex.findAll(sourceCode).forEach { match ->
            val args = match.groupValues[2]
            val fnName = extractKeywordArg(args, "fn")?.trim()
            val apiName = extractQuotedArg(args, "api_name")
            val inputNames = extractVarList(extractKeywordArg(args, "inputs"))
            val outputNames = extractVarList(extractKeywordArg(args, "outputs"))

            if (fnName != null || apiName != null || inputNames.isNotEmpty()) {
                val inputFields = inputNames.mapIndexed { pos, v ->
                    components[v] ?: ComponentField(
                        id = "src_in_${idx}_$pos",
                        parameterName = v,
                        label = v.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        gradioType = "textbox",
                        apiDataType = "string",
                        kind = ComponentKind.TEXTBOX
                    )
                }
                val outputFields = outputNames.mapIndexed { pos, v ->
                    components[v] ?: ComponentField(
                        id = "src_out_${idx}_$pos",
                        parameterName = v,
                        label = v.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        gradioType = "textbox",
                        apiDataType = "string",
                        kind = ComponentKind.TEXTBOX
                    )
                }
                val epLabel = apiName?.trimStart('/') ?: fnName ?: "action_$idx"
                endpoints.add(
                    EndpointManifest(
                        id = "provisional_$idx",
                        apiName = apiName?.let { if (it.startsWith("/")) it else "/$it" } ?: fnName?.let { "/$it" },
                        fnIndex = idx,
                        label = epLabel,
                        description = "Provisional endpoint discovered statically from $fileName",
                        inputs = inputFields,
                        outputs = outputFields,
                        zeroGpuDecorator = fnName?.let { decByFn[it] },
                        provenanceSource = "repo_source"
                    )
                )
                idx++
            }
        }

        // Check gr.Interface / gr.ChatInterface if no Blocks click handlers found
        if (endpoints.isEmpty()) {
            grInterfaceRegex.find(sourceCode)?.let { match ->
                val args = match.groupValues[1]
                val fnName = extractKeywordArg(args, "fn")?.trim() ?: "predict"
                val apiName = extractQuotedArg(args, "api_name") ?: "/predict"
                val inputsList = extractVarList(extractKeywordArg(args, "inputs"))
                val outputsList = extractVarList(extractKeywordArg(args, "outputs"))
                val inputFields = inputsList.mapIndexed { pos, v ->
                    components[v] ?: guessComponentFromToken(v, pos)
                }
                val outputFields = outputsList.mapIndexed { pos, v ->
                    components[v] ?: guessComponentFromToken(v, pos)
                }
                endpoints.add(
                    EndpointManifest(
                        id = "provisional_interface",
                        apiName = apiName,
                        fnIndex = 0,
                        label = apiName.trimStart('/'),
                        description = "Provisional gr.Interface endpoint from $fileName",
                        inputs = inputFields,
                        outputs = outputFields,
                        zeroGpuDecorator = decByFn[fnName],
                        provenanceSource = "repo_source"
                    )
                )
            }
        }

        // Final fallback: synthesize provisional endpoint from declared components if any exist
        if (endpoints.isEmpty() && components.isNotEmpty()) {
            endpoints.add(
                EndpointManifest(
                    id = "provisional_default",
                    apiName = "/predict",
                    fnIndex = 0,
                    label = "predict",
                    description = "Provisional controls inferred from $fileName while Space initializes",
                    inputs = components.values.filterNot { it.isHiddenState }.take(12),
                    outputs = listOf(
                        ComponentField(
                            id = "provisional_out",
                            parameterName = "output",
                            label = "Output",
                            gradioType = "json",
                            apiDataType = "any",
                            kind = ComponentKind.UNKNOWN
                        )
                    ),
                    zeroGpuDecorator = decorators.firstOrNull(),
                    provenanceSource = "repo_source"
                )
            )
        }

        return endpoints
    }

    private fun guessComponentFromToken(token: String, index: Int): ComponentField {
        val clean = token.trim('"', '\'', ' ').lowercase()
        val kind = mapGradioTypeToKind(clean, clean)
        return ComponentField(
            id = "src_guess_$index",
            parameterName = "param_$index",
            label = clean.replaceFirstChar { it.uppercase() }.ifBlank { "Input $index" },
            gradioType = clean.ifBlank { "textbox" },
            apiDataType = inferApiType(kind),
            kind = kind
        )
    }

    fun mapGradioTypeToKind(gradioType: String, apiDataType: String): ComponentKind {
        val g = gradioType.lowercase().trim()
        val a = apiDataType.lowercase().trim()
        return when {
            g.contains("state") -> ComponentKind.STATE
            g.contains("multimodal") -> ComponentKind.MULTIMODAL
            g.contains("chatbot") -> ComponentKind.CHATBOT
            g.contains("gallery") -> ComponentKind.GALLERY
            g.contains("image") || g.contains("paint") || g.contains("sketch") -> ComponentKind.IMAGE
            g.contains("audio") || g.contains("microphone") -> ComponentKind.AUDIO
            g.contains("video") -> ComponentKind.VIDEO
            g.contains("file") || g.contains("uploadbutton") || g.contains("downloadbutton") || g.contains("model3d") -> ComponentKind.FILE
            g.contains("slider") -> ComponentKind.SLIDER
            g.contains("number") -> ComponentKind.NUMBER
            g.contains("checkboxgroup") -> ComponentKind.CHECKBOX_GROUP
            g.contains("checkbox") -> ComponentKind.CHECKBOX
            g.contains("radio") -> ComponentKind.RADIO
            g.contains("dropdown") -> ComponentKind.DROPDOWN
            g.contains("dataframe") || g.contains("dataset") -> ComponentKind.DATAFRAME
            g.contains("colorpicker") -> ComponentKind.COLOR_PICKER
            g.contains("code") -> ComponentKind.CODE
            g.contains("markdown") -> ComponentKind.MARKDOWN
            g.contains("html") -> ComponentKind.HTML
            g.contains("json") -> ComponentKind.JSON
            g.contains("textbox") || g.contains("text") || g.contains("label") -> ComponentKind.TEXTBOX
            // Fallback to API data type
            a == "boolean" || a == "bool" -> ComponentKind.CHECKBOX
            a == "number" || a == "integer" || a == "float" || a == "int" -> ComponentKind.NUMBER
            a == "string" || a == "str" -> ComponentKind.TEXTBOX
            a.contains("filepath") || a.contains("filedata") -> ComponentKind.FILE
            a.contains("object") || a.contains("dict") || a.contains("list") || a.contains("array") -> ComponentKind.JSON
            else -> ComponentKind.UNKNOWN
        }
    }

    private fun inferApiType(kind: ComponentKind): String = when (kind) {
        ComponentKind.TEXTBOX, ComponentKind.CODE, ComponentKind.MARKDOWN, ComponentKind.HTML, ComponentKind.COLOR_PICKER, ComponentKind.RADIO -> "string"
        ComponentKind.SLIDER, ComponentKind.NUMBER -> "number"
        ComponentKind.CHECKBOX -> "boolean"
        ComponentKind.CHECKBOX_GROUP -> "array"
        ComponentKind.IMAGE, ComponentKind.AUDIO, ComponentKind.VIDEO, ComponentKind.FILE -> "filepath"
        else -> "any"
    }

    private fun extractKeywordArg(args: String, key: String): String? {
        val pattern = Regex("""\b$key\s*=\s*""")
        val m = pattern.find(args) ?: return null
        var idx = m.range.last + 1
        var depthParen = 0
        var depthBracket = 0
        var depthBrace = 0
        var inQuote: Char? = null
        val sb = StringBuilder()
        while (idx < args.length) {
            val c = args[idx]
            if (inQuote != null) {
                sb.append(c)
                if (c == inQuote && (idx == 0 || args[idx - 1] != '\\')) inQuote = null
            } else {
                when (c) {
                    '"', '\'' -> { inQuote = c; sb.append(c) }
                    '(' -> { depthParen++; sb.append(c) }
                    ')' -> {
                        if (depthParen == 0) break
                        depthParen--; sb.append(c)
                    }
                    '[' -> { depthBracket++; sb.append(c) }
                    ']' -> {
                        if (depthBracket == 0) break
                        depthBracket--; sb.append(c)
                    }
                    '{' -> { depthBrace++; sb.append(c) }
                    '}' -> {
                        if (depthBrace == 0) break
                        depthBrace--; sb.append(c)
                    }
                    ',' -> {
                        if (depthParen == 0 && depthBracket == 0 && depthBrace == 0) break
                        sb.append(c)
                    }
                    else -> sb.append(c)
                }
            }
            idx++
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun extractQuotedArg(args: String, key: String): String? {
        val raw = extractKeywordArg(args, key) ?: return null
        if ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'"))) {
            return raw.substring(1, raw.length - 1)
        }
        return null
    }

    private fun extractListStrings(raw: String?): List<String> {
        if (raw == null) return emptyList()
        val inner = raw.trim().removePrefix("[").removeSuffix("]")
        return Regex("""["']([^"']+)["']""").findAll(inner).map { it.groupValues[1] }.toList()
    }

    private fun extractVarList(raw: String?): List<String> {
        if (raw == null) return emptyList()
        val trimmed = raw.trim()
        if (trimmed == "None" || trimmed == "[]") return emptyList()
        val inner = trimmed.removePrefix("[").removeSuffix("]")
        return inner.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("gr.") }
    }

    private fun cleanPythonLiteralToJson(raw: String?): String? {
        if (raw == null) return null
        val t = raw.trim()
        return when {
            t == "True" -> "true"
            t == "False" -> "false"
            t == "None" -> null
            t.toDoubleOrNull() != null -> t
            (t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")) -> {
                val inner = t.substring(1, t.length - 1).replace("\"", "\\\"")
                "\"$inner\""
            }
            else -> null
        }
    }

    private fun countChar(s: String, target: Char): Int = s.count { it == target }
}
