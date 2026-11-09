package me.rerere.rikkahub.data.remote

import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.ProxyProfile
import me.rerere.rikkahub.data.datastore.ProxyType
import me.rerere.rikkahub.data.datastore.normalized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

class ProxyConfigTest {
    @Test
    fun `parses http proxy URL`() {
        val proxy = "http://127.0.0.1:7890".toProxyOrNull()!!
        val address = proxy.address() as InetSocketAddress

        assertEquals(Proxy.Type.HTTP, proxy.type())
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(7890, address.port)
    }

    @Test
    fun `parses socks proxy URL`() {
        val proxy = "socks5://localhost:1080".toProxyOrNull()!!

        assertEquals(Proxy.Type.SOCKS, proxy.type())
    }

    @Test
    fun `maps https proxy URL to http proxy type`() {
        val proxy = "https://localhost:8443".toProxyOrNull()!!

        assertEquals(Proxy.Type.HTTP, proxy.type())
    }

    @Test
    fun `defaults host and port input to http proxy`() {
        val proxy = "localhost:8080".toProxyOrNull()!!

        assertEquals(Proxy.Type.HTTP, proxy.type())
    }

    @Test
    fun `rejects invalid proxy URL`() {
        assertNull("ftp://localhost:21".toProxyOrNull())
        assertNull("socks://localhost:1080".toProxyOrNull())
        assertNull("http://localhost".toProxyOrNull())
        assertNull("http://user:password@localhost:8080".toProxyOrNull())
        assertNull("http://localhost:8080/path".toProxyOrNull())
    }

    @Test
    fun `parseProxyProfileOrNull supports uri with credentials and 4-part residential format`() {
        val uriProfile = "https://myuser:mypass@proxy.example.com:8443".parseProxyProfileOrNull()
        assertNotNull(uriProfile)
        assertEquals(ProxyType.HTTPS, uriProfile!!.type)
        assertEquals("proxy.example.com", uriProfile.host)
        assertEquals(8443, uriProfile.port)
        assertEquals("myuser", uriProfile.username)
        assertEquals("mypass", uriProfile.password)

        val socksProfile = "socks5h://u1:p1@1.2.3.4:10800".parseProxyProfileOrNull()
        assertNotNull(socksProfile)
        assertEquals(ProxyType.SOCKS5, socksProfile!!.type)
        assertEquals("1.2.3.4", socksProfile.host)
        assertEquals(10800, socksProfile.port)
        assertEquals("u1", socksProfile.username)
        assertEquals("p1", socksProfile.password)

        val fourPart = "us.isp.example.com:9000:zone-user-1:secret:pass".parseProxyProfileOrNull(
            defaultType = ProxyType.SOCKS5
        )
        assertNotNull(fourPart)
        assertEquals(ProxyType.SOCKS5, fourPart!!.type)
        assertEquals("us.isp.example.com", fourPart.host)
        assertEquals(9000, fourPart.port)
        assertEquals("zone-user-1", fourPart.username)
        assertEquals("secret:pass", fourPart.password)
    }

    @Test
    fun `normalized falls back to null when assigned proxy is deleted`() {
        val p1 = ProxyProfile(id = Uuid.random(), name = "P1", type = ProxyType.HTTP, host = "127.0.0.1", port = 7890)
        val deletedId = Uuid.random()
        val setting = NetworkSetting(
            proxies = listOf(p1),
            llmProxyId = p1.id,
            workspaceProxyId = deletedId,
            mcpProxyId = deletedId,
        ).normalized()

        assertEquals(p1.id, setting.llmProxyId)
        assertNull(setting.workspaceProxyId)
        assertNull(setting.mcpProxyId)
        assertEquals(p1, setting.resolveProxy(OutboundTarget.LLM))
        assertNull(setting.resolveProxy(OutboundTarget.WORKSPACE))
        assertNull(setting.resolveProxy(OutboundTarget.MCP))
    }

    @Test
    fun `ProxyTunnelDialer dials SOCKS5 with username password and domain ATYP 0x03`() {
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { mockSocksServer ->
            var receivedDomain = ""
            var receivedPort = 0
            var receivedUser = ""
            var receivedPass = ""

            val serverThread = thread {
                mockSocksServer.accept().use { client ->
                    val input = client.getInputStream()
                    val output = client.getOutputStream()
                    // greeting: 0x05, 0x02, 0x00, 0x02
                    val ver = input.read()
                    val nMethods = input.read()
                    val methods = ByteArray(nMethods)
                    input.read(methods)
                    assertEquals(5, ver)
                    // require username/password (0x02)
                    output.write(byteArrayOf(0x05, 0x02))
                    output.flush()

                    // RFC 1929 auth
                    val authVer = input.read()
                    assertEquals(1, authVer)
                    val uLen = input.read()
                    receivedUser = String(input.readNBytes(uLen))
                    val pLen = input.read()
                    receivedPass = String(input.readNBytes(pLen))
                    output.write(byteArrayOf(0x01, 0x00))
                    output.flush()

                    // CONNECT request
                    val header = input.readNBytes(4)
                    assertEquals(0x05, header[0].toInt())
                    assertEquals(0x01, header[1].toInt())
                    assertEquals(0x03, header[3].toInt()) // ATYP = DOMAINNAME (remote DNS)
                    val dLen = input.read()
                    receivedDomain = String(input.readNBytes(dLen))
                    val portBytes = input.readNBytes(2)
                    receivedPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

                    // reply success
                    output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 80))
                    output.flush()

                    val reader = BufferedReader(InputStreamReader(input))
                    val line = reader.readLine()
                    if (line == "PING") {
                        output.write("PONG\n".toByteArray())
                        output.flush()
                    }
                }
            }

            val profile = ProxyProfile(
                name = "TestSocks",
                type = ProxyType.SOCKS5,
                host = "127.0.0.1",
                port = mockSocksServer.localPort,
                username = "res_user",
                password = "res_password",
            )

            ProxyTunnelDialer.dial(profile, "api.anthropic.com", 443, 5_000).use { tunnel ->
                tunnel.getOutputStream().write("PING\n".toByteArray())
                tunnel.getOutputStream().flush()
                val reply = BufferedReader(InputStreamReader(tunnel.getInputStream())).readLine()
                assertEquals("PONG", reply)
            }

            serverThread.join(3_000)
            assertEquals("res_user", receivedUser)
            assertEquals("res_password", receivedPass)
            assertEquals("api.anthropic.com", receivedDomain)
            assertEquals(443, receivedPort)
        }
    }
}
