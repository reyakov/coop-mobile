package su.reya.coop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.KindStandard
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.ReqTarget
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.nostr.Nostr
import su.reya.coop.nostr.NostrManager
import su.reya.coop.repository.AccountRepository
import su.reya.coop.repository.AccountState
import su.reya.coop.repository.ChatRepository
import su.reya.coop.repository.MediaRepository
import su.reya.coop.repository.SettingsRepository
import su.reya.coop.viewmodel.ProfileCache
import kotlin.coroutines.resume

class FlowSubscription(private val job: Job) {
    fun cancel() {
        job.cancel()
    }
}

data class RelayLists(
    val messaging: List<RelayUrl>,
    val inbox: List<RelayUrl>,
    val outbox: List<RelayUrl>,
)

class Bootstrap private constructor(
    val scope: CoroutineScope,
    val nostr: Nostr,
    val settingsRepository: SettingsRepository,
    val accountRepository: AccountRepository,
    val chatRepository: ChatRepository,
    val profileCache: ProfileCache,
) {
    companion object {
        fun create(storage: AppStorage): Bootstrap {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val nostr = NostrManager.instance
            val settingsRepository = SettingsRepository(storage, scope)
            val mediaRepository = MediaRepository(settingsRepository)
            val accountRepository = AccountRepository(
                nostr = nostr,
                storage = storage,
                mediaRepository = mediaRepository,
                settingsRepository = settingsRepository,
                scope = scope,
                externalSignerHandler = null,
            )
            val chatRepository = ChatRepository(nostr, mediaRepository, settingsRepository, scope)
            val profileCache = ProfileCache(nostr)
            return Bootstrap(
                scope = scope,
                nostr = nostr,
                settingsRepository = settingsRepository,
                accountRepository = accountRepository,
                chatRepository = chatRepository,
                profileCache = profileCache,
            )
        }
    }

    private var notificationsJob: Job? = null
    private var dbPath: String? = null
    private var onNewMessage: ((UnsignedEvent) -> Unit)? = null

    fun start(dbPath: String, onNewMessage: (UnsignedEvent) -> Unit) {
        this.dbPath = dbPath
        this.onNewMessage = onNewMessage
        startNotificationLoop()
    }

    private fun startNotificationLoop() {
        if (notificationsJob?.isActive == true) return
        val path = dbPath ?: return
        val messageCallback = onNewMessage ?: return

        notificationsJob = scope.launch {
            runCatching {
                nostr.init(path)
                nostr.connectBootstrapRelays()
                nostr.handleNotifications(
                    onMetadataUpdate = { pubkey, metadata ->
                        scope.launch { nostr.profiles.emitMetadataUpdate(pubkey, metadata) }
                    },
                    onContactListUpdate = { contacts ->
                        scope.launch { nostr.profiles.emitContactListUpdate(contacts) }
                    },
                    onNewMessage = { event ->
                        nostr.emitNewEvent(event)
                        messageCallback(event)
                    },
                )
            }.onFailure {
                accountRepository.showError("Failed to start Nostr: ${it.message}")
            }
        }
    }

    suspend fun resume() {
        startNotificationLoop()
        nostr.waitUntilInitialized()
        nostr.client?.connect()

        val pubkey = nostr.signer.publicKeyFlow.value ?: return

        runCatching { nostr.profiles.getUserMetadata() }

        val relays = nostr.relays.getMsgRelays(pubkey)
        if (relays.isEmpty()) return

        relays.forEach { relay ->
            nostr.client?.addRelay(relay)
            nostr.client?.connectRelay(relay)
        }

        nostr.messages.updateSyncState { it.copy(isSyncing = true) }
        val filter = Filter().kind(Kind.fromStd(KindStandard.GIFT_WRAP)).pubkey(pubkey)
        val target = relays.associateWith { listOf(filter) }
        nostr.client?.subscribe(target = ReqTarget.manual(target), id = "gift-wraps")
    }

    suspend fun pause() {
        nostr.client?.disconnect()
    }

    private fun <T> Flow<T>.watch(onEach: (T) -> Unit): FlowSubscription {
        val job = scope.launch(Dispatchers.Main) { collect { onEach(it) } }
        return FlowSubscription(job)
    }

    fun watchAccountState(onEach: (AccountState) -> Unit): FlowSubscription =
        accountRepository.state.watch(onEach)

    fun watchIsUpdatingProfile(onEach: (Boolean) -> Unit): FlowSubscription =
        accountRepository.isUpdatingProfile.watch(onEach)

    fun watchCurrentUserProfile(onEach: (Profile?) -> Unit): FlowSubscription =
        accountRepository.currentUserProfile.watch(onEach)

    fun watchChatRooms(onEach: (List<Room>) -> Unit): FlowSubscription =
        chatRepository.chatRooms.watch(onEach)

    fun watchIsSyncing(onEach: (Boolean) -> Unit): FlowSubscription =
        chatRepository.isSyncing.watch(onEach)

    fun watchPartialProcessed(onEach: (Boolean) -> Unit): FlowSubscription =
        chatRepository.isPartialProcessedGiftWrap.watch(onEach)

    fun watchSettings(onEach: (Settings) -> Unit): FlowSubscription =
        settingsRepository.settings.watch(onEach)

    fun watchNewEvents(onEach: (UnsignedEvent) -> Unit): FlowSubscription =
        chatRepository.newEvents.watch(onEach)

    fun watchErrors(onEach: (String) -> Unit): FlowSubscription =
        merge(
            accountRepository.errorEvents,
            chatRepository.errorEvents,
            profileCache.errorEvents,
        ).watch(onEach)

    fun watchProfile(pubkey: PublicKey, onEach: (Profile?) -> Unit): FlowSubscription =
        profileCache.getMetadata(pubkey).watch(onEach)

    fun watchRoomUi(
        room: Room,
        currentUser: PublicKey?,
        onEach: (RoomUiState) -> Unit
    ): FlowSubscription =
        room.uiStateFlow(profileCache, currentUser).watch(onEach)

    @Throws(IllegalArgumentException::class)
    fun createChatRoom(recipients: List<PublicKey>): Long =
        chatRepository.createChatRoom(recipients)

    fun getChatRoom(id: Long): Room? = chatRepository.getChatRoom(id)

    fun markRoomRead(id: Long) = chatRepository.markAsRead(id)

    fun refreshChatRooms() = chatRepository.refreshChatRooms()

    fun connectRoom(id: Long) = chatRepository.chatRoomConnect(id)

    fun sendTextMessage(roomId: Long, text: String) =
        chatRepository.sendMessage(roomId, text, emptyList())

    fun sendReplyMessage(roomId: Long, text: String, replyTo: EventId) =
        chatRepository.sendMessage(roomId, text, listOf(replyTo))

    fun sendImageMessage(roomId: Long, file: ByteArray, contentType: String) =
        chatRepository.sendFileMessage(roomId, file, contentType, emptyList())

    suspend fun loadRoomMessages(roomId: Long): List<UnsignedEvent> =
        suspendCancellableCoroutine { cont ->
            chatRepository.loadChatRoomMessages(roomId) { cont.resume(it) }
        }

    fun importIdentity(secret: String, password: String?) =
        accountRepository.importIdentity(secret, password)

    fun createIdentity(name: String, bio: String?, picture: ByteArray?, contentType: String?) =
        accountRepository.createIdentity(name, bio, picture, contentType)

    fun updateProfile(name: String?, bio: String?, picture: ByteArray?, contentType: String?) =
        accountRepository.updateProfile(name, bio, picture, contentType)

    fun logout() = accountRepository.logout {}

    fun dismissNotificationBanner() = accountRepository.dismissNotificationBanner()

    fun addContact(address: String) = accountRepository.addContact(address)

    fun removeContact(publicKey: PublicKey) = accountRepository.removeContact(publicKey)

    suspend fun searchByAddress(query: String): PublicKey? =
        suspendCancellableCoroutine { cont ->
            accountRepository.searchByAddress(query) { cont.resume(it) }
        }

    suspend fun searchByNostr(query: String): List<PublicKey> =
        suspendCancellableCoroutine { cont ->
            accountRepository.searchByNostr(query) { cont.resume(it) }
        }

    suspend fun verifyActivity(pubkey: PublicKey): Timestamp? =
        suspendCancellableCoroutine { cont ->
            accountRepository.verifyActivity(pubkey) { cont.resume(it) }
        }

    suspend fun verifyContact(pubkey: PublicKey): Boolean =
        suspendCancellableCoroutine { cont ->
            accountRepository.verifyContact(pubkey) { cont.resume(it) }
        }

    suspend fun mutualContacts(pubkey: PublicKey): Set<PublicKey> =
        suspendCancellableCoroutine { cont ->
            accountRepository.mutualContacts(pubkey) { cont.resume(it) }
        }

    fun refetchMsgRelays() = accountRepository.refetchMsgRelays()

    fun useDefaultMsgRelayList() = accountRepository.useDefaultMsgRelayList()

    fun loadRelayLists() {
        accountRepository.loadCurrentUserRelayList()
        accountRepository.loadCurrentUserMsgRelayList()
    }

    fun watchRelayLists(onEach: (RelayLists) -> Unit): FlowSubscription =
        accountRepository.state.map { state ->
            RelayLists(
                messaging = state.userMsgRelayList,
                inbox = state.userRelayList
                    .filter { it.value == RelayMetadata.READ || it.value == null }
                    .keys.toList(),
                outbox = state.userRelayList
                    .filter { it.value == RelayMetadata.WRITE || it.value == null }
                    .keys.toList(),
            )
        }.watch(onEach)

    fun addMsgRelay(relay: String) = accountRepository.addMsgRelay(relay)

    fun removeMsgRelay(relay: String) = accountRepository.removeMsgRelay(relay)

    fun addInboxRelay(relay: String) = accountRepository.addInboxRelay(relay)

    fun addOutboxRelay(relay: String) = accountRepository.addOutboxRelay(relay)

    fun removeRelay(relay: String) = accountRepository.removeRelay(relay)

    fun setTheme(theme: Theme) = settingsRepository.update { it.copy(theme = theme) }

    fun setMediaConfig(media: MediaConfig) = settingsRepository.update { it.copy(media = media) }

    fun setScreening(enabled: Boolean) = settingsRepository.update { it.copy(screening = enabled) }

    fun setBlossomServer(url: String?) = settingsRepository.update { it.copy(blossomServer = url) }

    fun parsePublicKey(input: String): PublicKey? =
        runCatching { PublicKey.parse(input.trim()) }.getOrNull()

    fun currentPublicKey(): PublicKey? = nostr.signer.publicKeyFlow.value

    fun resetState() {
        accountRepository.resetInternalState()
        chatRepository.resetInternalState()
    }
}
