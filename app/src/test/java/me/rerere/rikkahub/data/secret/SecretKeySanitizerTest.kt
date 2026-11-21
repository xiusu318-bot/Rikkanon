package me.rerere.rikkahub.data.secret

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SecretKeyEntry
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.redactSecretKeyValues
import me.rerere.rikkahub.data.datastore.replaceSecretKeyAliases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class SecretKeySanitizerTest {

    private val urlEntry = SecretKeyEntry(
        keyName = "tiurl",
        secretValue = "https://love1011.xyz/",
        description = "测试中转地址",
        enabled = true,
    )

    private val apiKeyEntry = SecretKeyEntry(
        keyName = "tikey",
        secretValue = "sk-9f8e7d6c5b4a392817263544556677889900aabbccddeeff",
        description = "51位长期测试密钥",
        enabled = true,
    )

    private val entries = listOf(urlEntry, apiKeyEntry)
    private val settings = Settings(enableSecretKeyAlias = true, secretKeys = entries)

    private fun assertNoLeak(redacted: String) {
        assertFalse("Leaked love1011: $redacted", redacted.contains("love1011", ignoreCase = true))
        assertFalse("Leaked apiKey core: $redacted", redacted.contains("9f8e7d6c", ignoreCase = true))
        assertFalse("Leaked apiKey tail: $redacted", redacted.contains("ccddeeff", ignoreCase = true))
        assertFalse("Leaked spaced h t t p s: $redacted", redacted.contains("l   o   v   e"))
    }

    @Test
    fun `internal subagent and memory tools do not expand secret aliases while workspace tools do`() {
        assertFalse(SecretKeySanitizer.shouldExpandToolInput("conversation_send"))
        assertFalse(SecretKeySanitizer.shouldExpandToolInput("conversation_approve"))
        assertFalse(SecretKeySanitizer.shouldExpandToolInput("conversation_read"))
        assertFalse(SecretKeySanitizer.shouldExpandToolInput("conversation_search"))
        assertFalse(SecretKeySanitizer.shouldExpandToolInput("recent_chats"))
        assertFalse(SecretKeySanitizer.shouldExpandToolInput("memory_write"))
        assertFalse(SecretKeySanitizer.shouldExpandToolInput("memory_str_replace"))
        assertFalse(SecretKeySanitizer.shouldExpandToolInput("memory_append"))
        assertFalse(SecretKeySanitizer.shouldExpandToolInput("ask_user"))
        assertFalse(SecretKeySanitizer.shouldExpandToolInput("ask_key"))

        assertTrue(SecretKeySanitizer.shouldExpandToolInput("workspace_write_file"))
        assertTrue(SecretKeySanitizer.shouldExpandToolInput("workspace_edit_file"))
        assertTrue(SecretKeySanitizer.shouldExpandToolInput("workspace_shell"))
        assertTrue(SecretKeySanitizer.shouldExpandToolInput("workspace_terminal"))
        assertTrue(SecretKeySanitizer.shouldExpandToolInput("mcp__github__call"))

        // 验证 conversation_send 入参保持代名词原样，而 workspace_write_file 正常展开明文落盘
        val subAgentArgs = buildJsonObject {
            put("message", "请用 tikey 请求 tiurlv1/models")
        }
        val unexpanded = subAgentArgs.replaceSecretKeyAliases(
            settings,
            enabled = SecretKeySanitizer.shouldExpandToolInput("conversation_send"),
        )
        assertEquals("请用 tikey 请求 tiurlv1/models", unexpanded.jsonObject["message"]!!.jsonPrimitive.content)

        val writeArgs = buildJsonObject {
            put("path", "/workspace/ti.env")
            put("text", "BASE_URL=tiurl\nAPI_KEY=tikey\n")
        }
        val expanded = writeArgs.replaceSecretKeyAliases(
            settings,
            enabled = SecretKeySanitizer.shouldExpandToolInput("workspace_write_file"),
        )
        assertTrue(expanded.jsonObject["text"]!!.jsonPrimitive.content.contains(apiKeyEntry.secretValue))
    }

    @Test
    fun `shell json stdout and stderr with escaping slashless url bare host ansi and pty wrap are silently masked`() {
        val shellJson = buildJsonObject {
            put("exitCode", 127)
            put("stdout", "tiurl check: https://love1011.xyz/v1/models and slashless https://love1011.xyz")
            put(
                "stderr",
                "bash: https://love1011.xyz/=1: No such file or directory\n" +
                    "curl: (6) Could not resolve host: love1011.xyz\n" +
                    "\u001B[31mAuth failed for sk-9f8e7d6c5b4a3928172635\r\n44556677889900aabbccddeeff\u001B[0m"
            )
            put("timedOut", false)
        }.toString()

        val out = settings.redactSecretKeyValues(shellJson, true)
        assertNoLeak(out)
        assertFalse("Routine echo should not trigger probing warning: $out", out.contains("[HOST SECURITY:"))
        assertTrue(out.contains("tiurl=1: No such file or directory"))
        assertTrue(out.contains("tikey"))
    }

    @Test
    fun `od dash c byte split output across 16 byte lines is caught and emits safe diagnostic`() {
        // 真实还原 `od -c` 每行 16 字节带行首八进制偏移的输出
        val odC = """
            0000000   h   t   t   p   s   :   /   /   l   o   v   e   1   0   1   1
            0000020   .   x   y   z   /  \n   K   E   Y   =   s   k   -   9   f   8
            0000040   e   7   d   6   c   5   b   4   a   3   9   2   8   1   7   2
            0000060   6   3   5   4   4   5   5   6   6   7   7   8   8   9   9   0
            0000100   0   a   a   b   b   c   c   d   d   e   e   f   f  \n
        """.trimIndent()

        val shellPayload = buildJsonObject {
            put("exitCode", 0)
            put("stdout", odC)
            put("stderr", "")
        }.toString()

        val out = settings.redactSecretKeyValues(shellPayload, true)
        assertNoLeak(out)
        assertTrue(out.contains("[REDACTED:tiurl]"))
        assertTrue(out.contains("[REDACTED:tikey]"))
        assertTrue(out.contains("ends_with_slash=true"))
        assertTrue(out.contains("utf8_bytes=21"))
        assertTrue(out.contains("utf8_bytes=51"))
    }

    @Test
    fun `od hex octal decimal xxd and hexdump formats are all caught`() {
        val urlBytes = urlEntry.secretValue.toByteArray(StandardCharsets.UTF_8)

        // 1. od -t x1（空格分隔单字节 hex + 跨行偏移）
        val odTx1 = "0000000 " + urlBytes.take(16).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) } +
            "\n0000020 " + urlBytes.drop(16).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        assertNoLeak(SecretKeySanitizer.redactText(odTx1, entries).also {
            assertTrue(it.contains("[REDACTED:tiurl]"))
        })

        // 2. od -x（16位小端双字节 hex，含偶数与奇数偏移）
        for (prefix in listOf("", "A=", "AB=")) {
            val all = (prefix + urlEntry.secretValue).toByteArray(StandardCharsets.UTF_8)
            val words = (0 until all.size - 1 step 2).map { i ->
                "%02x%02x".format(all[i + 1].toInt() and 0xFF, all[i].toInt() and 0xFF)
            }
            val odX = "0000000 " + words.take(8).joinToString(" ") + "\n0000020 " + words.drop(8).joinToString(" ")
            val res = SecretKeySanitizer.redactText(odX, entries)
            assertTrue("od -x with prefix '$prefix' should be redacted: $res", res.contains("[REDACTED:tiurl]"))
        }

        // 3. od -b（八进制）
        val odB = "0000000 " + urlBytes.take(16).joinToString(" ") { "%03o".format(it.toInt() and 0xFF) } +
            "\n0000020 " + urlBytes.drop(16).joinToString(" ") { "%03o".format(it.toInt() and 0xFF) }
        assertTrue(SecretKeySanitizer.redactText(odB, entries).contains("[REDACTED:tiurl]"))

        // 4. Python [ord(c) for c in s]（十进制数组）
        val pyOrd = "[" + urlBytes.joinToString(", ") { (it.toInt() and 0xFF).toString() } + "]"
        assertTrue(SecretKeySanitizer.redactText(pyOrd, entries).contains("[REDACTED:tiurl]"))

        // 5. hexdump -C（左侧 hex + 右侧 |ASCII| 预览栏）
        val hexdumpC = """
            00000000  68 74 74 70 73 3a 2f 2f  6c 6f 76 65 31 30 31 31  |https://love1011|
            00000010  2e 78 79 7a 2f 0a                                 |.xyz/.|
        """.trimIndent()
        val resHexdump = SecretKeySanitizer.redactText(hexdumpC, entries)
        assertNoLeak(resHexdump)
        assertFalse("Hex bytes should be gone: $resHexdump", resHexdump.contains("6c 6f 76 65"))

        // 6. xxd（4字符分组 hex + 右侧无竖线预览栏）
        val xxd = """
            00000000: 6874 7470 733a 2f2f 6c6f 7665 3130 3131  https://love1011
            00000010: 2e78 797a 2f0a                           .xyz/.
        """.trimIndent()
        val resXxd = SecretKeySanitizer.redactText(xxd, entries)
        assertNoLeak(resXxd)
        assertFalse("xxd hex should be gone: $resXxd", resXxd.contains("6c6f 7665"))
    }

    @Test
    fun `base64 at any byte alignment and 76 col wrap is caught`() {
        for (prefix in listOf("", "X", "XY", "BASE_URL=")) {
            val raw = "$prefix${urlEntry.secretValue}\nAPI_KEY=${apiKeyEntry.secretValue}\n"
            val b64 = Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
                .chunked(76)
                .joinToString("\n")
            val res = SecretKeySanitizer.redactText(b64, entries)
            assertTrue("Base64 with prefix '$prefix' should redact url: $res", res.contains("[REDACTED:tiurl]"))
            assertTrue("Base64 with prefix '$prefix' should redact key: $res", res.contains("[REDACTED:tikey]"))
            // 确保解码剩余内容也拿不到原始密钥
            assertFalse(res.contains("bG92ZTEwMTE"))
        }
    }

    @Test
    fun `python char join list and substring slicing are caught and diff metadata is sanitized`() {
        val pyList = apiKeyEntry.secretValue.map { "'$it'" }.toString()
        assertNoLeak(SecretKeySanitizer.redactText(pyList, entries))

        val dashJoin = apiKeyEntry.secretValue.toCharArray().joinToString("-")
        assertNoLeak(SecretKeySanitizer.redactText(dashJoin, entries))

        // 切片探测 key[:14] 与 key[-12:]
        val headSlice = "prefix=" + apiKeyEntry.secretValue.take(14)
        val tailSlice = "suffix=" + apiKeyEntry.secretValue.takeLast(12)
        val resHead = SecretKeySanitizer.redactText(headSlice, entries)
        val resTail = SecretKeySanitizer.redactText(tailSlice, entries)
        assertNoLeak(resHead)
        assertNoLeak(resTail)
        assertTrue(resHead.contains("[REDACTED:tikey]"))
        assertTrue(resTail.contains("[REDACTED:tikey]"))

        // workspace_edit_file 的 DiffMetadata 脱敏验证
        val partWithDiff = UIMessagePart.Text(
            text = """{"path":"/workspace/ti.env","replacements":1}""",
            metadata = JsonObject(
                mapOf("diff" to JsonPrimitive("-API_KEY=old\n+API_KEY=${apiKeyEntry.secretValue}"))
            ),
        )
        val sanitizedPart = SecretKeySanitizer.redactMessagePart(partWithDiff, entries) as UIMessagePart.Text
        val diffAfter = sanitizedPart.metadata!!["diff"]!!.jsonPrimitive.content
        assertNoLeak(diffAfter)
        assertTrue(diffAfter.contains("tikey"))
    }

    @Test
    fun `normal source code english prose and unrelated hashes have zero false positives`() {
        val normalOutput = """
            Build succeeded in 12.4s!
            Commit: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            Server listening on http://localhost:8080/api/v1/health (status 200)
            val numbers = listOf(104, 200, 300, 404, 500)
            Using configured aliases tikey and tiurl to send requests.
        """.trimIndent()
        val res = SecretKeySanitizer.redactText(normalOutput, entries)
        assertEquals(normalOutput, res)
    }

    @Test
    fun `large 64kb tool json payload sanitizes embedded leaks accurately and quickly`() {
        val cleanLogChunk = (1..200).joinToString("\n") { line ->
            "[2026-11-17 12:00:%02d] INFO worker-%d processed 1024 items status=200 hash=a1b2c3d4e5f60718293a".format(
                line % 60,
                line % 8,
            )
        }
        val payload = buildJsonObject {
            put("stdout", cleanLogChunk + "\nTAIL_CHECK=" + urlEntry.secretValue + "v1/chat")
            put("stderr", cleanLogChunk + "\n\u001B[33mKEY_WRAP=" + apiKeyEntry.secretValue.take(20) + "\r\n" + apiKeyEntry.secretValue.drop(20) + "\u001B[0m")
        }.toString()

        val startNs = System.nanoTime()
        val redacted = SecretKeySanitizer.redactToolPayload(payload, entries)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertNoLeak(redacted)
        assertTrue(redacted.contains("TAIL_CHECK=tiurlv1/chat"))
        assertTrue(redacted.contains("KEY_WRAP=tikey"))
        assertTrue("Expected fast sanitization on 64KB payload, took ${elapsedMs}ms", elapsedMs < 2_000L)
    }
}
