/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.ext.updateMargin
import org.lineageos.twelve.ext.updatePadding
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.Lyrics
import org.lineageos.twelve.ui.recyclerview.CenterSmoothScroller
import org.lineageos.twelve.ui.recyclerview.SimpleListAdapter
import org.lineageos.twelve.viewmodels.LyricsViewModel
import org.lineageos.twelve.viewmodels.NowPlayingViewModel

typealias LineToState = Triple<Lyrics.Line, NowPlayingViewModel.LyricsLineState, Long?>

/**
 * Show lyrics of currently playing audio.
 */
class LyricsFragment : Fragment(R.layout.fragment_lyrics) {
    // View models
    private val viewModel by viewModels<LyricsViewModel>()

    // Views
    private val followCurrentLineExtendedFloatingActionButton by getViewProperty<ExtendedFloatingActionButton>(
        R.id.followCurrentLineExtendedFloatingActionButton
    )
    private val noElementsNestedScrollView by getViewProperty<NestedScrollView>(R.id.noElementsNestedScrollView)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)

    // RecyclerView
    private val adapter by lazy {
        object : SimpleListAdapter<LineToState, TextView>(
            diffCallback,
            { layoutInflater.inflate(R.layout.lyrics_line, null, false) as TextView }
        ) {
            override fun ViewHolder.onBindView(item: LineToState) {
                val (line, lineState, positionMs) = item

                // Lines carrying per-word timing are shaded up to the current position, so the
                // lyrics advance syllable by syllable instead of jumping a whole line at a time.
                // Lines without timing fall back to plain text and rely on the line state only.
                val words = line.words
                view.text = if (words.isNullOrEmpty() || lineState != NowPlayingViewModel.LyricsLineState.ACTIVE) {
                    line.text
                } else {
                    buildWordShadedText(line.text, words, positionMs ?: 0L)
                }

                view.isSelected = lineState == NowPlayingViewModel.LyricsLineState.ACTIVE
                view.isActivated = lineState != NowPlayingViewModel.LyricsLineState.PAST

                view.setOnClickListener {
                    viewModel.seekToLine(line)
                }
            }
        }
    }
    private val scrollListener by lazy {
        object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                val isScrolling = newState == RecyclerView.SCROLL_STATE_DRAGGING
                        || newState == RecyclerView.SCROLL_STATE_SETTLING

                if (isScrolling && recyclerView.layoutManager?.isSmoothScrolling == false) {
                    viewModel.setPositionSynced(false)
                }
            }
        }
    }

    /**
     * Build the active line's text with the already-sung syllables tinted.
     *
     * Shading is computed by clipping the current position against each word's own time range, so
     * a word lights up progressively rather than in one step. Words without timing are skipped by
     * the caller.
     */
    private fun buildWordShadedText(
        text: String,
        words: List<Lyrics.Word>,
        positionMs: Long,
    ): CharSequence {
        val shaded = android.text.SpannableStringBuilder()
        val sungColor = com.google.android.material.color.MaterialColors.getColor(
            requireView(),
            androidx.appcompat.R.attr.colorPrimary,
        )

        for (word in words) {
            val start = shaded.length
            shaded.append(word.text)
            val end = shaded.length
            if (end <= start) {
                continue
            }

            val sungEnd = when {
                positionMs >= word.durationMs.last -> end
                positionMs <= word.durationMs.first -> start
                else -> {
                    val span = (word.durationMs.last - word.durationMs.first).coerceAtLeast(1L)
                    val fraction = (positionMs - word.durationMs.first).toFloat() / span
                    start + ((end - start) * fraction).toInt()
                }
            }

            if (sungEnd > start) {
                shaded.setSpan(
                    android.text.style.ForegroundColorSpan(sungColor),
                    start,
                    sungEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        // Keep the original text when word concatenation drifts (defensive: a malformed document
        // must not render different lyrics than the plain text).
        return if (shaded.toString().trim() == text) shaded else text
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            v.updatePadding(
                insets,
                start = true,
                end = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                insets,
                bottom = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(noElementsNestedScrollView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            v.updatePadding(
                insets,
                bottom = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(
            followCurrentLineExtendedFloatingActionButton
        ) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updateMargin(
                insets,
                bottom = true,
            )

            windowInsets
        }

        toolbar.setupWithNavController(findNavController())

        recyclerView.adapter = adapter
        recyclerView.addOnScrollListener(scrollListener)

        followCurrentLineExtendedFloatingActionButton.setOnClickListener {
            viewModel.setPositionSynced(true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loadData()
            }
        }
    }

    override fun onDestroyView() {
        recyclerView.clearOnScrollListeners()
        recyclerView.adapter = null

        super.onDestroyView()
    }

    private fun CoroutineScope.loadData() {
        launch {
            viewModel.lyricsLines.collectLatest {
                when (it) {
                    is FlowResult.Loading -> {
                        // Do nothing
                    }

                    is FlowResult.Success -> {
                        val (lyricsWithState, currentIndex) = it.data

                        adapter.submitList(lyricsWithState)
                        currentIndex.takeIf { viewModel.positionSynced.value }?.let { index ->
                            CenterSmoothScroller(recyclerView.context).apply {
                                targetPosition = index
                            }.also { smoothScroller ->
                                recyclerView.layoutManager?.startSmoothScroll(smoothScroller)
                            }
                        }

                        val isEmpty = lyricsWithState.isEmpty()

                        recyclerView.isVisible = !isEmpty
                        noElementsNestedScrollView.isVisible = isEmpty
                    }

                    is FlowResult.Failure -> {
                        Log.e(
                            LOG_TAG,
                            "Error while loading lyrics, error: ${it.error}",
                            it.throwable
                        )

                        adapter.submitList(null)

                        recyclerView.isVisible = false
                        noElementsNestedScrollView.isVisible = true
                    }
                }
            }
        }

        launch {
            viewModel.positionSynced.collectLatest {
                followCurrentLineExtendedFloatingActionButton.isVisible = !it
            }
        }
    }

    companion object {
        private val LOG_TAG = LyricsFragment::class.simpleName!!

        private val diffCallback = object : DiffUtil.ItemCallback<LineToState>() {
            override fun areItemsTheSame(
                oldItem: LineToState,
                newItem: LineToState
            ) = oldItem.first === newItem.first

            override fun areContentsTheSame(
                oldItem: LineToState,
                newItem: LineToState
            ) = oldItem.second == newItem.second &&
                // Word shading must advance within an active line, so the position has to take
                // part in the comparison. Quantising keeps rebinds to a few per second instead of
                // one per playback tick.
                oldItem.third?.div(100L) == newItem.third?.div(100L)
        }
    }
}
