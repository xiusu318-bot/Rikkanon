package me.rerere.rikkahub.data.secret

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SecretKeyEntry
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 密钥代名词出入站安全阀门：
 * 1. 入站（工具执行前）：仅对实际与外部环境/文件系统交互的工具展开代名词（允许明文落盘供业务脚本/CLI 读取）；
 *    对内部代理通信（`conversation_*`、`recent_chats`）、长期记忆（`memory_*`）、交互提问（`ask_*`）等严禁展开，
 *    确保只有人类用户自己能向模型上下文或记忆中输入真实密钥。
 * 2. 出站（工具结果/进度/元数据/报错返回前）：先解包 JSON 逐字段脱敏再封回，消除 `stdout` / `stderr` / 转义差异；
 *    除静默还原常规回显（原串、URL 变体、URL 编码、ANSI 颜色与 PTY 折行）外，主动拦截 `od -c`、`od -x/-b/-tu1`、
 *    `xxd`、`hexdump -C`、`base64`（任意字节偏移三相位）、字符数组拼接与长密钥切片等探测变形，并附带零泄密的格式诊断信息，
 *    防止模型因排查格式报错而反复换编码拆字节试探。
 */
object SecretKeySanitizer {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private val LOCAL_HOSTS = setOf(
        "localhost",
        "127.0.0.1",
        "0.0.0.0",
        "::1",
        "[::1]",
    )

    private val COMMON_KEY_PREFIXES = listOf(
        "https://",
        "http://",
        "Bearer ",
        "bearer ",
        "sk-ant-api03-",
        "sk-proj-",
        "sk-ant-",
        "github_pat_",
        "glpat-",
        "xoxb-",
        "xoxp-",
        "ghp_",
        "gho_",
        "sk-",
    )

    /**
     * 判断指定工具的入参是否允许将代名词展开为真实密钥。
     * 内部子代理通信、会话检索、记忆读写、向用户提问等工具一旦展开会把明文直接写进对话历史或记忆库绕过脱敏，必须保持代名词原样传递。
     */
    fun shouldExpandToolInput(toolName: String): Boolean {
        if (toolName.startsWith("conversation_")) return false
        if (toolName == "recent_chats") return false
        if (toolName.startsWith("memory_")) return false
        if (toolName == "ask_user" || toolName == "ask_key") return false
        if (toolName == "chart_display" || toolName == "text_to_speech" || toolName == "read_skill") return false
        return true
    }

    fun activeEntries(entries: List<SecretKeyEntry>): List<SecretKeyEntry> =
        entries.filter { it.enabled && it.keyName.isNotBlank() && it.secretValue.isNotEmpty() }

    private data class PreparedTarget(
        val raw: String,
        val base64Cores: List<String>,
        val hexTokens: List<String>,
        val evenLeWords: List<String>,
        val oddLeWords: List<String>,
        val octTokens: List<String>,
        val decTokens: List<String>,
        val odCharTokens: List<String>,
    )

    private data class PreparedEntry(
        val entry: SecretKeyEntry,
        val secret: String,
        val alias: String,
        val marker: String,
        val slashEscaped: String?,
        val trimmed: String?,
        val urlEncoded: String?,
        val urlEncodedPct20: String?,
        val trimmedUrl: String?,
        val trimmedUrlSlashEsc: String?,
        val trimmedUrlEnc: String?,
        val authority: String?,
        val bareHost: String?,
        val targets: List<PreparedTarget>,
        val sliceWindows: List<Pair<String, Int>>,
        val diagnosticNotice: String,
    )

    private val preparedCache = object : LinkedHashMap<Pair<String, String>, PreparedEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, PreparedEntry>): Boolean =
            size > 64
    }

    private fun prepareTarget(target: String): PreparedTarget {
        val bytes = target.toByteArray(StandardCharsets.UTF_8)
        val cores = LinkedHashSet<String>()
        if (bytes.size >= 8) {
            val stdEnc = Base64.getEncoder().withoutPadding()
            val urlEnc = Base64.getUrlEncoder().withoutPadding()
            for (skip in 0..2) {
                if (bytes.size - skip < 6) continue
                val usable = ((bytes.size - skip) / 3) * 3
                if (usable < 6) continue
                val slice = bytes.copyOfRange(skip, skip + usable)
                val s1 = stdEnc.encodeToString(slice)
                val s2 = urlEnc.encodeToString(slice)
                if (s1.length >= 8) cores.add(s1)
                if (s2.length >= 8) cores.add(s2)
            }
        }
        val hexTokens = if (bytes.size >= 6) bytes.map { String.format("%02x", it.toInt() and 0xFF) } else emptyList()
        val evenLeWords = if (bytes.size >= 6) {
            (0 until bytes.size - 1 step 2).map { i ->
                String.format("%02x%02x", bytes[i + 1].toInt() and 0xFF, bytes[i].toInt() and 0xFF)
            }.takeIf { it.size >= 3 }.orEmpty()
        } else emptyList()
        val oddLeWords = if (bytes.size >= 7) {
            (1 until bytes.size - 1 step 2).map { i ->
                String.format("%02x%02x", bytes[i + 1].toInt() and 0xFF, bytes[i].toInt() and 0xFF)
            }.takeIf { it.size >= 3 }.orEmpty()
        } else emptyList()
        val octTokens = if (bytes.size >= 6) bytes.map { String.format("%03o", it.toInt() and 0xFF) } else emptyList()
        val decTokens = if (bytes.size >= 6) bytes.map { (it.toInt() and 0xFF).toString() } else emptyList()
        val odCharTokens = if (target.length >= 6) {
            target.map { ch ->
                when (ch) {
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    '\u0000' -> "\\0"
                    else -> ch.toString()
                }
            }
        } else emptyList()

        return PreparedTarget(
            raw = target,
            base64Cores = cores.toList(),
            hexTokens = hexTokens,
            evenLeWords = evenLeWords,
            oddLeWords = oddLeWords,
            octTokens = octTokens,
            decTokens = decTokens,
            odCharTokens = odCharTokens,
        )
    }

    private fun prepareEntry(entry: SecretKeyEntry): PreparedEntry {
        val cacheKey = entry.keyName to entry.secretValue
        synchronized(preparedCache) {
            preparedCache[cacheKey]?.let { return it }
        }
        val secret = entry.secretValue
        val alias = entry.keyName
        val marker = "[REDACTED:$alias]"
        val slashEscaped = secret.replace("/", "\\/").takeIf { it != secret }
        val trimmed = secret.trim().takeIf { it.length >= 6 && it != secret && !alias.contains(it) }
        val urlEncoded = if (secret.length >= 6) {
            runCatching { URLEncoder.encode(secret, StandardCharsets.UTF_8.name()) }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it != secret }
        } else null
        val urlEncodedPct20 = urlEncoded?.replace("+", "%20")?.takeIf { it != urlEncoded }

        val rawTrimmedUrl = trimmedHttpUrlVariant(secret)
        val validTrimmedUrl = rawTrimmedUrl?.takeIf { it != secret && it.length >= 8 && !alias.contains(it) }
        val trimmedUrlSlashEsc = validTrimmedUrl?.replace("/", "\\/")?.takeIf { it != validTrimmedUrl }
        val trimmedUrlEnc = validTrimmedUrl?.let { u ->
            runCatching { URLEncoder.encode(u, StandardCharsets.UTF_8.name()) }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it != u }
        }
        val authority = rawTrimmedUrl?.let(::extractPrivateUrlAuthority)?.takeIf { !alias.contains(it, ignoreCase = true) }
        val bareHost = authority?.substringBefore(':')?.takeIf {
            it != authority && it.length >= 6 && !alias.contains(it, ignoreCase = true)
        }

        val targetStrings = buildList {
            add(secret)
            if (rawTrimmedUrl != null && rawTrimmedUrl != secret && rawTrimmedUrl.length >= 6) {
                add(rawTrimmedUrl)
            }
        }

        var core = secret.trim()
        for (pfx in COMMON_KEY_PREFIXES) {
            if (core.startsWith(pfx) && core.length - pfx.length >= 8) {
                core = core.removePrefix(pfx)
                break
            }
        }
        val sliceWindows = if (core.length >= 10) {
            val windowSize = if (core.length >= 12) 10 else 8
            buildList(core.length - windowSize + 1) {
                for (w in 0..(core.length - windowSize)) {
                    val sub = core.substring(w, w + windowSize)
                    if (!alias.contains(sub)) {
                        add(sub to secret.indexOf(sub))
                    }
                }
            }
        } else emptyList()

        val prepared = PreparedEntry(
            entry = entry,
            secret = secret,
            alias = alias,
            marker = marker,
            slashEscaped = slashEscaped,
            trimmed = trimmed,
            urlEncoded = urlEncoded,
            urlEncodedPct20 = urlEncodedPct20,
            trimmedUrl = rawTrimmedUrl,
            trimmedUrlSlashEsc = trimmedUrlSlashEsc,
            trimmedUrlEnc = trimmedUrlEnc,
            authority = authority,
            bareHost = bareHost,
            targets = targetStrings.map(::prepareTarget),
            sliceWindows = sliceWindows,
            diagnosticNotice = buildSafeDiagnosticNotice(entry),
        )
        synchronized(preparedCache) {
            preparedCache[cacheKey] = prepared
        }
        return prepared
    }

    private fun prepareEntries(entries: List<SecretKeyEntry>): List<PreparedEntry> =
        activeEntries(entries)
            .sortedByDescending { it.secretValue.length }
            .map(::prepareEntry)

    /**
     * 对整块工具输出载荷（可能是 JSON 如 `{"stdout": "...", "stderr": "..."}` 或纯文本）执行脱敏。
     * 若为合法 JSON，先对内部每个字符串值按解码后的真实字符单独脱敏，再序列化回 JSON 并做外层兜底，
     * 确保 `stderr`、引号/斜杠/换行转义与多行 `od`/`hexdump` 输出均与纯文本完全一致地被拦截。
     */
    fun redactToolPayload(payload: String, entries: List<SecretKeyEntry>): String {
        val prepared = prepareEntries(entries)
        if (payload.isEmpty() || prepared.isEmpty()) return payload
        val trimmed = payload.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsed = runCatching { lenientJson.parseToJsonElement(payload) }.getOrNull()
            if (parsed != null) {
                val redactedJson = redactJsonElementPrepared(parsed, prepared)
                val encoded = lenientJson.encodeToString(JsonElement.serializer(), redactedJson)
                return silentReplaceDirectVariants(encoded, prepared)
            }
        }
        return redactTextPrepared(payload, prepared)
    }

    /** 递归脱敏任意 [JsonElement]（含工具 `metadata` 中的 `DiffMetadata` 等）。 */
    fun redactJsonElement(element: JsonElement, entries: List<SecretKeyEntry>): JsonElement {
        val prepared = prepareEntries(entries)
        if (prepared.isEmpty()) return element
        return redactJsonElementPrepared(element, prepared)
    }

    private fun redactJsonElementPrepared(element: JsonElement, prepared: List<PreparedEntry>): JsonElement =
        when (element) {
            is JsonPrimitive -> if (element.isString) {
                JsonPrimitive(redactTextPrepared(element.content, prepared))
            } else {
                element
            }
            is JsonObject -> JsonObject(element.mapValues { (_, v) -> redactJsonElementPrepared(v, prepared) })
            is JsonArray -> JsonArray(element.map { redactJsonElementPrepared(it, prepared) })
        }

    /** 脱敏单个 [UIMessagePart]（同时覆盖正文与 `metadata` 中的文件编辑 Diff 等）。 */
    fun redactMessagePart(part: UIMessagePart, entries: List<SecretKeyEntry>): UIMessagePart {
        val prepared = prepareEntries(entries)
        if (prepared.isEmpty()) return part
        return redactMessagePartPrepared(part, prepared)
    }

    private fun redactMessagePartPrepared(part: UIMessagePart, prepared: List<PreparedEntry>): UIMessagePart {
        val safeMeta = part.metadata?.let { redactJsonElementPrepared(it, prepared) as? JsonObject }
        return when (part) {
            is UIMessagePart.Text -> part.copy(
                text = redactToolPayload(part.text, prepared.map { it.entry }),
                metadata = safeMeta,
            )
            is UIMessagePart.Reasoning -> part.copy(
                reasoning = redactTextPrepared(part.reasoning, prepared),
                metadata = safeMeta,
            )
            else -> if (safeMeta != part.metadata) {
                when (part) {
                    is UIMessagePart.Image -> part.copy(metadata = safeMeta)
                    is UIMessagePart.Document -> part.copy(metadata = safeMeta)
                    is UIMessagePart.Audio -> part.copy(metadata = safeMeta)
                    is UIMessagePart.Video -> part.copy(metadata = safeMeta)
                    is UIMessagePart.Tool -> part.copy(
                        output = part.output.map { redactMessagePartPrepared(it, prepared) },
                        metadata = safeMeta,
                    )
                    else -> part
                }
            } else {
                part
            }
        }
    }

    /**
     * 对单段原始文本执行完整脱敏：
     * 1. 常规连续/URL/ANSI/PTY 折行形态 → 静默替换回 `keyName`；
     * 2. 拆字节（`od -c`、`' '.join`、`list()`）、Hex/八进制/十进制字节流（`xxd`、`hexdump`、`od`、`ord`）、
     *    三相位 Base64、高熵连续切片（`key[:10]`）→ 替换为 `[REDACTED:keyName]` 并附加零泄密安全格式诊断。
     */
    fun redactText(output: String, entries: List<SecretKeyEntry>): String {
        val prepared = prepareEntries(entries)
        if (output.isEmpty() || prepared.isEmpty()) return output
        return redactTextPrepared(output, prepared)
    }

    private fun redactTextPrepared(output: String, prepared: List<PreparedEntry>): String {
        var result = silentReplaceDirectVariants(output, prepared)
        val probed = LinkedHashSet<PreparedEntry>()

        for (p in prepared) {
            val alias = p.alias
            val marker = p.marker

            // 2a. Base64 / Base64URL 三相位核心子串（先扫长编码块，避免内部碰巧触发短规则残留头尾）
            for (target in p.targets) {
                if (target.base64Cores.isNotEmpty()) {
                    val b64Replaced = replaceBase64Phases(result, target.base64Cores, alias, marker)
                    if (b64Replaced != result) {
                        result = b64Replaced
                        probed.add(p)
                    }
                }
            }

            // 2b. 字节编码流：单字节 Hex（xxd / hexdump / od -t x1 / \xNN）、od -x 16位小端双字节 Hex、八进制（od -b）、十进制（[ord(c)] / od -tu1）
            for (target in p.targets) {
                if (target.hexTokens.isNotEmpty()) {
                    val r1 = replaceEncodedTokenStream(result, target.hexTokens, TokenStreamMode.HEX_BYTE, alias, marker)
                    if (r1 != result) {
                        result = r1
                        probed.add(p)
                    }
                }
                if (target.evenLeWords.isNotEmpty()) {
                    val r2 = replaceEncodedTokenStream(result, target.evenLeWords, TokenStreamMode.HEX_WORD_LE, alias, marker)
                    if (r2 != result) {
                        result = r2
                        probed.add(p)
                    }
                }
                if (target.oddLeWords.isNotEmpty()) {
                    val r3 = replaceEncodedTokenStream(result, target.oddLeWords, TokenStreamMode.HEX_WORD_LE, alias, marker)
                    if (r3 != result) {
                        result = r3
                        probed.add(p)
                    }
                }
                if (target.octTokens.isNotEmpty()) {
                    val r4 = replaceEncodedTokenStream(result, target.octTokens, TokenStreamMode.NUMERIC, alias, marker)
                    if (r4 != result) {
                        result = r4
                        probed.add(p)
                    }
                }
                if (target.decTokens.isNotEmpty()) {
                    val r5 = replaceEncodedTokenStream(result, target.decTokens, TokenStreamMode.NUMERIC, alias, marker)
                    if (r5 != result) {
                        result = r5
                        probed.add(p)
                    }
                }
            }

            // 2c. 有界分隔符拆字符流（od -c、' '.join、'-'.join、list(s)、repr、hexdump -C 右侧字符栏）
            for (target in p.targets) {
                if (target.odCharTokens.isNotEmpty()) {
                    val rChar = replaceSeparatedCharStream(result, target.odCharTokens, alias, marker)
                    if (rChar != result) {
                        result = rChar
                        probed.add(p)
                    }
                }
            }

            // 2d. 长密钥高熵连续切片（如 key[:10]、key[-12:]、hexdump/xxd 跨行截断的半截明文栏）
            if (p.sliceWindows.isNotEmpty()) {
                val rSlice = replaceSecretSlices(result, p.secret, p.sliceWindows, alias, marker)
                if (rSlice != result) {
                    result = rSlice
                    probed.add(p)
                }
            }
        }

        if (probed.isNotEmpty()) {
            val diagLines = probed.joinToString("\n") { it.diagnosticNotice }
            result = if (result.endsWith("\n")) "$result$diagLines\n" else "$result\n$diagLines"
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Pass 1: 静默常规替换（精确串、JSON 斜杠转义、URL 编码、URL 变体与私有域名、PTY 折行与 ANSI 颜色码）
    // -------------------------------------------------------------------------

    private fun silentReplaceDirectVariants(input: String, prepared: List<PreparedEntry>): String {
        var out = input
        for (p in prepared) {
            val secret = p.secret
            val alias = p.alias
            val marker = p.marker
            if (out.contains(secret)) out = out.replace(secret, alias)

            p.slashEscaped?.let { if (out.contains(it)) out = out.replace(it, alias) }
            p.trimmed?.let { if (out.contains(it)) out = out.replace(it, alias) }
            p.urlEncoded?.let { if (out.contains(it, ignoreCase = true)) out = out.replace(it, alias, ignoreCase = true) }
            p.urlEncodedPct20?.let { if (out.contains(it, ignoreCase = true)) out = out.replace(it, alias, ignoreCase = true) }

            val trimmedUrl = p.trimmedUrl
            if (trimmedUrl != null) {
                if (trimmedUrl != secret && trimmedUrl.length >= 8 && !alias.contains(trimmedUrl) && out.contains(trimmedUrl)) {
                    out = out.replace(trimmedUrl, alias)
                }
                p.trimmedUrlSlashEsc?.let { if (out.contains(it)) out = out.replace(it, alias) }
                p.trimmedUrlEnc?.let { if (out.contains(it, ignoreCase = true)) out = out.replace(it, alias, ignoreCase = true) }
                p.authority?.let { out = replaceOutsideProtectedTokens(out, it, alias, alias, marker) }
                p.bareHost?.let { out = replaceOutsideProtectedTokens(out, it, alias, alias, marker) }
            }

            // PTY 软折行（\r / \n）或夹杂 ANSI CSI 控制序列的连续密钥回显
            if (secret.length >= 6) {
                out = replaceSoftWrappedOrAnsi(out, secret, alias, marker)
                if (trimmedUrl != null && trimmedUrl != secret && trimmedUrl.length >= 8) {
                    out = replaceSoftWrappedOrAnsi(out, trimmedUrl, alias, marker)
                }
            }
        }
        return out
    }

    private fun trimmedHttpUrlVariant(secret: String): String? {
        val s = secret.trim()
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return s.trimEnd('/')
    }

    private fun extractPrivateUrlAuthority(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val rawAuth = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = rawAuth.substringAfterLast('@').trim()
        val host = hostPort.substringBefore(':').lowercase()
        if (host.length < 6 || host in LOCAL_HOSTS || '.' !in host) return null
        return hostPort
    }

    private fun replaceOutsideProtectedTokens(
        text: String,
        needle: String,
        replacement: String,
        alias: String,
        marker: String,
    ): String {
        if (needle.isEmpty()) return text
        var matchIdx = text.indexOf(needle, 0, ignoreCase = true)
        if (matchIdx < 0) return text
        val protectedRanges = findProtectedRanges(text, alias, marker)
        if (protectedRanges.isEmpty()) return text.replace(needle, replacement, ignoreCase = true)

        val sb = StringBuilder(text.length)
        var cursor = 0
        var protIdx = 0
        while (matchIdx >= 0) {
            while (protIdx < protectedRanges.size && protectedRanges[protIdx].second <= matchIdx) protIdx++
            if (protIdx < protectedRanges.size && matchIdx < protectedRanges[protIdx].second && matchIdx + needle.length > protectedRanges[protIdx].first) {
                val skipTo = maxOf(matchIdx + 1, protectedRanges[protIdx].second)
                matchIdx = text.indexOf(needle, skipTo, ignoreCase = true)
                continue
            }
            sb.append(text, cursor, matchIdx)
            sb.append(replacement)
            cursor = matchIdx + needle.length
            matchIdx = text.indexOf(needle, cursor, ignoreCase = true)
        }
        if (cursor == 0) return text
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }

    /** 跳过纯换行与 ANSI 转义序列匹配连续原串（处理终端 80 列硬折行或彩色报错）。 */
    private fun replaceSoftWrappedOrAnsi(
        text: String,
        secret: String,
        alias: String,
        marker: String,
    ): String {
        if (secret.isEmpty()) return text
        // 只有文本中存在换行或 ANSI 转义序列，且出现密钥首字符时才可能命中
        if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0 && text.indexOf('\u001b') < 0) return text
        val firstChar = secret[0]
        var nextCandidate = text.indexOf(firstChar)
        if (nextCandidate < 0) return text

        val protectedRanges = findProtectedRanges(text, alias, marker)
        var sb: StringBuilder? = null
        var appendedUntil = 0
        var protIdx = 0
        while (nextCandidate >= 0) {
            while (protIdx < protectedRanges.size && protectedRanges[protIdx].second <= nextCandidate) protIdx++
            if (protIdx < protectedRanges.size && nextCandidate >= protectedRanges[protIdx].first) {
                nextCandidate = text.indexOf(firstChar, protectedRanges[protIdx].second)
                continue
            }
            val end = matchWithAnsiAndLineWraps(text, nextCandidate, secret)
            if (end > nextCandidate) {
                val builder = sb ?: StringBuilder(text.length).also { sb = it }
                builder.append(text, appendedUntil, nextCandidate)
                builder.append(alias)
                appendedUntil = end
                nextCandidate = text.indexOf(firstChar, end)
            } else {
                nextCandidate = text.indexOf(firstChar, nextCandidate + 1)
            }
        }
        val builder = sb ?: return text
        if (appendedUntil < text.length) builder.append(text, appendedUntil, text.length)
        return builder.toString()
    }

    private fun matchWithAnsiAndLineWraps(text: String, start: Int, secret: String): Int {
        if (text[start] != secret[0]) return -1
        var p = start + 1
        var sawWrapOrAnsi = false
        for (k in 1 until secret.length) {
            while (p < text.length) {
                val ansiEnd = skipAnsiEscape(text, p)
                if (ansiEnd > p) {
                    p = ansiEnd
                    sawWrapOrAnsi = true
                    continue
                }
                if (text[p] == '\r' || text[p] == '\n') {
                    p++
                    sawWrapOrAnsi = true
                    continue
                }
                break
            }
            if (p >= text.length || text[p] != secret[k]) return -1
            p++
        }
        return if (sawWrapOrAnsi) p else -1
    }

    // -------------------------------------------------------------------------
    // Pass 2a: Base64 / Base64URL 三相位偏移不变核心子串扫描
    // -------------------------------------------------------------------------

    private fun replaceBase64Phases(
        text: String,
        cores: List<String>,
        alias: String,
        marker: String,
    ): String {
        var cur = text
        for (core in cores) {
            cur = replaceBase64Core(cur, core, alias, marker)
        }
        return cur
    }

    private fun replaceBase64Core(
        text: String,
        core: String,
        alias: String,
        marker: String,
    ): String {
        if (core.isEmpty()) return text
        val hasLineBreaks = text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
        if (!hasLineBreaks && text.indexOf(core) < 0) return text
        val firstChar = core[0]
        var i = if (hasLineBreaks) text.indexOf(firstChar) else text.indexOf(core)
        if (i < 0) return text

        val protectedRanges = findProtectedRanges(text, alias, marker)
        val spans = ArrayList<IntRange>()
        var protIdx = 0
        while (i >= 0 && i < text.length) {
            while (protIdx < protectedRanges.size && protectedRanges[protIdx].second <= i) protIdx++
            if (protIdx < protectedRanges.size && i >= protectedRanges[protIdx].first) {
                val resumeAt = protectedRanges[protIdx].second
                i = if (hasLineBreaks) text.indexOf(firstChar, resumeAt) else text.indexOf(core, resumeAt)
                continue
            }
            val end = if (hasLineBreaks) matchIgnoringLineBreaks(text, i, core) else i + core.length
            if (end > i) {
                // 向左右各扩最多 4 个 Base64 字符，抹除与相邻字节共用的边界 24-bit 量子
                var left = i
                var expandedLeft = 0
                while (left > 0 && expandedLeft < 4) {
                    val prev = text[left - 1]
                    if (prev == '\r' || prev == '\n') {
                        left--
                    } else if (prev.isBase64Char()) {
                        left--
                        expandedLeft++
                    } else {
                        break
                    }
                }
                var right = end
                var expandedRight = 0
                while (right < text.length && expandedRight < 6) {
                    val next = text[right]
                    if (next == '\r' || next == '\n') {
                        right++
                    } else if (next.isBase64Char() || next == '=') {
                        right++
                        expandedRight++
                    } else {
                        break
                    }
                }
                spans.add(left until right)
                i = if (hasLineBreaks) text.indexOf(firstChar, right) else text.indexOf(core, right)
            } else {
                i = text.indexOf(firstChar, i + 1)
            }
        }
        return replaceMergedSpans(text, spans, marker)
    }

    private fun matchIgnoringLineBreaks(text: String, start: Int, needle: String): Int {
        if (text[start] != needle[0]) return -1
        var p = start + 1
        for (k in 1 until needle.length) {
            while (p < text.length && (text[p] == '\r' || text[p] == '\n')) p++
            if (p >= text.length || text[p] != needle[k]) return -1
            p++
        }
        return p
    }

    private fun Char.isBase64Char(): Boolean =
        (this in 'A'..'Z') || (this in 'a'..'z') || (this in '0'..'9') ||
            this == '+' || this == '/' || this == '-' || this == '_'

    // -------------------------------------------------------------------------
    // Pass 2b: 字节编码序列（Hex / od -x 小端双字节 / 八进制 / 十进制 ASCII）
    // -------------------------------------------------------------------------

    private enum class TokenStreamMode {
        HEX_BYTE,
        HEX_WORD_LE,
        NUMERIC,
    }

    private fun replaceEncodedTokenStream(
        text: String,
        tokens: List<String>,
        mode: TokenStreamMode,
        alias: String,
        marker: String,
    ): String {
        if (tokens.isEmpty()) return text
        val firstTok = tokens[0]
        var i = text.indexOf(firstTok, 0, ignoreCase = true)
        if (i < 0) return text

        val protectedRanges = findProtectedRanges(text, alias, marker)
        val spans = ArrayList<IntRange>()
        var protIdx = 0
        while (i >= 0 && i <= text.length - firstTok.length) {
            while (protIdx < protectedRanges.size && protectedRanges[protIdx].second <= i) protIdx++
            if (protIdx < protectedRanges.size && i >= protectedRanges[protIdx].first) {
                i = text.indexOf(firstTok, protectedRanges[protIdx].second, ignoreCase = true)
                continue
            }
            if (hasValidTokenLeftBoundary(text, i, mode)) {
                var tokenStart = i
                // 若前面紧跟 0x / \x 前缀，一并纳入抹除范围
                if (mode != TokenStreamMode.NUMERIC && i >= 2) {
                    val p2 = text.substring(i - 2, i)
                    if (p2.equals("0x", ignoreCase = true) || p2.equals("\\x", ignoreCase = true)) {
                        tokenStart = i - 2
                    }
                } else if (mode == TokenStreamMode.NUMERIC && i >= 1 && text[i - 1] == '\\') {
                    tokenStart = i - 1
                }
                val matchEnd = matchRemainingTokens(text, i + firstTok.length, tokens, mode)
                if (matchEnd > i && hasValidTokenRightBoundary(text, matchEnd, mode)) {
                    spans.add(tokenStart until matchEnd)
                    i = text.indexOf(firstTok, matchEnd, ignoreCase = true)
                    continue
                }
            }
            i = text.indexOf(firstTok, i + 1, ignoreCase = true)
        }
        return replaceMergedSpans(text, spans, marker)
    }

    private fun hasValidTokenLeftBoundary(text: String, pos: Int, mode: TokenStreamMode): Boolean {
        if (pos == 0) return true
        val prev = text[pos - 1]
        return when (mode) {
            TokenStreamMode.NUMERIC -> !prev.isDigit()
            TokenStreamMode.HEX_WORD_LE -> !prev.isHexDigit()
            TokenStreamMode.HEX_BYTE -> {
                if (!prev.isHexDigit()) true
                else {
                    // xxd 偶数字节对（如 6874）中第 1 个字节若从奇数半字开始可能前接 hex，要求非 hex 边界或 0x 前缀
                    pos >= 2 && (text.substring(pos - 2, pos).equals("0x", ignoreCase = true))
                }
            }
        }
    }

    private fun hasValidTokenRightBoundary(text: String, endPos: Int, mode: TokenStreamMode): Boolean {
        if (endPos >= text.length) return true
        val next = text[endPos]
        return when (mode) {
            TokenStreamMode.NUMERIC -> !next.isDigit()
            TokenStreamMode.HEX_WORD_LE, TokenStreamMode.HEX_BYTE -> !next.isHexDigit()
        }
    }

    private fun matchRemainingTokens(
        text: String,
        afterFirst: Int,
        tokens: List<String>,
        mode: TokenStreamMode,
    ): Int {
        var p = afterFirst
        for (k in 1 until tokens.size) {
            val tok = tokens[k]
            val nextStart = skipTokenSeparator(text, p, mode)
            if (nextStart < 0 || nextStart + tok.length > text.length) return -1
            if (!text.regionMatches(nextStart, tok, 0, tok.length, ignoreCase = true)) return -1
            p = nextStart + tok.length
        }
        return p
    }

    private fun skipTokenSeparator(text: String, from: Int, mode: TokenStreamMode): Int {
        var p = from
        // Hex 单字节支持 0 间隔（如连续 hex 或 xxd 的 4 字符分组 6874 7470）
        if (mode == TokenStreamMode.HEX_BYTE && p < text.length && text[p].isHexDigit()) {
            return p
        }
        // 跳过行内常规分隔符：空格、制表、逗号、分号、引号、冒号、竖线、方括号
        var inlineCount = 0
        while (p < text.length && inlineCount < 10) {
            val ansiEnd = skipAnsiEscape(text, p)
            if (ansiEnd > p) {
                p = ansiEnd
                continue
            }
            val c = text[p]
            if (c == ' ' || c == '\t' || c == ',' || c == ';' || c == '\'' || c == '"' || c == '|' || c == '[' || c == ']') {
                p++
                inlineCount++
            } else {
                break
            }
        }
        // 处理 xxd / hexdump / od 跨行：行尾可选 ASCII 预览栏（如 `  |https://...|` 或 `  https://...`）+ 换行 + 行首地址偏移
        val afterWrap = skipDumpLineWrap(text, from, p)
        if (afterWrap > p) {
            p = afterWrap
        }
        // 可选 0x / \x / \ 前缀
        if (mode != TokenStreamMode.NUMERIC && p + 2 <= text.length) {
            val pfx = text.substring(p, p + 2)
            if (pfx.equals("0x", ignoreCase = true) || pfx.equals("\\x", ignoreCase = true)) {
                p += 2
                return p
            }
        }
        if (mode == TokenStreamMode.NUMERIC && p < text.length && text[p] == '\\' && inlineCount == 0 && afterWrap <= from) {
            return p + 1
        }
        return if (p > from) p else -1
    }

    /**
     * 跳过 `od` / `xxd` / `hexdump -C` 的跨行结构：
     * - 行尾（若紧接 2+ 空格或 `|`）最多 24 个非换行预览字符；
     * - `\r?\n`；
     * - 行首可选空白 + 4~10 位十六进制/八进制地址偏移（可选 `:`）+ 空白。
     */
    private fun skipDumpLineWrap(text: String, rawStart: Int, afterSpaces: Int): Int {
        var p = afterSpaces
        // 若行尾至少有 2 个空格（xxd / hexdump -C 右侧预览栏与 hex 栏之间的固定双空格），允许跨过本行剩余最多 22 个预览字符到换行
        if (afterSpaces - rawStart >= 2 && p < text.length && text[p] != '\r' && text[p] != '\n') {
            var look = p
            var gutterChars = 0
            while (look < text.length && text[look] != '\r' && text[look] != '\n' && gutterChars <= 22) {
                look++
                gutterChars++
            }
            if (look < text.length && (text[look] == '\r' || text[look] == '\n')) {
                p = look
            }
        }
        if (p >= text.length || (text[p] != '\r' && text[p] != '\n')) return -1
        if (text[p] == '\r') p++
        if (p < text.length && text[p] == '\n') p++

        while (p < text.length && (text[p] == ' ' || text[p] == '\t')) p++
        // 行首地址偏移（如 `0000020   ` 或 `00000010: `）
        val addrStart = p
        while (p < text.length && p - addrStart < 12 && text[p].isHexDigit()) p++
        if (p - addrStart in 4..10) {
            if (p < text.length && text[p] == ':') p++
            var sp = 0
            while (p < text.length && (text[p] == ' ' || text[p] == '\t')) {
                p++
                sp++
            }
            if (sp == 0) {
                // 不是地址列，回退到行首空白后
                p = addrStart
            }
        } else {
            p = addrStart
        }
        return p
    }

    // -------------------------------------------------------------------------
    // Pass 2c: 有界分隔符拆字符流（od -c、' '.join、'-'.join、list(s)、hexdump 右栏）
    // -------------------------------------------------------------------------

    private fun replaceSeparatedCharStream(
        text: String,
        charTokens: List<String>,
        alias: String,
        marker: String,
    ): String {
        if (charTokens.size < 6) return text
        val first = charTokens[0]
        var i = text.indexOf(first)
        if (i < 0) return text

        val protectedRanges = findProtectedRanges(text, alias, marker)
        val spans = ArrayList<IntRange>()
        var protIdx = 0
        while (i >= 0 && i <= text.length - first.length) {
            while (protIdx < protectedRanges.size && protectedRanges[protIdx].second <= i) protIdx++
            if (protIdx < protectedRanges.size && i >= protectedRanges[protIdx].first) {
                i = text.indexOf(first, protectedRanges[protIdx].second)
                continue
            }
            val end = matchSeparatedCharsFrom(text, i + first.length, charTokens)
            if (end > i) {
                spans.add(i until end)
                i = text.indexOf(first, end)
            } else {
                i = text.indexOf(first, i + 1)
            }
        }
        return replaceMergedSpans(text, spans, marker)
    }

    private fun matchSeparatedCharsFrom(
        text: String,
        afterFirst: Int,
        charTokens: List<String>,
    ): Int {
        var p = afterFirst
        var sawSeparator = false
        for (k in 1 until charTokens.size) {
            val targetTok = charTokens[k]
            val nextPos = advanceToNextCharToken(text, p, targetTok)
            if (nextPos < 0) return -1
            if (nextPos > p) sawSeparator = true
            p = nextPos + targetTok.length
        }
        return if (sawSeparator) p else -1
    }

    /**
     * 在拆字符流中定位下一个目标字符 `targetTok`：
     * - 只允许跳过非字母数字的标点/空白（最多 8 个字符），且一旦遇到 `targetTok` 立即停下（避免把 `:`、`/`、`.` 当分隔符吃过头）；
     * - 允许跳过换行 + `od` 地址列（及 `hexdump -C` 左侧最多 58 列的 hex 区域到达右侧 `|` 字符栏）。
     */
    private fun advanceToNextCharToken(text: String, from: Int, targetTok: String): Int {
        if (from + targetTok.length <= text.length && text.startsWith(targetTok, from)) {
            // 若紧邻匹配，优先验证不是同类分隔符碰撞：除非后面还有分隔符模式，直接取紧邻位置即可
            return from
        }
        var p = from
        var inlineSkipped = 0
        while (p < text.length && inlineSkipped < 8) {
            val ansiEnd = skipAnsiEscape(text, p)
            if (ansiEnd > p) {
                p = ansiEnd
                continue
            }
            if (p > from && p + targetTok.length <= text.length && text.startsWith(targetTok, p)) {
                return p
            }
            val c = text[p]
            if (c == '\r' || c == '\n') break
            if (c.isLetterOrDigit()) return -1
            p++
            inlineSkipped++
        }
        if (p + targetTok.length <= text.length && text.startsWith(targetTok, p)) {
            return p
        }
        // 检查跨行（od -c 换行地址列 或 hexdump -C 换行后跳过 hex 栏进入右侧 |...| 字符栏）
        if (p < text.length && (text[p] == '\r' || text[p] == '\n')) {
            if (text[p] == '\r') p++
            if (p < text.length && text[p] == '\n') p++
            while (p < text.length && (text[p] == ' ' || text[p] == '\t')) p++
            val lineContentStart = p
            // 尝试跳过 od 地址列（如 `0000020   `）
            var q = p
            while (q < text.length && q - lineContentStart < 12 && text[q].isHexDigit()) q++
            if (q - lineContentStart in 4..10) {
                if (q < text.length && text[q] == ':') q++
                var sp = 0
                while (q < text.length && (text[q] == ' ' || text[q] == '\t')) {
                    q++
                    sp++
                }
                if (sp > 0) p = q
            }
            // 若是 hexdump -C 右栏（行内先有 hex 列再以 `|` 开启字符栏），定位本行第一个 `|` 之后
            if (p + targetTok.length <= text.length && !text.startsWith(targetTok, p)) {
                var pipeScan = p
                var steps = 0
                var onlyHexAndSpaces = true
                while (pipeScan < text.length && text[pipeScan] != '\r' && text[pipeScan] != '\n' && steps < 60) {
                    val ch = text[pipeScan]
                    if (ch == '|') {
                        if (onlyHexAndSpaces && pipeScan + 1 + targetTok.length <= text.length &&
                            text.startsWith(targetTok, pipeScan + 1)
                        ) {
                            return pipeScan + 1
                        }
                        break
                    }
                    if (!ch.isHexDigit() && ch != ' ' && ch != '\t') {
                        onlyHexAndSpaces = false
                        break
                    }
                    pipeScan++
                    steps++
                }
            }
            // 行首地址后再跳过最多 4 个引号/空格分隔符
            var afterLineSep = 0
            while (p < text.length && afterLineSep < 4) {
                if (p + targetTok.length <= text.length && text.startsWith(targetTok, p)) return p
                val c = text[p]
                if (c.isLetterOrDigit() || c == '\r' || c == '\n') break
                p++
                afterLineSep++
            }
            if (p + targetTok.length <= text.length && text.startsWith(targetTok, p)) return p
        }
        return -1
    }

    // -------------------------------------------------------------------------
    // Pass 2d: 长密钥高熵连续切片拦截（防 key[:10]、key[-12:]、hexdump 半行明文残片）
    // -------------------------------------------------------------------------

    private fun replaceSecretSlices(
        text: String,
        secret: String,
        windows: List<Pair<String, Int>>,
        alias: String,
        marker: String,
    ): String {
        if (windows.isEmpty()) return text

        var protectedRanges: List<Pair<Int, Int>>? = null
        val spans = ArrayList<IntRange>()
        for ((win, secretIdx) in windows) {
            var searchFrom = 0
            while (searchFrom <= text.length - win.length) {
                val idx = text.indexOf(win, searchFrom)
                if (idx < 0) break
                val prot = protectedRanges ?: findProtectedRanges(text, alias, marker).also { protectedRanges = it }
                if (prot.any { idx < it.second && idx + win.length > it.first }) {
                    searchFrom = idx + 1
                    continue
                }
                // 向左右延伸吞掉属于同一密钥连续片段的相邻字符（例如连同前面的 `sk-` 或后面的剩余切片一并抹掉）
                var left = idx
                var right = idx + win.length
                if (secretIdx >= 0) {
                    var sLeft = secretIdx
                    while (left > 0 && sLeft > 0 && text[left - 1] == secret[sLeft - 1] &&
                        prot.none { left - 1 in it.first until it.second }
                    ) {
                        left--
                        sLeft--
                    }
                    var sRight = secretIdx + win.length
                    while (right < text.length && sRight < secret.length && text[right] == secret[sRight] &&
                        prot.none { right in it.first until it.second }
                    ) {
                        right++
                        sRight++
                    }
                }
                spans.add(left until right)
                searchFrom = right
            }
        }
        return replaceMergedSpans(text, spans, marker)
    }

    // -------------------------------------------------------------------------
    // 辅助工具与零泄密安全诊断生成
    // -------------------------------------------------------------------------

    private fun buildSafeDiagnosticNotice(entry: SecretKeyEntry): String {
        val s = entry.secretValue
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        val isUrl = s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)
        val hasLf = '\n' in s
        val hasCr = '\r' in s
        val leadWs = s.firstOrNull()?.isWhitespace() == true
        val trailWs = s.lastOrNull()?.isWhitespace() == true
        val endsSlash = s.endsWith("/")
        return "[HOST SECURITY: Output containing split/encoded/sliced bytes of secret alias \"${entry.keyName}\" was redacted. " +
            "Do NOT inspect secret bytes via od, xxd, hexdump, base64, ord, or slicing. " +
            "Safe format diagnostics for \"${entry.keyName}\" -> utf8_bytes=${bytes.size}, chars=${s.length}, " +
            "has_lf=$hasLf, has_cr=$hasCr, leading_space=$leadWs, trailing_space=$trailWs, " +
            "is_http_url=$isUrl, ends_with_slash=$endsSlash]"
    }

    private fun findProtectedRanges(text: String, alias: String, marker: String): List<Pair<Int, Int>> {
        val ranges = ArrayList<Pair<Int, Int>>()
        fun collect(token: String) {
            if (token.isEmpty()) return
            var idx = text.indexOf(token)
            while (idx >= 0) {
                ranges.add(idx to (idx + token.length))
                idx = text.indexOf(token, idx + token.length)
            }
        }
        collect(marker)
        collect(alias)
        collect("[HOST SECURITY:")
        if (ranges.size <= 1) return ranges
        ranges.sortWith(compareBy<Pair<Int, Int>> { it.first }.thenByDescending { it.second })
        val merged = ArrayList<Pair<Int, Int>>(ranges.size)
        var cur = ranges[0]
        for (k in 1 until ranges.size) {
            val next = ranges[k]
            cur = if (next.first <= cur.second) {
                cur.first to maxOf(cur.second, next.second)
            } else {
                merged.add(cur)
                next
            }
        }
        merged.add(cur)
        return merged
    }

    private fun replaceMergedSpans(text: String, spans: List<IntRange>, replacement: String): String {
        if (spans.isEmpty()) return text
        val sorted = spans.sortedWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })
        val merged = ArrayList<IntRange>(sorted.size)
        var cur = sorted[0]
        for (idx in 1 until sorted.size) {
            val next = sorted[idx]
            cur = if (next.first <= cur.last + 1) {
                cur.first..maxOf(cur.last, next.last)
            } else {
                merged.add(cur)
                next
            }
        }
        merged.add(cur)

        val sb = StringBuilder(text.length)
        var pos = 0
        for (r in merged) {
            if (r.first > pos) sb.append(text, pos, r.first)
            sb.append(replacement)
            pos = r.last + 1
        }
        if (pos < text.length) sb.append(text, pos, text.length)
        return sb.toString()
    }

    private fun skipAnsiEscape(text: String, pos: Int): Int {
        if (pos + 1 >= text.length || text[pos] != '\u001b' || text[pos + 1] != '[') return pos
        var p = pos + 2
        while (p < text.length && p - pos < 20) {
            val c = text[p]
            if (c in '@'..'~') return p + 1
            if (c !in '0'..'?' && c !in ' '..'/') return pos
            p++
        }
        return pos
    }

    private fun Char.isHexDigit(): Boolean =
        (this in '0'..'9') || (this in 'a'..'f') || (this in 'A'..'F')
}
