package me.rerere.rikkahub.data.remote

import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.ProxyProfile
import me.rerere.rikkahub.data.datastore.ProxyType
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.Authenticator as OkHttpAuthenticator
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.uuid.Uuid

enum class OutboundTarget {
    LLM,
    WORKSPACE,
    MCP,
}

fun NetworkSetting.resolveProxy(target: OutboundTarget): ProxyProfile? {
    val targetId: Uuid? = when (target) {
        OutboundTarget.LLM -> llmProxyId
        OutboundTarget.WORKSPACE -> workspaceProxyId
        OutboundTarget.MCP -> mcpProxyId
    }
    if (targetId != null) {
        return proxies.firstOrNull { it.id == targetId }
    }
    if (proxies.isEmpty() && proxyUrl.isNotBlank()) {
        return proxyUrl.parseProxyProfileOrNull(
            name = "默认代理",
            fallbackUsername = proxyUsername,
            fallbackPassword = proxyPassword,
        )
    }
    return null
}

/**
 * 解析各种代理输入格式为 [ProxyProfile]：
 * 1. 标准 URI：`http://host:port`、`https://user:pass@host:port`、`socks5://user:pass@host:port`、`socks5h://host:port`
 * 2. 无 scheme：`host:port`、`user:pass@host:port`（使用 [defaultType]）
 * 3. 住宅/家宽代理商常见四段式：`host:port:username:password`（其中 password 可含冒号）
 */
fun String.parseProxyProfileOrNull(
    id: Uuid = Uuid.random(),
    name: String = "",
    defaultType: ProxyType = ProxyType.HTTP,
    fallbackUsername: String = "",
    fallbackPassword: String = "",
): ProxyProfile? {
    val raw = trim()
    if (raw.isEmpty()) return null

    var explicitType: ProxyType? = null
    val withoutScheme = if ("://" in raw) {
        val scheme = raw.substringBefore("://").lowercase()
        explicitType = when (scheme) {
            "http" -> ProxyType.HTTP
            "https" -> ProxyType.HTTPS
            "socks5", "socks5h" -> ProxyType.SOCKS5
            else -> return null
        }
        raw.substringAfter("://")
    } else {
        raw
    }

    // 尝试解析家宽代理常见的 host:port:user:pass 四段格式（不含 @、不含 /、非 IPv6 裸格式）
    if ("@" !in withoutScheme && "/" !in withoutScheme && !withoutScheme.startsWith("[")) {
        val parts = withoutScheme.split(":")
        if (parts.size >= 4) {
            val candidateHost = parts[0].trim()
            val candidatePort = parts[1].trim().toIntOrNull()
            val candidateUser = parts[2]
            val candidatePass = parts.drop(3).joinToString(":")
            if (candidateHost.isNotBlank() && candidatePort != null && candidatePort in 1..65535) {
                return ProxyProfile(
                    id = id,
                    name = name,
                    type = explicitType ?: defaultType,
                    host = candidateHost,
                    port = candidatePort,
                    username = candidateUser.ifEmpty { fallbackUsername },
                    password = if (candidateUser.isNotEmpty()) candidatePass else fallbackPassword,
                )
            }
        }
    }

    val normalized = "${(explicitType ?: defaultType).scheme}://$withoutScheme"
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() } ?: return null
    val port = uri.port.takeIf { it in 1..65535 } ?: return null
    if (uri.query != null || uri.fragment != null) return null
    if (uri.path?.let { it.isNotEmpty() && it != "/" } == true) return null

    var parsedUser = fallbackUsername
    var parsedPass = fallbackPassword
    val rawUserInfo = uri.rawUserInfo
    if (rawUserInfo != null) {
        if (rawUserInfo.isEmpty()) return null
        val userPart = rawUserInfo.substringBefore(":")
        val passPart = if (":" in rawUserInfo) rawUserInfo.substringAfter(":") else ""
        parsedUser = runCatching { URLDecoder.decode(userPart, "UTF-8") }.getOrDefault(userPart)
        parsedPass = runCatching { URLDecoder.decode(passPart, "UTF-8") }.getOrDefault(passPart)
    }

    return ProxyProfile(
        id = id,
        name = name,
        type = explicitType ?: defaultType,
        host = host,
        port = port,
        username = parsedUser,
        password = parsedPass,
    )
}

fun ProxyProfile.isValid(): Boolean =
    host.isNotBlank() && port in 1..65535 && "://" !in host && "/" !in host && " " !in host

fun ProxyProfile.toJavaProxy(): Proxy {
    val javaType = when (type) {
        ProxyType.HTTP, ProxyType.HTTPS -> Proxy.Type.HTTP
        ProxyType.SOCKS5 -> Proxy.Type.SOCKS
    }
    return Proxy(javaType, InetSocketAddress.createUnresolved(host.trim(), port))
}

/**
 * 兼容旧单测与旧调用点：支持 `http://`、`https://`、`socks5://`、`socks5h://` 及无协议头 `host:port`。
 */
fun String.toProxyOrNull(): Proxy? {
    val value = trim()
    if (value.isEmpty()) return null
    val normalizedValue = if ("://" in value) value else "http://$value"
    val uri = runCatching { URI(normalizedValue) }.getOrNull() ?: return null
    if (uri.userInfo != null) return null
    return parseProxyProfileOrNull()?.toJavaProxy()
}

/**
 * 为指定 [ProxyProfile] 创建 SocketFactory：
 * - 当 `type == ProxyType.HTTPS` 时，连接代理服务器后立即叠加一层 TLS + SNI 握手，供上层发送加密 CONNECT 隧道请求。
 * - 当 `type == ProxyType.HTTP` 时，走普通 TCP Socket。
 */
class OutboundProxySocketFactory(
    private val profileProvider: () -> ProxyProfile?,
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) : SocketFactory() {
    override fun createSocket(): Socket {
        val profile = profileProvider()
        return if (profile != null && profile.type == ProxyType.HTTPS) {
            TlsProxyTunnelSocket(
                proxyHost = profile.host.trim(),
                proxyPort = profile.port,
                sslSocketFactory = sslSocketFactory,
            )
        } else {
            Socket()
        }
    }

    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

/**
 * 先建立到 HTTPS 代理服务器的 TCP 连接，随后立即升级为 TLS 握手（带 SNI），
 * 并将读写流透明代理给内部的 [SSLSocket]，从而让 OkHttp 在 TLS 通道内发送 HTTP CONNECT 请求。
 */
internal class TlsProxyTunnelSocket(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val sslSocketFactory: SSLSocketFactory,
) : Socket() {
    private val rawTcpSocket = Socket()

    @Volatile
    private var sslSocket: SSLSocket? = null

    override fun connect(endpoint: SocketAddress?) {
        connect(endpoint, 0)
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val targetEndpoint = endpoint ?: InetSocketAddress(proxyHost, proxyPort)
        rawTcpSocket.connect(targetEndpoint, timeout)
        val upgraded = sslSocketFactory.createSocket(
            rawTcpSocket,
            proxyHost,
            proxyPort,
            true,
        ) as SSLSocket
        runCatching {
            val params: SSLParameters = upgraded.sslParameters
            params.serverNames = listOf(SNIHostName(proxyHost))
            upgraded.sslParameters = params
        }
        upgraded.startHandshake()
        sslSocket = upgraded
    }

    override fun getInputStream(): InputStream =
        (sslSocket ?: rawTcpSocket).inputStream

    override fun getOutputStream(): OutputStream =
        (sslSocket ?: rawTcpSocket).outputStream

    override fun isConnected(): Boolean =
        sslSocket?.isConnected ?: rawTcpSocket.isConnected

    override fun isClosed(): Boolean =
        sslSocket?.isClosed ?: rawTcpSocket.isClosed

    override fun setSoTimeout(timeout: Int) {
        rawTcpSocket.soTimeout = timeout
        sslSocket?.soTimeout = timeout
    }

    override fun getSoTimeout(): Int =
        sslSocket?.soTimeout ?: rawTcpSocket.soTimeout

    override fun setTcpNoDelay(on: Boolean) {
        rawTcpSocket.tcpNoDelay = on
        sslSocket?.tcpNoDelay = on
    }

    override fun setKeepAlive(on: Boolean) {
        rawTcpSocket.keepAlive = on
        sslSocket?.keepAlive = on
    }

    override fun getInetAddress(): InetAddress? =
        sslSocket?.inetAddress ?: rawTcpSocket.inetAddress

    override fun getRemoteSocketAddress(): SocketAddress? =
        sslSocket?.remoteSocketAddress ?: rawTcpSocket.remoteSocketAddress

    override fun getLocalAddress(): InetAddress =
        sslSocket?.localAddress ?: rawTcpSocket.localAddress

    override fun getLocalPort(): Int =
        sslSocket?.localPort ?: rawTcpSocket.localPort

    override fun getPort(): Int =
        sslSocket?.port ?: rawTcpSocket.port

    @Synchronized
    override fun close() {
        runCatching { sslSocket?.close() }
        runCatching { rawTcpSocket.close() }
        super.close()
    }
}

/**
 * 动态 DNS：
 * 当当前出站为 HTTP / HTTPS 代理且解析的是该代理自身的域名时，才走系统 DNS；
 * 当当前出站为 SOCKS5 时，OkHttp 5 的 `RouteSelector` 对 SOCKS 默认使用 `InetSocketAddress.createUnresolved(host, port)`
 * 把目标域名原样交给远端 SOCKS5 代理（即 `socks5h` 行为，不泄露本地 DNS）。
 */
class OutboundProxyDns(
    private val systemDns: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = systemDns.lookup(hostname)
}

class SettingsProxyAuthenticator(
    private val profileProvider: () -> ProxyProfile?,
) : OkHttpAuthenticator {
    constructor(
        settingsStore: SettingsStore,
        target: OutboundTarget = OutboundTarget.LLM,
    ) : this({ settingsStore.settingsFlow.value.networkSetting.resolveProxy(target) })

    override fun authenticate(route: Route?, response: Response): Request? {
        val profile = profileProvider() ?: return null
        if (profile.type != ProxyType.HTTP && profile.type != ProxyType.HTTPS) return null
        if (profile.username.isEmpty()) return null
        val credential = Credentials.basic(profile.username, profile.password, Charsets.UTF_8)
        // 支持 OkHttp-Preemptive 首包主动携带认证，同时防止 407 死循环重试
        if (response.request.header("Proxy-Authorization") == credential) return null

        return response.request.newBuilder()
            .header("Proxy-Authorization", credential)
            .build()
    }
}

class SettingsSocks5Authenticator(
    private val settingsStore: SettingsStore,
) : java.net.Authenticator() {
    override fun getPasswordAuthentication(): PasswordAuthentication? {
        if (!requestingProtocol.equals("SOCKS5", ignoreCase = true)) return null
        val setting = settingsStore.settingsFlow.value.networkSetting
        val reqHost = requestingHost?.removeSurrounding("[", "]")

        // 先匹配所有已配置的 SOCKS5 节点（支持 LLM / 工作区 / MCP 各自走不同 SOCKS5 节点）
        val candidates = buildList {
            setting.resolveProxy(OutboundTarget.LLM)?.let(::add)
            setting.resolveProxy(OutboundTarget.WORKSPACE)?.let(::add)
            setting.resolveProxy(OutboundTarget.MCP)?.let(::add)
            addAll(setting.proxies)
        }.filter { it.type == ProxyType.SOCKS5 && it.username.isNotEmpty() }

        val matched = candidates.firstOrNull { profile ->
            profile.port == requestingPort &&
                profile.host.trim().removeSurrounding("[", "]").equals(reqHost, ignoreCase = true)
        } ?: return null

        return PasswordAuthentication(matched.username, matched.password.toCharArray())
    }
}

class SettingsProxySelector(
    private val profileProvider: () -> ProxyProfile?,
    private val systemProxySelector: ProxySelector? = ProxySelector.getDefault(),
) : ProxySelector() {
    constructor(
        settingsStore: SettingsStore,
        target: OutboundTarget = OutboundTarget.LLM,
        systemProxySelector: ProxySelector? = ProxySelector.getDefault(),
    ) : this(
        profileProvider = { settingsStore.settingsFlow.value.networkSetting.resolveProxy(target) },
        systemProxySelector = systemProxySelector,
    )

    override fun select(uri: URI): List<Proxy> {
        val host = uri.host?.lowercase()
        // 本地回环地址永不走代理
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]") {
            return listOf(Proxy.NO_PROXY)
        }
        profileProvider()?.takeIf { it.isValid() }?.let { profile ->
            return listOf(profile.toJavaProxy())
        }
        return listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, exception: IOException) {
        if (profileProvider() == null) {
            systemProxySelector?.connectFailed(uri, socketAddress, exception)
        }
    }
}

/**
 * 为 `OkHttpClient.Builder` 挂载支持 HTTP / 真 HTTPS / SOCKS5(h) 的动态代理配置。
 */
fun OkHttpClient.Builder.configureOutboundProxy(
    profileProvider: () -> ProxyProfile?,
): OkHttpClient.Builder {
    return this
        .socketFactory(OutboundProxySocketFactory(profileProvider))
        .dns(OutboundProxyDns())
        .proxySelector(SettingsProxySelector(profileProvider))
        .proxyAuthenticator(SettingsProxyAuthenticator(profileProvider))
}

/**
 * 直接使用单个 [ProxyProfile] 建立底层传输隧道连接目标 `targetHost:targetPort`：
 * - 支持 `HTTP`（首包主动带 `Proxy-Authorization`）
 * - 支持 `HTTPS`（先与代理完成 TLS + SNI 握手，再发 `CONNECT`）
 * - 支持 `SOCKS5`（纯手写 RFC 1928 / RFC 1929 域名 ATYP=0x03 握手，远端 DNS 解析，不依赖全局 Authenticator）
 *
 * 供 SSH 工作区跳板、本地 PRoot 沙盒回环桥接器以及代理面板内独立测速共用。
 */
object ProxyTunnelDialer {
    fun dial(
        profile: ProxyProfile,
        targetHost: String,
        targetPort: Int,
        connectTimeoutMs: Int = 15_000,
    ): Socket {
        return when (profile.type) {
            ProxyType.HTTP -> dialHttpConnect(
                rawSocket = Socket(),
                profile = profile,
                targetHost = targetHost,
                targetPort = targetPort,
                connectTimeoutMs = connectTimeoutMs,
            )

            ProxyType.HTTPS -> {
                val tlsSocket = TlsProxyTunnelSocket(
                    proxyHost = profile.host.trim(),
                    proxyPort = profile.port,
                    sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
                )
                dialHttpConnect(
                    rawSocket = tlsSocket,
                    profile = profile,
                    targetHost = targetHost,
                    targetPort = targetPort,
                    connectTimeoutMs = connectTimeoutMs,
                )
            }

            ProxyType.SOCKS5 -> dialSocks5(
                profile = profile,
                targetHost = targetHost,
                targetPort = targetPort,
                connectTimeoutMs = connectTimeoutMs,
            )
        }
    }

    private fun dialHttpConnect(
        rawSocket: Socket,
        profile: ProxyProfile,
        targetHost: String,
        targetPort: Int,
        connectTimeoutMs: Int,
    ): Socket {
        try {
            rawSocket.soTimeout = connectTimeoutMs
            rawSocket.connect(InetSocketAddress(profile.host.trim(), profile.port), connectTimeoutMs)
            val authority = if (":" in targetHost && !targetHost.startsWith("[")) {
                "[$targetHost]:$targetPort"
            } else {
                "$targetHost:$targetPort"
            }
            val request = buildString {
                append("CONNECT $authority HTTP/1.1\r\n")
                append("Host: $authority\r\n")
                append("Proxy-Connection: Keep-Alive\r\n")
                if (profile.username.isNotEmpty()) {
                    val basic = Credentials.basic(profile.username, profile.password, Charsets.UTF_8)
                    append("Proxy-Authorization: $basic\r\n")
                }
                append("\r\n")
            }
            val output = rawSocket.getOutputStream()
            output.write(request.toByteArray(Charsets.ISO_8859_1))
            output.flush()

            val input = rawSocket.getInputStream()
            val statusLine = readAsciiLine(input)
            val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("Invalid proxy response: $statusLine")
            while (true) {
                val line = readAsciiLine(input)
                if (line.isEmpty()) break
            }
            if (statusCode != 200) {
                throw IOException("Proxy CONNECT failed: $statusLine")
            }
            return rawSocket
        } catch (e: Throwable) {
            runCatching { rawSocket.close() }
            throw if (e is IOException) e else IOException(e.message ?: "Proxy CONNECT error", e)
        }
    }

    private fun dialSocks5(
        profile: ProxyProfile,
        targetHost: String,
        targetPort: Int,
        connectTimeoutMs: Int,
    ): Socket {
        val socket = Socket()
        try {
            socket.soTimeout = connectTimeoutMs
            socket.connect(InetSocketAddress(profile.host.trim(), profile.port), connectTimeoutMs)
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val hasAuth = profile.username.isNotEmpty()
            if (hasAuth) {
                output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            } else {
                output.write(byteArrayOf(0x05, 0x01, 0x00))
            }
            output.flush()

            val greeting = readExact(input, 2)
            if (greeting[0].toInt() != 0x05) {
                throw IOException("Invalid SOCKS5 version: ${greeting[0]}")
            }
            when (val method = greeting[1].toInt() and 0xFF) {
                0x00 -> Unit
                0x02 -> {
                    val userBytes = profile.username.toByteArray(Charsets.UTF_8)
                    val passBytes = profile.password.toByteArray(Charsets.UTF_8)
                    require(userBytes.size <= 255 && passBytes.size <= 255) {
                        "SOCKS5 username/password too long"
                    }
                    val authPacket = ByteArray(3 + userBytes.size + passBytes.size)
                    authPacket[0] = 0x01
                    authPacket[1] = userBytes.size.toByte()
                    System.arraycopy(userBytes, 0, authPacket, 2, userBytes.size)
                    authPacket[2 + userBytes.size] = passBytes.size.toByte()
                    System.arraycopy(passBytes, 0, authPacket, 3 + userBytes.size, passBytes.size)
                    output.write(authPacket)
                    output.flush()

                    val authReply = readExact(input, 2)
                    if (authReply[1].toInt() != 0x00) {
                        throw IOException("SOCKS5 authentication failed")
                    }
                }

                else -> throw IOException("SOCKS5 proxy rejected authentication methods (code=$method)")
            }

            // 始终使用 ATYP = 0x03 (DOMAINNAME) 发送目标域名/IP，实现 socks5h 远端 DNS 解析
            val cleanHost = targetHost.removeSurrounding("[", "]")
            val hostBytes = cleanHost.toByteArray(Charsets.UTF_8)
            require(hostBytes.size in 1..255) { "Invalid target host: $targetHost" }
            val connectPacket = ByteArray(7 + hostBytes.size)
            connectPacket[0] = 0x05
            connectPacket[1] = 0x01 // CONNECT
            connectPacket[2] = 0x00 // RSV
            connectPacket[3] = 0x03 // ATYP = DOMAINNAME
            connectPacket[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, connectPacket, 5, hostBytes.size)
            connectPacket[5 + hostBytes.size] = ((targetPort ushr 8) and 0xFF).toByte()
            connectPacket[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
            output.write(connectPacket)
            output.flush()

            val replyHeader = readExact(input, 4)
            val rep = replyHeader[1].toInt() and 0xFF
            if (rep != 0x00) {
                throw IOException("SOCKS5 CONNECT failed with code $rep")
            }
            when (val atyp = replyHeader[3].toInt() and 0xFF) {
                0x01 -> readExact(input, 4 + 2)
                0x03 -> {
                    val len = readExact(input, 1)[0].toInt() and 0xFF
                    readExact(input, len + 2)
                }

                0x04 -> readExact(input, 16 + 2)
                else -> throw IOException("SOCKS5 unknown address type: $atyp")
            }
            return socket
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw if (e is IOException) e else IOException(e.message ?: "SOCKS5 error", e)
        }
    }

    internal fun readAsciiLine(input: InputStream): String {
        val buf = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) throw IOException("Unexpected EOF from proxy")
                break
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                buf.write(b)
                if (buf.size() > 16 * 1024) throw IOException("Proxy header line too long")
            }
        }
        return buf.toString(Charsets.ISO_8859_1.name())
    }

    internal fun readExact(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw IOException("Unexpected EOF during proxy handshake")
            offset += read
        }
        return bytes
    }
}

/**
 * 本地沙盒（PRoot）专用回环代理桥接器：
 * - 在 `127.0.0.1` 随机端口监听，同时识别客户端发来的 **HTTP (CONNECT + 普通请求)** 与 **SOCKS5** 协议；
 * - 内部根据 `profileProvider()` 当前选中的工作区出站节点：
 *   - 若选了代理（HTTP / 真 HTTPS / SOCKS5），通过 [ProxyTunnelDialer] 透明转发并自动完成上游鉴权与 TLS/SOCKS5h 封装；
 *   - 若未选代理（直连），直接向目标地址发起 TCP 连接（因此用户在 UI 随时把工作区出站切为“不使用代理”或切到另一个代理时，
 *     **已经打开的持久化终端无需重启即可立即切换出站**，且沙盒环境变量内永远不会暴露真实的代理 IP 与账号密码）。
 */
object WorkspaceLoopbackProxyBridge {
    private val started = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "workspace-proxy-bridge").apply { isDaemon = true }
    }

    @Volatile
    var profileProvider: () -> ProxyProfile? = { null }

    val port: Int
        get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun ensureStarted(provider: () -> ProxyProfile?): Int {
        profileProvider = provider
        if (started.get() && serverSocket?.isClosed == false) {
            return serverSocket!!.localPort
        }
        val server = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        started.set(true)
        executor.execute { acceptLoop(server) }
        return server.localPort
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            executor.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = client.getInputStream()
            val firstByte = input.read()
            if (firstByte < 0) {
                client.close()
                return
            }
            if (firstByte == 0x05) {
                handleSocks5Client(client, input)
            } else {
                handleHttpClient(client, PrependInputStream(firstByte, input))
            }
        } catch (_: Throwable) {
            runCatching { client.close() }
        }
    }

    private fun handleSocks5Client(client: Socket, input: InputStream) {
        val output = client.getOutputStream()
        val nMethods = ProxyTunnelDialer.readExact(input, 1)[0].toInt() and 0xFF
        if (nMethods > 0) {
            ProxyTunnelDialer.readExact(input, nMethods)
        }
        // 本地回环免密响应
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        val reqHeader = ProxyTunnelDialer.readExact(input, 4)
        val ver = reqHeader[0].toInt() and 0xFF
        val cmd = reqHeader[1].toInt() and 0xFF
        if (ver != 0x05 || (cmd != 0x01 && cmd != 0xF0)) {
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            client.close()
            return
        }
        val targetHost = when (val atyp = reqHeader[3].toInt() and 0xFF) {
            0x01 -> InetAddress.getByAddress(ProxyTunnelDialer.readExact(input, 4)).hostAddress ?: ""
            0x03 -> {
                val len = ProxyTunnelDialer.readExact(input, 1)[0].toInt() and 0xFF
                String(ProxyTunnelDialer.readExact(input, len), Charsets.UTF_8)
            }

            0x04 -> InetAddress.getByAddress(ProxyTunnelDialer.readExact(input, 16)).hostAddress ?: ""
            else -> {
                client.close()
                return
            }
        }
        val portBytes = ProxyTunnelDialer.readExact(input, 2)
        val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        // CMD = 0xF0: 沙盒底层 getaddrinfo 发起的远端 DNS 查询（通过当前代理走 DoH 解析真实公网 IPv4，彻底消除 Fake-IP 28.0.x.x）
        if (cmd == 0xF0) {
            val ipBytes = resolveRemoteIpv4(targetHost)
            if (ipBytes != null && ipBytes.size == 4) {
                output.write(
                    byteArrayOf(
                        0x05,
                        0x00,
                        0x00,
                        0x01,
                        ipBytes[0],
                        ipBytes[1],
                        ipBytes[2],
                        ipBytes[3],
                        0x00,
                        0x00,
                    )
                )
            } else {
                output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            }
            output.flush()
            client.close()
            return
        }

        val remote = try {
            connectUpstream(targetHost, targetPort)
        } catch (e: Throwable) {
            runCatching {
                output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
            }
            client.close()
            return
        }

        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
        client.soTimeout = 0
        remote.soTimeout = 0
        pipeBidirectional(client, input, output, remote, remote.getInputStream(), remote.getOutputStream())
    }

    private fun handleHttpClient(client: Socket, input: InputStream) {
        val output = client.getOutputStream()
        val requestLine = ProxyTunnelDialer.readAsciiLine(input)
        val parts = requestLine.split(' ')
        if (parts.size < 3) {
            client.close()
            return
        }
        val method = parts[0]
        val rawTarget = parts[1]
        val version = parts[2]

        val headers = mutableListOf<String>()
        var hostHeader: String? = null
        while (true) {
            val line = ProxyTunnelDialer.readAsciiLine(input)
            if (line.isEmpty()) break
            if (line.startsWith("Host:", ignoreCase = true)) {
                hostHeader = line.substringAfter(':').trim()
            }
            if (!line.startsWith("Proxy-", ignoreCase = true)) {
                headers += line
            }
        }

        if (method.equals("CONNECT", ignoreCase = true)) {
            val (targetHost, targetPort) = parseHostPort(rawTarget, 443)
            val remote = try {
                connectUpstream(targetHost, targetPort)
            } catch (e: Throwable) {
                runCatching {
                    output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                }
                client.close()
                return
            }
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            client.soTimeout = 0
            remote.soTimeout = 0
            pipeBidirectional(client, input, output, remote, remote.getInputStream(), remote.getOutputStream())
        } else {
            val uri = runCatching { URI(rawTarget) }.getOrNull()
            val defaultPort = if (uri?.scheme.equals("https", ignoreCase = true)) 443 else 80
            val (targetHost, targetPort) = if (uri?.host != null) {
                uri.host.removeSurrounding("[", "]") to (uri.port.takeIf { it > 0 } ?: defaultPort)
            } else {
                parseHostPort(hostHeader ?: "", 80)
            }
            if (targetHost.isBlank()) {
                client.close()
                return
            }
            val relativePath = if (uri != null && uri.rawPath != null) {
                buildString {
                    append(uri.rawPath.ifEmpty { "/" })
                    uri.rawQuery?.let { append('?').append(it) }
                }
            } else {
                rawTarget
            }
            val remote = try {
                connectUpstream(targetHost, targetPort)
            } catch (e: Throwable) {
                runCatching {
                    output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                }
                client.close()
                return
            }
            val remoteOut = remote.getOutputStream()
            val rewritten = buildString {
                append("$method $relativePath $version\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }
            remoteOut.write(rewritten.toByteArray(Charsets.ISO_8859_1))
            remoteOut.flush()
            client.soTimeout = 0
            remote.soTimeout = 0
            pipeBidirectional(client, input, output, remote, remote.getInputStream(), remoteOut)
        }
    }

    private val dohCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val ipv4Regex = Regex("""\"data\"\s*:\s*\"(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\"""")

    /**
     * 通过当前工作区代理向 `1.1.1.1:853` / `8.8.8.8:443` 发起远端 DoH 查询，
     * 返回真实公网 IPv4（4 字节），彻底避免本机 TUN VPN 返回 `28.0.x.x` / `198.18.x.x` 等 Fake-IP。
     */
    private fun resolveRemoteIpv4(domain: String): ByteArray? {
        val clean = domain.trim().lowercase().removeSurrounding("[", "]")
        if (clean.isEmpty()) return null
        dohCache[clean]?.let { return it }

        val resolved = runCatching {
            connectUpstream("1.1.1.1", 443).use { rawTunnel ->
                rawTunnel.soTimeout = 4_000
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (sslFactory.createSocket(rawTunnel, "cloudflare-dns.com", 443, true) as SSLSocket).use { ssl ->
                    runCatching {
                        val params = ssl.sslParameters
                        params.serverNames = listOf(SNIHostName("cloudflare-dns.com"))
                        ssl.sslParameters = params
                    }
                    ssl.startHandshake()
                    val encodedName = java.net.URLEncoder.encode(clean, "UTF-8")
                    val req = "GET /dns-query?name=$encodedName&type=A HTTP/1.1\r\n" +
                        "Host: cloudflare-dns.com\r\n" +
                        "Accept: application/dns-json\r\n" +
                        "Connection: close\r\n\r\n"
                    ssl.outputStream.write(req.toByteArray(Charsets.ISO_8859_1))
                    ssl.outputStream.flush()
                    val responseText = ssl.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val ipStr = ipv4Regex.find(responseText)?.groupValues?.getOrNull(1) ?: return@use null
                    InetAddress.getByName(ipStr).address.takeIf { it.size == 4 }
                }
            }
        }.getOrNull()

        if (resolved != null) {
            dohCache[clean] = resolved
        }
        return resolved
    }

    private fun connectUpstream(targetHost: String, targetPort: Int): Socket {
        val cleanHost = targetHost.removeSurrounding("[", "]")
        val isLocal = cleanHost.equals("localhost", ignoreCase = true) ||
            cleanHost == "127.0.0.1" ||
            cleanHost == "::1"
        val profile = if (isLocal) null else profileProvider()?.takeIf { it.isValid() }
        return if (profile != null) {
            ProxyTunnelDialer.dial(profile, cleanHost, targetPort, connectTimeoutMs = 15_000)
        } else {
            Socket().apply {
                connect(InetSocketAddress(cleanHost, targetPort), 15_000)
            }
        }
    }

    private fun parseHostPort(authority: String, defaultPort: Int): Pair<String, Int> {
        val trimmed = authority.trim()
        if (trimmed.startsWith("[")) {
            val closeIdx = trimmed.indexOf(']')
            if (closeIdx > 0) {
                val host = trimmed.substring(1, closeIdx)
                val port = trimmed.substring(closeIdx + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                return host to port
            }
        }
        val colon = trimmed.lastIndexOf(':')
        return if (colon > 0) {
            trimmed.substring(0, colon) to (trimmed.substring(colon + 1).toIntOrNull() ?: defaultPort)
        } else {
            trimmed to defaultPort
        }
    }

    private fun pipeBidirectional(
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        remoteSocket: Socket,
        remoteIn: InputStream,
        remoteOut: OutputStream,
    ) {
        executor.execute {
            try {
                clientIn.copyTo(remoteOut)
                remoteOut.flush()
                runCatching { remoteSocket.shutdownOutput() }
            } catch (_: Throwable) {
                runCatching { remoteSocket.close() }
                runCatching { clientSocket.close() }
            }
        }
        try {
            remoteIn.copyTo(clientOut)
            clientOut.flush()
        } catch (_: Throwable) {
        } finally {
            runCatching { remoteSocket.close() }
            runCatching { clientSocket.close() }
        }
    }

    private class PrependInputStream(
        private var firstByte: Int,
        inStream: InputStream,
    ) : FilterInputStream(inStream) {
        override fun read(): Int {
            if (firstByte >= 0) {
                val b = firstByte
                firstByte = -1
                return b
            }
            return super.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            if (firstByte >= 0) {
                b[off] = firstByte.toByte()
                firstByte = -1
                return 1
            }
            return super.read(b, off, len)
        }
    }
}
