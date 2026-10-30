/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.ext.navigateSafe
import org.lineageos.twelve.ext.updatePadding
import org.lineageos.twelve.models.Album
import org.lineageos.twelve.models.Audio
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.MediaItem
import androidx.recyclerview.widget.DiffUtil
import org.lineageos.twelve.ui.recyclerview.SimpleListAdapter
import org.lineageos.twelve.ui.recyclerview.UniqueItemDiffCallback
import org.lineageos.twelve.ui.views.ListItem
import org.lineageos.twelve.viewmodels.MainViewModel

/**
 * Music library.
 *
 * PawAM adds a search field above the tabs. Twelve's global search walks the whole catalog, but
 * "find the album I saved" is a library-scoped question, so it is answered here without leaving
 * the screen: while the field has text the tabs are replaced by the matching albums and songs.
 */
class LibraryFragment : Fragment(R.layout.fragment_library) {
    // View models
    private val viewModel by viewModels<MainViewModel>(ownerProducer = { requireActivity() })

    // Views
    private val tabLayout by getViewProperty<TabLayout>(R.id.tabLayout)
    private val viewPager2 by getViewProperty<ViewPager2>(R.id.viewPager2)
    private val searchInput by getViewProperty<TextInputEditText>(R.id.searchInput)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)

    // ViewPager2
    private enum class Menus(
        @StringRes val titleStringResId: Int,
        val fragment: () -> Fragment,
    ) {
        ALBUMS(
            R.string.library_fragment_menu_albums,
            { AlbumsFragment() },
        ),
        ARTISTS(
            R.string.library_fragment_menu_artists,
            { ArtistsFragment() },
        ),
        GENRES(
            R.string.library_fragment_menu_genres,
            { GenresFragment() },
        ),
        PLAYLISTS(
            R.string.library_fragment_menu_playlists,
            { PlaylistsFragment() },
        ),
    }

    private val adapter by lazy {
        object : SimpleListAdapter<MediaItem<*>, ListItem>(
            object : DiffUtil.ItemCallback<MediaItem<*>>() {
                override fun areItemsTheSame(
                    oldItem: MediaItem<*>,
                    newItem: MediaItem<*>,
                ) = oldItem.uri == newItem.uri

                override fun areContentsTheSame(
                    oldItem: MediaItem<*>,
                    newItem: MediaItem<*>,
                ) = oldItem == newItem
            },
            ::ListItem,
        ) {
            override fun ViewHolder.onPrepareView() {
                view.setLeadingIconImage(R.drawable.ic_music_note)
                view.supportingText = null
            }

            override fun ViewHolder.onBindView(item: MediaItem<*>) {
                view.headlineText = when (item) {
                    is Album -> item.title
                    is Audio -> item.title
                    else -> null
                }
                view.supportingText = when (item) {
                    is Album -> item.artistName
                    is Audio -> item.artistName
                    else -> null
                }

                view.setOnClickListener {
                    when (item) {
                        is Album -> findNavController().navigateSafe(
                            R.id.action_mainFragment_to_fragment_album,
                            AlbumFragment.createBundle(item.uri)
                        )

                        is Audio -> viewModel.playAudio(listOf(item), 0)

                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewPager2.adapter = object : FragmentStateAdapter(
            childFragmentManager, viewLifecycleOwner.lifecycle
        ) {
            override fun getItemCount() = Menus.entries.size
            override fun createFragment(position: Int) = Menus.entries[position].fragment()
        }
        viewPager2.offscreenPageLimit = Menus.entries.size

        TabLayoutMediator(tabLayout, viewPager2) { tab, position ->
            val menu = Menus.entries[position]

            tab.setText(menu.titleStringResId)
            tab.setContentDescription(menu.titleStringResId)
        }.attach()

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        searchInput.doOnTextChanged { text, _, _, _ ->
            viewModel.setSearchQuery(text?.toString().orEmpty(), immediate = true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.searchResults.collectLatest { result ->
                    val searching = searchInput.text?.isNotEmpty() == true

                    tabLayout.isVisible = !searching
                    viewPager2.isVisible = !searching
                    recyclerView.isVisible = searching

                    val items = (result as? FlowResult.Success)?.data ?: emptyList()
                    adapter.submitList(if (searching) items else emptyList())
                }
            }
        }
    }

    override fun onDestroyView() {
        viewPager2.adapter = null
        recyclerView.adapter = null
        recyclerView.layoutManager = null

        super.onDestroyView()
    }
}
