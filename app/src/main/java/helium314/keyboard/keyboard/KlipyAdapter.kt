// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.load
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.KlipyHistoryDao

class KlipyAdapter(
    private var items: List<KlipyHistoryDao.KlipyItem>,
    private val onItemClick: (KlipyHistoryDao.KlipyItem) -> Unit
) : RecyclerView.Adapter<KlipyAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.klipyImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.klipy_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        val imageLoader = ImageLoader.Builder(holder.itemView.context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()

        holder.imageView.load(item.url, imageLoader) {
            crossfade(true)
            // Staggered grid needs some info about aspect ratio if possible,
            // but coil handles dynamic sizing well with wrap_content FrameLayout
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<KlipyHistoryDao.KlipyItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
