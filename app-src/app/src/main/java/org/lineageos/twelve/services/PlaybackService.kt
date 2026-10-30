/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.services

import android.app.PendingIntent
import android.content.Intent
import android.content.res.Resources
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.listen
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.twelve.MainActivity
import org.lineageos.twelve.R
import org.lineageos.twelve.TwelveApplication
import org.lineageos.twelve.ext.Bundle
import org.lineageos.twelve.ext.enableFloatOutput
import org.lineageos.twelve.ext.enableOffload
import org.lineageos.twelve.ext.mapAsync
import org.lineageos.twelve.ext.mediaItems
import org.lineageos.twelve.ext.next
import org.lineageos.twelve.ext.skipSilence
import org.lineageos.twelve.ext.stopPlaybackOnTaskRemoved
import org.lineageos.twelve.ext.typedRepeatMode
import org.lineageos.twelve.models.RepeatMode
import org.lineageos.twelve.ui.widgets.NowPlayingAppWidgetProvider

@OptIn(UnstableApi::class)
/**
 * How many upcoming queue entries are resolved ahead of playback.
 *
 * Resolving the whole queue made start-up block on one network round trip per track; resolving
 * only the current one made the player skip forward whenever the next entry had no URL yet.
 * A small lookahead keeps playback gapless in both directions.
 */
private const val RESOLVE_LOOKAHEAD = 3

/**
 * Total budget for cached audio.
 *
 * Apple's streams cost a network round trip and a licence per track, so replaying from disk is the
 * main way to make repeat listening feel instant. Too small a budget would evict tracks the user
 * is still rotating through, hence a generous ceiling; LRU keeps it bounded.
 */
private const val MEDIA_CACHE_BYTES = 5L * 1024 * 1024 * 1024
class PlaybackService : MediaLibraryService() {
    enum class CustomCommand {
        /**
         * Toggles audio offload mode.
         *
         * Arguments:
         * - [CustomCommand.ARG_VALUE] ([Boolean]): Whether to enable or disable offload
         */
        TOGGLE_OFFLOAD,

        /**
         * Toggles skip silence.
         *
         * Arguments:
         * - [CustomCommand.ARG_VALUE] ([Boolean]): Whether to enable or disable skip silence
         */
        TOGGLE_SKIP_SILENCE,

        /**
         * Get the audio session ID.
         *
         * Response:
         * - [CustomCommand.RSP_VALUE] ([Int]): The audio session ID
         */
        GET_AUDIO_SESSION_ID,

        /**
         * Toggle shuffle mode.
         *
         * Arguments:
         * - [CustomCommand.ARG_VALUE] ([Boolean]): Whether to enable or disable shuffle mode
         */
        TOGGLE_SHUFFLE {
            override fun buildCommandButton(
                player: ExoPlayer,
                resources: Resources,
            ) = player.shuffleModeEnabled.let { shuffleModeEnabled ->
                val (icon, titleStringResId) = when (shuffleModeEnabled) {
                    true -> CommandButton.ICON_SHUFFLE_ON to R.string.shuffle_on
                    false -> CommandButton.ICON_SHUFFLE_OFF to R.string.shuffle_off
                }

                CommandButton.Builder(icon)
                    .setDisplayName(resources.getString(titleStringResId))
                    .setSessionCommand(
                        SessionCommand(
                            name,
                            Bundle {
                                putBoolean(ARG_VALUE, !shuffleModeEnabled)
                            },
                        )
                    )
                    .build()
            }
        },

        /**
         * Toggle repeat mode.
         *
         * Arguments:
         * - [CustomCommand.ARG_VALUE] ([String]): The repeat mode
         */
        TOGGLE_REPEAT {
            override fun buildCommandButton(
                player: ExoPlayer,
                resources: Resources,
            ) = player.typedRepeatMode.let { repeatMode ->
                val (icon, titleStringResId) = when (repeatMode) {
                    RepeatMode.NONE -> CommandButton.ICON_REPEAT_OFF to R.string.repeat_off
                    RepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL to R.string.repeat_all
                    RepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE to R.string.repeat_one
                }

                CommandButton.Builder(icon)
                    .setDisplayName(resources.getString(titleStringResId))
                    .setSessionCommand(
                        SessionCommand(
                            name,
                            Bundle {
                                putString(ARG_VALUE, repeatMode.next().name)
                            },
                        )
                    )
                    .build()
            }
        };

        val sessionCommand = SessionCommand(name, Bundle.EMPTY)

        open fun buildCommandButton(player: ExoPlayer, resources: Resources): CommandButton? = null

        companion object {
            const val ARG_VALUE = "value"
            const val RSP_VALUE = "value"

            fun fromCustomAction(
                customAction: String
            ) = entries.firstOrNull { it.name == customAction }

            suspend fun MediaController.sendCustomCommand(
                customCommand: CustomCommand,
                extras: Bundle
            ) = sendCustomCommand(customCommand.sessionCommand, extras).await()
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession

    private val mediaRepositoryTree by lazy {
        MediaRepositoryTree(
            applicationContext,
            mediaRepository,
            providersRepository,
        )
    }

    private val sharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    private val resumptionPlaylistRepository by lazy {
        (application as TwelveApplication).resumptionPlaylistRepository
    }

    private val mediaRepository by lazy {
        (application as TwelveApplication).mediaRepository
    }

    private val providersRepository by lazy {
        (application as TwelveApplication).providersRepository
    }

    private val outputConfigurationRepository by lazy {
        (application as TwelveApplication).outputConfigurationRepository
    }

    private val analyticsListener by lazy {
        object : AnalyticsListener {
            override fun onEvents(player: Player, events: AnalyticsListener.Events) {
                if (events.contains(AnalyticsListener.EVENT_AUDIO_INPUT_FORMAT_CHANGED)) {
                    outputConfigurationRepository.updateFormat(
                        this@PlaybackService.player.audioFormat
                    )
                }
            }
        }
    }

    private val mediaLibrarySessionCallback = object : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .apply {
                        for (command in CustomCommand.entries) {
                            add(command.sessionCommand)
                        }
                    }
                    .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaId: String,
            rating: Rating
        ) = lifecycleScope.future {
            val heartRating = rating as? HeartRating ?: return@future SessionResult(
                SessionError.ERROR_NOT_SUPPORTED
            )

            SessionResult(
                when (mediaRepositoryTree.setFavorite(mediaId, heartRating.isHeart)) {
                    true -> {
                        // Horrible.
                        player.mediaItems.forEachIndexed { index, mediaItem ->
                            if (mediaItem.mediaId == mediaId) {
                                player.replaceMediaItem(
                                    index,
                                    mediaItem.buildUpon()
                                        .setMediaMetadata(
                                            mediaItem.mediaMetadata.buildUpon()
                                                .setUserRating(heartRating)
                                                .build()
                                        )
                                        .build(),
                                )
                            }
                        }

                        SessionResult.RESULT_SUCCESS
                    }

                    false -> SessionError.ERROR_UNKNOWN
                }
            )
        }

        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            rating: Rating
        ) = player.currentMediaItem?.let {
            onSetRating(session, controller, it.mediaId, rating)
        } ?: Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))

        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ) = lifecycleScope.future {
            getResumptionPlaylist()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ) = lifecycleScope.future {
            LibraryResult.ofItem(mediaRepositoryTree.rootMediaItem, params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ) = lifecycleScope.future {
            mediaRepositoryTree.getItem(mediaId)?.let {
                LibraryResult.ofItem(it, null)
            } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        @OptIn(UnstableApi::class)
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ) = lifecycleScope.future {
            LibraryResult.ofItemList(mediaRepositoryTree.getChildren(parentId), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ) = lifecycleScope.future {
            // Diagnostics: this callback carries no start index, so a queue installed through it
            // always begins at its first item. Seeing it fire is what distinguishes "the tapped
            // track was never selected" from "it was selected but fails to play".
            android.util.Log.d(
                "PawAMPlayback",
                "onAddMediaItems ${mediaItems.size} item(s), firstId=${mediaItems.firstOrNull()?.mediaId}",
            )
            mediaRepositoryTree.resolveMediaItems(mediaItems)
        }

        @OptIn(UnstableApi::class)
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            browser: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ) = lifecycleScope.future {
            android.util.Log.d(
                "PawAMPlayback",
                "onSetMediaItems received ${mediaItems.size} item(s), startIndex=$startIndex",
            )

            // Show progress immediately: resolution can take a second or more, and without a
            // buffering state the UI looks frozen while the queue is being prepared. Seeking is
            // left to the returned MediaItemsWithStartPosition — the playlist is still the old one
            // at this point, so a seek here would be applied to the replaced queue and lost.
            player?.playWhenReady = true

            val startedAt = android.os.SystemClock.elapsedRealtime()

            // Resolution performs a network round trip per online track. If it fails, the whole
            // queue used to be dropped without any exposed error, which the user experiences as
            // "nothing happens when I tap a playlist row". Falling back to the unresolved items
            // keeps playback possible and makes the failure visible in the log.
            val resolvedMediaItems = runCatching {
                mediaRepositoryTree.resolveMediaItems(mediaItems)
            }.onFailure {
                android.util.Log.e("PawAMPlayback", "resolveMediaItems failed", it)
            }.getOrDefault(mediaItems)

            android.util.Log.d(
                "PawAMPlayback",
                "resolveMediaItems took ${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
                    "for ${mediaItems.size} item(s)",
            )

            // Diagnostics: distinguishes "the queue arrived without stream URIs" from "the player
            // never started". PawAM-specific, low volume (once per queue set).
            android.util.Log.d(
                "PawAMPlayback",
                "onSetMediaItems in=${mediaItems.size} out=${resolvedMediaItems.size} " +
                    "firstId=${resolvedMediaItems.firstOrNull()?.mediaId} " +
                    "firstUri=${resolvedMediaItems.firstOrNull()?.localConfiguration?.uri}",
            )

            // Entries other than the current one are resolved lazily, so a queue whose URLs are
            // not known yet would make the player skip item after item. Neighbours are resolved in
            // the background and replaced in place, so both skipping forward and going back find a
            // ready entry instead of re-resolving (and stuttering) each time.
            launch {
                val index = startIndex.takeIf { it in mediaItems.indices } ?: 0

                // Nearest neighbours first: skipping one track is far more common than skipping
                // three, so offsets are walked outward.
                for (offset in 1..RESOLVE_LOOKAHEAD) {
                    for (target in intArrayOf(index + offset, index - offset)) {
                        if (target !in mediaItems.indices) {
                            continue
                        }

                        val item = resolvedMediaItems.getOrNull(target) ?: continue
                        if (!mediaRepositoryTree.needsResolution(item)) {
                            continue
                        }

                        val resolved = mediaRepositoryTree.resolveOne(item)
                            .firstOrNull { it.localConfiguration?.uri != null }
                            ?: continue

                        val current = player ?: break

                        // The check and the replacement must observe the same index: skipping
                        // right between them let `replaceMediaItem` hit the entry that had just
                        // become current, which the player answers with STOPPED. Re-checking
                        // immediately before replacing, and aborting if the queue moved at all,
                        // removes that window.
                        if (target == current.currentMediaItemIndex ||
                            current.mediaItemCount != resolvedMediaItems.size
                        ) {
                            // Replacing the item that is playing restarts it, so leave the
                            // current entry alone; it is re-resolved on demand if needed.
                            continue
                        }

                        runCatching { current.replaceMediaItem(target, resolved) }
                            .onFailure {
                                android.util.Log.w("PawAMPlayback", "replaceMediaItem failed", it)
                            }
                    }
                }
            }

            launch {
                resumptionPlaylistRepository.onMediaItemsChanged(
                    resolvedMediaItems.map { it.mediaId },
                    startIndex,
                    startPositionMs,
                )
            }

            MediaSession.MediaItemsWithStartPosition(
                resolvedMediaItems,
                startIndex,
                startPositionMs
            )
        }

        private fun resolvedRange(size: Int) = 0 until size
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ) = lifecycleScope.future {
            session.notifySearchResultChanged(
                browser, query, mediaRepositoryTree.search(query).size, params
            )
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ) = lifecycleScope.future {
            LibraryResult.ofItemList(mediaRepositoryTree.search(query), params)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ) = lifecycleScope.future {
            when (CustomCommand.fromCustomAction(customCommand.customAction)) {
                CustomCommand.TOGGLE_OFFLOAD -> {
                    args.getBoolean(CustomCommand.ARG_VALUE).let {
                        player.trackSelectionParameters =
                            player.trackSelectionParameters.buildUpon()
                                .setAudioOffloadPreferences(
                                    TrackSelectionParameters.AudioOffloadPreferences
                                        .Builder()
                                        .setAudioOffloadMode(
                                            getOffloadMode(it)
                                        )
                                        .build()
                                ).build()
                    }

                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommand.TOGGLE_SKIP_SILENCE -> {
                    args.getBoolean(CustomCommand.ARG_VALUE).let {
                        player.skipSilenceEnabled = it
                    }

                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommand.GET_AUDIO_SESSION_ID -> {
                    SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle {
                            putInt(CustomCommand.RSP_VALUE, player.audioSessionId)
                        },
                    )
                }

                CustomCommand.TOGGLE_SHUFFLE -> {
                    args.getBoolean(CustomCommand.ARG_VALUE).let {
                        player.shuffleModeEnabled = it
                    }

                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommand.TOGGLE_REPEAT -> {
                    args.getString(CustomCommand.ARG_VALUE)?.let {
                        player.typedRepeatMode = RepeatMode.valueOf(it)
                    }

                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                null -> SessionResult(SessionError.ERROR_NOT_SUPPORTED)
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Apple Music full tracks are cbcs-encrypted HLS. Two hooks are required and both are
        // keyed off the stream URL: the manifest must be rewritten to CENC before Media3 parses
        // it, and the Widevine licence must be fetched with Apple's own request format.
        // Local and NetEase items are unaffected — the resolver passes them straight through.
        // DefaultDataSource (not DefaultHttpDataSource): the app plays local `content://` files as
        // well as network streams, and an HTTP-only factory cannot open content URIs at all —
        // which silently broke every local track.
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
            this,
            androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true),
        )

        // Cache what is fetched: Apple's streams take a network round trip (and a licence) per
        // track, so replaying from disk makes repeated playback instant. A dedicated cache also
        // means a track that played once does not have to be resolved again.
        val cacheDir = java.io.File(cacheDir, "media")
        // Bounded by total size rather than unbounded: NoOpCacheEvictor would let the cache grow
        // without limit, while the user wants "as much as fits, up to 5 GB". Least-recently-used
        // is the right policy for playback, where old tracks are the least likely to be replayed.
        val mediaCache = androidx.media3.datasource.cache.SimpleCache(
            cacheDir,
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(MEDIA_CACHE_BYTES),
        )

        val playerDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(
                org.lineageos.twelve.datasources.applemusic.drm.AppleMusicDrm
                    .dataSourceFactory(dataSourceFactory)
            )
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            playerDataSourceFactory
        ).setDrmSessionManagerProvider(
            org.lineageos.twelve.datasources.applemusic.drm.AppleMusicDrm
                .drmSessionManagerProvider()
        )

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .setRenderersFactory(
                TwelveRenderersFactory(
                    this,
                    sharedPreferences.enableFloatOutput,
                    onAudioDeviceInfoChanged = {
                        outputConfigurationRepository.updateAudioDeviceInfo(it)
                    },
                    onAudioTrackConfigChanged = {
                        outputConfigurationRepository.updateAudioTrackConfig(it)
                    },
                )
            )
            .setTrackSelector(DefaultTrackSelector(this).apply {
                setParameters(
                    buildUponParameters()
                        .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                        .setAudioOffloadPreferences(
                            TrackSelectionParameters.AudioOffloadPreferences.Builder()
                                .setAudioOffloadMode(
                                    getOffloadMode(sharedPreferences.enableOffload)
                                ).build()
                        )
                )
            })
            .setSkipSilenceEnabled(sharedPreferences.skipSilence)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .experimentalSetDynamicSchedulingEnabled(true)
            .build()

        // Every queue installation passes through STATE_IDLE, so reacting to that state meant this
        // listener fired while `setMediaItems` was still being applied and re-prepared the queue
        // from its first item — the tapped track was never the one that started. Genuinely idle
        // sessions are handled where the queue is installed instead.

        // An entry whose stream URL is not resolved yet carries the placeholder `applemusic://`
        // URI, which the data source cannot open: the player raises a source error before any
        // lazy resolution could run. Playback is therefore held until the entry is resolved, so a
        // skip waits for the URL instead of failing and bouncing around the queue.
        player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) {
                    return
                }

                val current = player ?: return
                if (!mediaRepositoryTree.needsResolution(mediaItem)) {
                    android.util.Log.d(
                        "PawAMPlayback",
                        "transition to ${mediaItem.mediaId}: already resolved, " +
                            "index=${current.currentMediaItemIndex} playWhenReady=${current.playWhenReady}",
                    )
                    return
                }

                android.util.Log.d(
                    "PawAMPlayback",
                    "transition to ${mediaItem.mediaId}: needs resolution, " +
                        "index=${current.currentMediaItemIndex}",
                )

                val position = current.currentMediaItemIndex
                lifecycleScope.launch {
                    // Hold playback while the URL is fetched; resuming early would hit the
                    // placeholder URI and turn a short wait into a source error.
                    val wasPlaying = current.playWhenReady
                    current.playWhenReady = false

                    val resolved = mediaRepositoryTree.resolveOne(mediaItem)
                        .firstOrNull { it.localConfiguration?.uri != null }

                    if (resolved == null) {
                        Log.w("PawAMPlayback", "Item at $position could not be resolved")
                        return@launch
                    }

                    // The user may have skipped again while this was in flight.
                    if (current.currentMediaItemIndex != position) {
                        return@launch
                    }

                    current.replaceMediaItem(position, resolved)
                    current.prepare()
                    current.playWhenReady = wasPlaying

                    // Warm the new neighbourhood so further skips find a ready URL too.
                    warmNeighbours(position)
                }
            }
        })

        // A track can fail because its stream URL is not known yet: the queue is resolved lazily,
        // so skipping onto an un-resolved entry used to raise an error, and blindly seeking to the
        // next entry repeated the failure until the queue wrapped back to its first track — which
        // is exactly what "skipping jumps to the first song" was. Resolve the failing entry in
        // place and retry before giving up on it.
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val current = player ?: return
                val position = current.currentMediaItemIndex
                val item = current.currentMediaItem

                if (item != null && mediaRepositoryTree.needsResolution(item)) {
                    Log.w(
                        "PawAMPlayback",
                        "Unresolved item at $position failed; resolving and retrying",
                        error,
                    )

                    lifecycleScope.launch {
                        val resolved = mediaRepositoryTree.resolveOne(item)
                            .firstOrNull { it.localConfiguration?.uri != null }

                        if (resolved == null) {
                            Log.w("PawAMPlayback", "Item at $position could not be resolved")
                            return@launch
                        }

                        // The user may have moved on while this was in flight.
                        if (current.currentMediaItemIndex != position) {
                            return@launch
                        }

                        current.replaceMediaItem(position, resolved)
                        current.prepare()
                        current.play()
                    }
                    return
                }

                if (current.hasNextMediaItem()) {
                    Log.w("PawAMPlayback", "Skipping unplayable item: ${error.errorCodeName}", error)
                    current.seekToNextMediaItem()
                    current.prepare()
                    current.play()
                }
            }
        })

        mediaLibrarySession = MediaLibrarySession.Builder(
            this, player, mediaLibrarySessionCallback
        )
            .setBitmapLoader(
                CoilBitmapLoader(
                    this,
                    lifecycleScope,
                    MediaSession.getBitmapDimensionLimit(this)
                )
            )
            .setSessionActivity(getSingleTopActivity())
            .setCustomLayout(getCustomLayout())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply {
                    setSmallIcon(R.drawable.ic_notification_small_icon)
                }
        )

        player.addAnalyticsListener(analyticsListener)

        lifecycleScope.launch {
            player.listen { events ->
                // Update startIndex and startPositionMs in resumption playlist.
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    lifecycleScope.launch {
                        resumptionPlaylistRepository.onPlaybackPositionChanged(
                            player.currentMediaItemIndex,
                            player.currentPosition
                        )
                    }

                    lifecycleScope.launch {
                        player.currentMediaItem?.mediaId?.let {
                            mediaRepository.onAudioPlayed(
                                it.toUri(),
                                player.currentPosition,
                            )
                        }
                    }
                }

                // Update the now playing widget
                if (events.containsAny(
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    )
                ) {
                    lifecycleScope.launch {
                        NowPlayingAppWidgetProvider.update(this@PlaybackService)
                    }
                }

                // Update the shuffle and repeat buttons
                if (events.containsAny(
                        Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
                    )
                ) {
                    mediaLibrarySession.setCustomLayout(getCustomLayout())
                }

                if (events.contains(Player.EVENT_AUDIO_SESSION_ID)) {
                    openAudioEffectSession()
                }
            }
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = when (intent?.action) {
        ACTION_TOGGLE_PLAY_PAUSE -> {
            lifecycleScope.launch {
                when (player.playWhenReady) {
                    true -> player.pause()
                    false -> {
                        maybeLoadResumptionPlaylist()
                        player.play()
                    }
                }
            }

            START_STICKY
        }

        else -> super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (sharedPreferences.stopPlaybackOnTaskRemoved || !isPlaybackOngoing) {
            lifecycleScope.launch {
                if (isPlaybackOngoing) {
                    resumptionPlaylistRepository.onPlaybackPositionChanged(
                        player.currentMediaItemIndex,
                        player.currentPosition
                    )
                }
                pauseAllPlayersAndStopSelf()
            }
        }
    }

    override fun onDestroy() {
        closeAudioEffectSession()

        player.removeAnalyticsListener(analyticsListener)

        player.release()
        mediaLibrarySession.release()

        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaLibrarySession

    /**
     * Resolve the entries around [index] that still lack a stream URL.
     *
     * `replaceMediaItem` resets playback of the queue, so this only ever touches entries that are
     * neither current nor adjacent to the current one: patching a neighbour close to playback was
     * observed to keep restarting the track that was about to play.
     */
    private fun warmNeighbours(index: Int) {
        lifecycleScope.launch {
            val current = player ?: return@launch
            val count = current.mediaItemCount

            for (offset in 2..RESOLVE_LOOKAHEAD) {
                for (target in intArrayOf(index + offset, index - offset)) {
                    if (target !in 0 until count) {
                        continue
                    }

                    val item = current.getMediaItemAt(target)
                    if (!mediaRepositoryTree.needsResolution(item)) {
                        continue
                    }

                    val resolved = mediaRepositoryTree.resolveOne(item)
                        .firstOrNull { it.localConfiguration?.uri != null }
                        ?: continue

                    if (current.currentMediaItemIndex == target) {
                        continue
                    }

                    runCatching { current.replaceMediaItem(target, resolved) }
                        .onFailure { android.util.Log.w("PawAMPlayback", "replaceMediaItem failed", it) }
                }
            }
        }
    }

    private fun openAudioEffectSession() {
        Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, application.packageName)
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            sendBroadcast(this)
        }
    }

    private fun closeAudioEffectSession() {
        Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, application.packageName)
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            sendBroadcast(this)
        }
    }

    private fun getSingleTopActivity() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun getCustomLayout() = CustomCommand.entries.mapNotNull {
        it.buildCommandButton(player, resources)
    }

    /**
     * Get the resumption playlist as [MediaSession.MediaItemsWithStartPosition].
     * Returns an empty list if no valid media items are found.
     */
    private suspend fun getResumptionPlaylist(): MediaSession.MediaItemsWithStartPosition {
        val resumptionPlaylist = resumptionPlaylistRepository.getResumptionPlaylist()

        var startIndex = resumptionPlaylist.startIndex
        var startPositionMs = resumptionPlaylist.startPositionMs

        val mediaItems = resumptionPlaylist.mediaItemIds.mapAsync { itemId ->
            mediaRepositoryTree.getItem(itemId)
        }.withIndex().mapNotNull { (index, mediaItem) ->
            when (mediaItem) {
                null -> {
                    if (index == resumptionPlaylist.startIndex) {
                        // The playback position is now invalid
                        startPositionMs = 0

                        // Let's try the next item, this is done automatically since
                        // the next item will take this item's index
                    } else if (index < resumptionPlaylist.startIndex) {
                        // The missing media is before the start index, we have to offset
                        // the start by 1 entry
                        startIndex -= 1
                    }

                    null
                }

                else -> mediaItem
            }
        }

        return if (mediaItems.isEmpty()) {
            // No valid media items found, clear the resumption playlist
            resumptionPlaylistRepository.clearResumptionPlaylist()

            MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        } else {
            MediaSession.MediaItemsWithStartPosition(
                mediaItems,
                startIndex,
                startPositionMs
            )
        }
    }

    /**
     * If no media item is available, load the resumption playlist and seek to the last index and
     * position.
     */
    private suspend fun maybeLoadResumptionPlaylist() {
        if (player.mediaItemCount != 0) {
            return
        }

        val resumptionPlaylist = withContext(Dispatchers.IO) {
            getResumptionPlaylist()
        }
        if (resumptionPlaylist.mediaItems.isEmpty()) {
            Log.e(LOG_TAG, "No resumption playlist items found")
            return
        }

        player.setMediaItems(
            resumptionPlaylist.mediaItems,
            resumptionPlaylist.startIndex,
            resumptionPlaylist.startPositionMs
        )
        player.prepare()
    }

    private fun getOffloadMode(enabled: Boolean) = if (enabled) {
        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
    } else {
        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
    }

    companion object {
        private val LOG_TAG = PlaybackService::class.simpleName!!

        /**
         * Toggles play/pause. On play request and empty queue, resumption playlist will be loaded.
         */
        const val ACTION_TOGGLE_PLAY_PAUSE = "org.lineageos.twelve.ACTION_TOGGLE_PLAY_PAUSE"
    }
}
