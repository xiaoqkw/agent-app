package com.example.agent

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject

class AgentWebServer(
    host: String,
    port: Int,
    private val token: String?,
    private val provider: LabelProvider,
    private val actionExecutor: ActionExecutor,
    private val refresher: () -> Unit
) : NanoHTTPD(host, port) {

    interface LabelProvider {
        fun labels(): List<UiLabel>
    }

    interface ActionExecutor {
        fun click(x: Int, y: Int): Boolean
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (!authorized(session)) return NanoHTTPD.newFixedLengthResponse(Status.UNAUTHORIZED, "text/plain", "unauthorized")

            when (session.uri) {
                "/ping" -> text("pong")
                "/refresh" -> {
                    refresher.invoke()
                    json(JSONObject().put("status", "ok").toString())
                }
                "/list" -> json(listPayload())
                "/get_coords" -> coordsPayload(session)
                "/action" -> handleAction(session)
                else -> NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Serve error", e)
            NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
        }
    }

    private fun authorized(session: IHTTPSession): Boolean {
        token ?: return true
        val queryToken = session.parameters["token"]?.firstOrNull()
        val headerToken = session.headers["x-auth-token"]
        return token == queryToken || token == headerToken
    }

    private fun coordsPayload(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toIntOrNull()
            ?: return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "missing id")
        val label = provider.labels().firstOrNull { it.id == id }
            ?: return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "id not found")
        val rect = label.rect
        val obj = JSONObject()
            .put("id", id)
            .put("x", rect.centerX())
            .put("y", rect.centerY())
            .put("w", rect.width())
            .put("h", rect.height())
            .put("text", label.text)
            .put("className", label.className)
        return json(obj.toString())
    }

    private fun handleAction(session: IHTTPSession): Response {
        val type = session.parameters["type"]?.firstOrNull() ?: return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "missing type")
        return when (type) {
            "click" -> {
                val id = session.parameters["id"]?.firstOrNull()?.toIntOrNull()
                val labels = provider.labels()
                val target = id?.let { labels.firstOrNull { l -> l.id == it } }
                val (x, y) = if (target != null) {
                    target.rect.centerX() to target.rect.centerY()
                } else {
                    val xParam = session.parameters["x"]?.firstOrNull()?.toIntOrNull()
                    val yParam = session.parameters["y"]?.firstOrNull()?.toIntOrNull()
                    if (xParam == null || yParam == null) return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "missing coords or id")
                    xParam to yParam
                }
                val ok = actionExecutor.click(x, y)
                json(JSONObject().put("status", if (ok) "ok" else "failed").put("x", x).put("y", y).toString())
            }
            else -> NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "unsupported action")
        }
    }

    private fun listPayload(): String {
        val arr = JSONArray()
        provider.labels().forEach { label ->
            val rect = label.rect
            arr.put(
                JSONObject()
                    .put("id", label.id)
                    .put("x", rect.centerX())
                    .put("y", rect.centerY())
                    .put("w", rect.width())
                    .put("h", rect.height())
                    .put("text", label.text)
                    .put("className", label.className)
            )
        }
        return JSONObject().put("count", arr.length()).put("items", arr).toString()
    }

    private fun text(body: String): Response =
        NanoHTTPD.newFixedLengthResponse(Status.OK, "text/plain", body)

    private fun json(body: String): Response =
        NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", body)

    companion object {
        private const val TAG = "AgentWebServer"
    }
}
