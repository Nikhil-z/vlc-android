/*
 * *************************************************************************
 *  PlaylistAdapter.java
 * **************************************************************************
 *  Copyright © 2015-2017 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.audio

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.medialibrary.interfaces.media.AbstractMediaWrapper
import org.videolan.vlc.R
import org.videolan.vlc.VLCApplication
import org.videolan.vlc.databinding.PlaylistItemBinding
import org.videolan.vlc.gui.DiffUtilAdapter
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.getBitmapFromDrawable
import org.videolan.vlc.gui.view.MiniVisualizer
import org.videolan.vlc.interfaces.SwipeDragHelperAdapter
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.util.MediaItemDiffCallback
import org.videolan.vlc.util.WeakHandler
import org.videolan.vlc.viewmodels.PlaylistModel
import java.util.*

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
class PlaylistAdapter(private val player: IPlayer) : DiffUtilAdapter<AbstractMediaWrapper, PlaylistAdapter.ViewHolder>(), SwipeDragHelperAdapter {

    private var defaultCoverVideo: BitmapDrawable
    private var defaultCoverAudio: BitmapDrawable
    private var model: PlaylistModel? = null
    private var currentPlayingVisu: MiniVisualizer? = null

    init {
        val ctx = when (player) {
            is Context -> player
            is Fragment -> player.requireContext()
            else -> VLCApplication.appContext
        }

        defaultCoverAudio = BitmapDrawable(ctx.resources, getBitmapFromDrawable(ctx, R.drawable.ic_no_song_background))
        defaultCoverVideo = UiTools.getDefaultVideoDrawable(ctx)
    }

    var currentIndex = 0
        set(position) {
            if (position == currentIndex || position >= itemCount) return
            val former = currentIndex
            field = position
            if (former >= 0) notifyItemChanged(former)
            if (position >= 0) {
                notifyItemChanged(position)
                player.onSelectionSet(position)
            }
        }

    private val mHandler = PlaylistHandler(this)

    interface IPlayer {
        fun onPopupMenu(view: View, position: Int, item: AbstractMediaWrapper?)
        fun onSelectionSet(position: Int)
        fun playItem(position: Int, item: AbstractMediaWrapper)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.playlist_item, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val media = getItem(position)
        holder.binding.media = media
        holder.binding.subTitle = MediaUtils.getMediaSubtitle(media)
        holder.binding.scaleType = ImageView.ScaleType.CENTER_CROP
        if (currentIndex == position) {
            if (model?.playing != false) holder.binding.playing.start() else holder.binding.playing.stop()
            holder.binding.playing.visibility = View.VISIBLE
            holder.binding.coverImage.visibility = View.INVISIBLE
            holder.binding.audioItemTitle.setTypeface(null, Typeface.BOLD)
            currentPlayingVisu = holder.binding.playing
        } else {
            holder.binding.playing.stop()
            holder.binding.playing.visibility = View.INVISIBLE
            holder.binding.audioItemTitle.typeface = null
            holder.binding.coverImage.visibility = View.VISIBLE
        }

        if (media.type == AbstractMediaWrapper.TYPE_VIDEO) {
            (holder.binding.coverImage.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio = "16:10"
            holder.binding.cover = defaultCoverVideo
        } else {
            (holder.binding.coverImage.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio = "1"
            holder.binding.cover = defaultCoverAudio
        }

        holder.binding.executePendingBindings()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        currentPlayingVisu = null
    }

    override fun getItemCount() = dataset.size

    @MainThread
    override fun getItem(position: Int) = dataset[position]

    override fun onUpdateFinished() {
        model?.run { currentIndex = selection }
    }

    @MainThread
    fun remove(position: Int) {
        model?.run { remove(position) }
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int) {
        Collections.swap(dataset, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        mHandler.obtainMessage(PlaylistHandler.ACTION_MOVE, fromPosition, toPosition).sendToTarget()
    }

    override fun onItemMoved(dragFrom: Int, dragTo: Int) {
    }

    override fun onItemDismiss(position: Int) {
        val media = getItem(position)
        val message = String.format(VLCApplication.appResources.getString(R.string.remove_playlist_item), media.title)
        if (player is Fragment) {
            val v = (player as Fragment).view
            val cancelAction = Runnable { model?.run { insertMedia(position, media) } }
            UiTools.snackerWithCancel(v!!, message, null, cancelAction)
        } else if (player is Context) {
            Toast.makeText(VLCApplication.appContext, message, Toast.LENGTH_SHORT).show()
        }
        remove(position)
    }

    fun setModel(model: PlaylistModel) {
        this.model = model
    }

    inner class ViewHolder @TargetApi(Build.VERSION_CODES.M)
    constructor(v: View) : RecyclerView.ViewHolder(v) {
        var binding: PlaylistItemBinding = DataBindingUtil.bind(v)!!

        init {
            binding.holder = this
            if (AndroidUtil.isMarshMallowOrLater)
                itemView.setOnContextClickListener { view ->
                    onMoreClick(view)
                    true
                }
        }

        fun onClick(@Suppress("UNUSED_PARAMETER") v: View, media: AbstractMediaWrapper) {
            val position = layoutPosition //getMediaPosition(media);
            player.playItem(position, media)
        }

        fun onMoreClick(v: View) {
            val position = layoutPosition
            player.onPopupMenu(v, position, getItem(position))
        }
    }

    private class PlaylistHandler internal constructor(owner: PlaylistAdapter) : WeakHandler<PlaylistAdapter>(owner) {

        internal var from = -1
        internal var to = -1

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                ACTION_MOVE -> {
                    removeMessages(ACTION_MOVED)
                    if (from == -1) from = msg.arg1
                    to = msg.arg2
                    sendEmptyMessageDelayed(ACTION_MOVED, 1000)
                }
                ACTION_MOVED -> {
                    val model = owner?.model ?: return
                    if (from != -1 && to != -1) return
                    if (to > from) ++to
                    model.move(from, to)
                    to = -1
                    from = to
                }
            }
        }

        companion object {

            const val ACTION_MOVE = 0
            const val ACTION_MOVED = 1
        }
    }

    override fun createCB(): DiffCallback<AbstractMediaWrapper> = MediaItemDiffCallback()

    fun setCurrentlyPlaying(playing: Boolean) {
        if (playing) currentPlayingVisu?.start() else currentPlayingVisu?.stop()
    }
}
