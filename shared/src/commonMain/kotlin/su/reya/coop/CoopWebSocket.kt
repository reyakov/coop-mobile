package su.reya.coop

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import rust.nostr.sdk.ConnectionMode
import rust.nostr.sdk.CustomWebSocketTransport
import rust.nostr.sdk.WebSocketAdapter
import rust.nostr.sdk.WebSocketAdapterWrapper
import rust.nostr.sdk.WebSocketMessage

class KtorWebSocketAdapter(
    private val client: HttpClient,
    private val session: DefaultClientWebSocketSession
) : WebSocketAdapter {

    override suspend fun send(msg: WebSocketMessage) {
        try {
            when (msg) {
                is WebSocketMessage.Text -> session.send(Frame.Text(msg.text))
                is WebSocketMessage.Binary -> session.send(Frame.Binary(true, msg.bytes))
                is WebSocketMessage.Ping -> session.send(Frame.Ping(msg.bytes))
                is WebSocketMessage.Pong -> session.send(Frame.Pong(msg.bytes))
                else -> {}
            }
        } catch (e: Exception) {
            println("Attempted to send on a closed WebSocket: ${e.message}")
            throw e
        }
    }

    override suspend fun recv(): WebSocketMessage? {
        return try {
            when (val frame = session.incoming.receive()) {
                is Frame.Text -> WebSocketMessage.Text(frame.readText())
                is Frame.Binary -> WebSocketMessage.Binary(frame.readBytes())
                is Frame.Ping -> WebSocketMessage.Ping(frame.readBytes())
                is Frame.Pong -> WebSocketMessage.Pong(frame.readBytes())
                else -> null
            }
        } catch (e: ClosedReceiveChannelException) {
            null
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun closeConnection() {
        session.cancel()
        session.close()
    }
}

class CoopWebSocketClient(private val httpClient: HttpClient) : CustomWebSocketTransport {
    override fun supportPing(): Boolean = false

    override suspend fun connect(url: String, mode: ConnectionMode): WebSocketAdapterWrapper {
        try {
            val session = httpClient.webSocketSession {
                url(url)
            }
            val adapter = KtorWebSocketAdapter(httpClient, session)
            return WebSocketAdapterWrapper(adapter)
        } catch (e: Exception) {
            throw e
        }
    }
}
