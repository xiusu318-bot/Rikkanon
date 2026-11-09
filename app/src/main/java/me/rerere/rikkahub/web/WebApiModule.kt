package me.rerere.rikkahub.web

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.manifest.PluginHookKind
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.ErrorResponse
import me.rerere.rikkahub.web.dto.WebAuthTokenRequest
import me.rerere.rikkahub.web.dto.WebAuthTokenResponse
import me.rerere.rikkahub.web.routes.aiIconRoutes
import me.rerere.rikkahub.web.routes.assetsRoutes
import me.rerere.rikkahub.web.routes.conversationRoutes
import me.rerere.rikkahub.web.routes.eventsRoutes
import me.rerere.rikkahub.web.routes.filesRoutes
import me.rerere.rikkahub.web.routes.folderRoutes
import me.rerere.rikkahub.web.routes.settingsRoutes
import org.koin.mp.KoinPlatformTools
import java.security.MessageDigest

private const val WEB_JWT_ISSUER = "rikkahub-web"
private const val WEB_JWT_AUDIENCE = "rikkahub-web-client"
private const val WEB_JWT_SUBJECT = "web-access"
private const val WEB_JWT_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val WEB_ACCESS_TOKEN_QUERY_KEY = "access_token"
private const val WEB_AUTH_PROVIDER = "auth-jwt"

/**
 * Configure Web API for the Ktor application.
 * This should be called from app module when starting the web server.
 *
 * Example usage:
 * ```
 * startWebServer(port = 8080) {
 *     configureWebApi(context, chatService, conversationRepo, settingsStore, filesManager)
 * }
 * ```
 */
fun Application.configureWebApi(
    context: Context,
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
    filesManager: FilesManager
) {
    val jwtEnabled = settingsStore.settingsFlow.value.webServerJwtEnabled

    install(ContentNegotiation) {
        json(JsonInstant)
    }

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not Found", status.value))
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message, cause.status.value))
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Internal server error", 500)
            )
        }
    }

    if (jwtEnabled) {
        install(Authentication) {
            provider(WEB_AUTH_PROVIDER) {
                authenticate { context ->
                    // Dynamically read the current password on each request so that
                    // tokens signed after a password change are validated correctly.
                    val currentPassword = settingsStore.settingsFlow.value.webServerAccessPassword
                    val token = extractAccessToken(
                        authorizationHeader = context.call.request.headers[HttpHeaders.Authorization],
                        queryToken = context.call.request.queryParameters[WEB_ACCESS_TOKEN_QUERY_KEY]
                    )
                    // Keep protected routes closed when jwt is enabled but password is missing.
                    if (token != null && currentPassword.isNotBlank() && verifyWebJwt(token, currentPassword)) {
                        context.principal(WEB_AUTH_PROVIDER, UserIdPrincipal(WEB_JWT_SUBJECT))
                        return@authenticate
                    }
                    val cause = if (token == null) {
                        AuthenticationFailedCause.NoCredentials
                    } else {
                        AuthenticationFailedCause.InvalidCredentials
                    }
                    context.challenge(WEB_AUTH_PROVIDER, cause) { challenge, call ->
                        if (currentPassword.isBlank()) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                ErrorResponse("Access password is not configured", HttpStatusCode.Forbidden.value)
                            )
                        } else {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                ErrorResponse("Unauthorized", HttpStatusCode.Unauthorized.value)
                            )
                        }
                        challenge.complete()
                    }
                }
            }
        }
    }

    routing {
        route("/api") {
            post("/auth/token") {
                val settings = settingsStore.settingsFlow.value
                if (!settings.webServerJwtEnabled) {
                    throw BadRequestException("JWT auth is disabled")
                }

                val accessPassword = settings.webServerAccessPassword
                if (accessPassword.isBlank()) {
                    throw BadRequestException("Access password is not configured")
                }

                val request = call.receive<WebAuthTokenRequest>()
                if (!secureEquals(request.password, accessPassword)) {
                    throw UnauthorizedException("Invalid password")
                }

                val (token, expiresAt) = createWebJwt(accessPassword)
                call.respond(
                    HttpStatusCode.OK,
                    WebAuthTokenResponse(
                        token = token,
                        expiresAt = expiresAt
                    )
                )
            }

            aiIconRoutes(context)

            if (jwtEnabled) {
                authenticate(WEB_AUTH_PROVIDER) {
                    conversationRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                    folderRoutes(chatService, folderRepo, settingsStore)
                    eventsRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                    settingsRoutes(settingsStore)
                    filesRoutes(filesManager, context)
                    assetsRoutes(context)
                }
            } else {
                conversationRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                folderRoutes(chatService, folderRepo, settingsStore)
                eventsRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                settingsRoutes(settingsStore)
                filesRoutes(filesManager, context)
                assetsRoutes(context)
            }
        }

        // 插件通过 rikka.web.route(path, fn) 注册的路由：/plugin/{id}/{path...}
        route("/plugin/{id}/{path...}") {
            handle {
                val pluginId = call.parameters["id"].orEmpty()
                val subPath = call.parameters.getAll("path")?.joinToString("/").orEmpty()
                val host = KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<PluginHost>()
                val found = host?.findNamed(PluginHookKind.WEB, pluginId, subPath)
                    ?: host?.findNamed(PluginHookKind.WEB, pluginId, "*")
                if (found == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Plugin route not found: /plugin/$pluginId/$subPath", 404))
                    return@handle
                }
                val (rt, reg) = found
                val reqJson = buildJsonObject {
                    put("method", call.request.httpMethod.value)
                    put("path", subPath)
                    put("uri", call.request.uri)
                    put("query", buildJsonObject {
                        call.request.queryParameters.entries().forEach { (k, v) ->
                            put(k, v.joinToString(","))
                        }
                    })
                    put("headers", buildJsonObject {
                        call.request.headers.entries().forEach { (k, v) ->
                            put(k, v.joinToString(", "))
                        }
                    })
                    val bodyText = runCatching { call.receiveText() }.getOrDefault("")
                    put("body", bodyText)
                }
                val resEl = rt.invoke(PluginHookKind.WEB, reg.handlerId, listOf(reqJson))
                when (resEl) {
                    is JsonNull -> call.respondText("", status = HttpStatusCode.NoContent)
                    is JsonPrimitive -> call.respondText(resEl.content, ContentType.Text.Plain)
                    is JsonObject -> {
                        val status = HttpStatusCode.fromValue(resEl["status"]?.jsonPrimitive?.intOrNull ?: 200)
                        val headers = resEl["headers"] as? JsonObject
                        var contentType = ContentType.Application.Json
                        headers?.forEach { (k, v) ->
                            val hv = (v as? JsonPrimitive)?.content ?: v.toString()
                            if (k.equals("content-type", ignoreCase = true)) {
                                contentType = runCatching { ContentType.parse(hv) }.getOrDefault(ContentType.Text.Plain)
                            } else {
                                call.response.header(k, hv)
                            }
                        }
                        val body = when (val b = resEl["body"]) {
                            null, is JsonNull -> ""
                            is JsonPrimitive -> {
                                if (headers?.keys?.none { it.equals("content-type", true) } != false) {
                                    contentType = ContentType.Text.Plain
                                }
                                b.content
                            }
                            else -> b.toString()
                        }
                        call.respondText(body, contentType = contentType, status = status)
                    }
                    else -> call.respondText(resEl.toString(), ContentType.Application.Json)
                }
            }
        }
    }
}

private fun createWebJwt(secret: String): Pair<String, Long> {
    val now = System.currentTimeMillis()
    val expiresAt = now + WEB_JWT_TTL_MILLIS
    val token = WebJwt.sign(
        secret = secret,
        issuer = WEB_JWT_ISSUER,
        audience = WEB_JWT_AUDIENCE,
        subject = WEB_JWT_SUBJECT,
        issuedAtMillis = now,
        expiresAtMillis = expiresAt,
    )
    return token to expiresAt
}

private fun verifyWebJwt(token: String, secret: String): Boolean {
    return WebJwt.verify(
        token = token,
        secret = secret,
        issuer = WEB_JWT_ISSUER,
        audience = WEB_JWT_AUDIENCE,
        subject = WEB_JWT_SUBJECT,
    )
}

private fun extractBearerToken(authorizationHeader: String?): String? {
    if (authorizationHeader.isNullOrBlank()) return null
    val prefix = "Bearer "
    if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) return null
    return authorizationHeader.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}

private fun extractAccessToken(authorizationHeader: String?, queryToken: String?): String? {
    return extractBearerToken(authorizationHeader)
        ?: queryToken?.trim()?.takeIf { it.isNotEmpty() }
}

private fun secureEquals(left: String, right: String): Boolean {
    return MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}
