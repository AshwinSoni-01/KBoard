// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.imageview.ShapeableImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.load
import coil.size.Scale
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.KlipyHistoryDao

class KlipyAdapter(
    private var items: List<KlipyHistoryDao.KlipyItem>,
    private val onItemClick: (KlipyHistoryDao.KlipyItem) -> Unit
) : RecyclerView.Adapter<KlipyAdapter.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items[position].id.hashCode().toLong()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ShapeableImageView = view.findViewById(R.id.klipyImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.klipy_item, parent, false)
        return ViewHolder(view)
    }

    private var imageLoader: ImageLoader? = null

    private fun getImageLoader(context: android.content.Context): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: ImageLoader.Builder(context.applicationContext)
                .components {
                    if (Build.VERSION.SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build().also { imageLoader = it }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // Prevent StaggeredGridLayoutManager from making items full-span
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams) {
            lp.isFullSpan = false
        }

        val parent = holder.itemView.parent as? ViewGroup
        if (parent != null && parent.width > 0) {
            val layoutManager = (parent as? RecyclerView)?.layoutManager
            val spanCount = when (layoutManager) {
                is GridLayoutManager -> layoutManager.spanCount
                is StaggeredGridLayoutManager -> layoutManager.spanCount
                else -> 1
            }
            val itemWidth = (parent.width - parent.paddingLeft - parent.paddingRight) / spanCount
            val widthRatio = if (item.width > 0) item.height.toFloat() / item.width.toFloat() else 1f
            val targetHeight = (itemWidth * widthRatio).toInt().coerceIn(50, itemWidth * 3)

            val layoutParams = holder.imageView.layoutParams
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            layoutParams.height = targetHeight
            holder.imageView.layoutParams = layoutParams
        } else {
            val layoutParams = holder.imageView.layoutParams
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            holder.imageView.layoutParams = layoutParams
        }

        val loader = getImageLoader(holder.itemView.context)

        holder.imageView.load(item.url, loader) {
            crossfade(150)
            scale(Scale.FIT)
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<KlipyHistoryDao.KlipyItem>) {
        val oldItems = items
        items = newItems
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos].id == newItems[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos] == newItems[newPos]
        })
        diffResult.dispatchUpdatesTo(this)
    }
}
