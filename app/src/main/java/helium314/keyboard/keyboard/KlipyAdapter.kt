// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.os.Build
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import com.google.android.material.imageview.ShapeableImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.load
import coil.dispose
import coil.size.Scale
import coil.memory.MemoryCache
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

    class ViewHolder(view: View, val parent: ViewGroup) : RecyclerView.ViewHolder(view) {
        val imageView: ShapeableImageView = view.findViewById(R.id.klipyImage)
        var pendingLoadRunnable: Runnable? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.klipy_item, parent, false)
        return ViewHolder(view, parent)
    }

    private var imageLoader: ImageLoader? = null
    private var areAnimationsRunning = true
    private val activeViews = mutableSetOf<ViewHolder>()

    fun setAnimationsRunning(running: Boolean) {
        if (areAnimationsRunning == running) return
        areAnimationsRunning = running
        for (holder in activeViews) {
            val drawable = holder.imageView.drawable
            if (drawable is Animatable) {
                if (running) {
                    drawable.start()
                } else {
                    drawable.stop()
                }
            }
        }
    }

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
                .memoryCache {
                    MemoryCache.Builder(context.applicationContext)
                        .maxSizePercent(0.10)
                        .build()
                }
                .build().also { imageLoader = it }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // Keep track of this active view holder
        activeViews.add(holder)

        // Prevent StaggeredGridLayoutManager from making items full-span
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams) {
            lp.isFullSpan = false
        }

        var targetWidth = 0
        var targetHeight = 0
        var hasDimensions = false

        val parent = holder.parent
        if (parent.width > 0) {
            val layoutManager = (parent as? RecyclerView)?.layoutManager
            val spanCount = when (layoutManager) {
                is GridLayoutManager -> layoutManager.spanCount
                is StaggeredGridLayoutManager -> layoutManager.spanCount
                else -> 1
            }
            val itemWidth = (parent.width - parent.paddingLeft - parent.paddingRight) / spanCount
            val widthRatio = if (item.width > 0) item.height.toFloat() / item.width.toFloat() else 1f
            targetHeight = (itemWidth * widthRatio).toInt().coerceIn(50, itemWidth * 3)
            targetWidth = itemWidth
            hasDimensions = true

            val itemLp = holder.itemView.layoutParams
            itemLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            itemLp.height = targetHeight
            holder.itemView.layoutParams = itemLp

            val imageLayoutParams = holder.imageView.layoutParams
            imageLayoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            imageLayoutParams.height = targetHeight
            holder.imageView.layoutParams = imageLayoutParams
        } else {
            val itemLp = holder.itemView.layoutParams
            itemLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            itemLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            holder.itemView.layoutParams = itemLp

            val imageLayoutParams = holder.imageView.layoutParams
            imageLayoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            imageLayoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            holder.imageView.layoutParams = imageLayoutParams
        }

        // Cancel any pending load runnable to avoid memory leaks or duplicate loads
        holder.pendingLoadRunnable?.let { holder.itemView.removeCallbacks(it) }

        // Setup our custom SubtleCircularProgressDrawable as a premium, lightweight placeholder
        val placeholder = SubtleCircularProgressDrawable().apply {
            start()
        }
        holder.imageView.setImageDrawable(placeholder)

        val loader = getImageLoader(holder.itemView.context)
        val imageUrl = item.previewUrl ?: item.url

        val runnable = Runnable {
            if (holder.bindingAdapterPosition == position) {
                holder.imageView.load(imageUrl, loader) {
                    crossfade(150)
                    scale(Scale.FIT)
                    if (hasDimensions && targetWidth > 0 && targetHeight > 0) {
                        size(targetWidth, targetHeight)
                    }
                    listener(
                        onSuccess = { _, result ->
                            val drawable = result.drawable
                            if (drawable is Animatable) {
                                val throttled = ThrottledAnimatableDrawable(drawable, 150L)
                                holder.imageView.setImageDrawable(throttled)
                                if (areAnimationsRunning) {
                                    throttled.start()
                                } else {
                                    throttled.stop()
                                }
                            }
                        }
                    )
                }
            }
        }
        holder.pendingLoadRunnable = runnable

        // Use a longer debounce delay when flinging/settling to prevent massive CPU decode spikes
        val recyclerView = parent as? RecyclerView
        val isFlinging = recyclerView?.scrollState == RecyclerView.SCROLL_STATE_SETTLING
        val delay = if (isFlinging) 150L else 40L

        holder.itemView.postDelayed(runnable, delay)

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount() = items.size

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        activeViews.remove(holder)
        holder.pendingLoadRunnable?.let { holder.itemView.removeCallbacks(it) }
        holder.pendingLoadRunnable = null
        holder.imageView.dispose()
        holder.imageView.setImageDrawable(null)
    }

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

class SubtleCircularProgressDrawable : Drawable(), Animatable, Runnable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = 0x66888888 // mid-tone grey with 40% opacity (looks excellent on light/dark themes)
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    private var angle = 0f
    private var isRunning = false

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val size = Math.min(bounds.width(), bounds.height()) * 0.35f
        if (size <= 0) return

        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)

        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawArc(rect, 0f, 270f, false, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // Animatable implementation
    override fun start() {
        if (!isRunning) {
            isRunning = true
            scheduleSelf(this, SystemClock.uptimeMillis() + 16)
        }
    }

    override fun stop() {
        if (isRunning) {
            isRunning = false
            unscheduleSelf(this)
        }
    }

    override fun isRunning(): Boolean = isRunning

    override fun run() {
        if (isRunning) {
            angle = (angle + 6f) % 360f // Rotate 6 degrees every frame (~60 FPS)
            invalidateSelf()
            scheduleSelf(this, SystemClock.uptimeMillis() + 16)
        }
    }
}

class ThrottledAnimatableDrawable(
    private val delegate: Drawable,
    private val minFrameDurationMs: Long = 150L // Cap frame rate to ~7 FPS for previews
) : Drawable(), Drawable.Callback, Animatable {

    init {
        delegate.callback = this
    }

    private val isAnimatable: Boolean
        get() = delegate is Animatable

    override fun draw(canvas: Canvas) {
        delegate.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        delegate.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        delegate.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int {
        return delegate.opacity
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        delegate.bounds = bounds
    }

    override fun getIntrinsicWidth(): Int = delegate.intrinsicWidth
    override fun getIntrinsicHeight(): Int = delegate.intrinsicHeight

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        val delegateChanged = delegate.setVisible(visible, restart)
        return changed || delegateChanged
    }

    override fun getPadding(padding: Rect): Boolean = delegate.getPadding(padding)
    override fun isStateful(): Boolean = delegate.isStateful
    override fun onStateChange(state: IntArray): Boolean = delegate.setState(state)
    override fun getCurrent(): Drawable = delegate.current

    // Animatable implementation
    override fun start() {
        if (isAnimatable) {
            (delegate as Animatable).start()
        }
    }

    override fun stop() {
        if (isAnimatable) {
            (delegate as Animatable).stop()
        }
    }

    override fun isRunning(): Boolean {
        return isAnimatable && (delegate as Animatable).isRunning
    }

    // Callback implementation
    private var lastFrameTime = 0L

    override fun invalidateDrawable(who: Drawable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFrameTime >= minFrameDurationMs) {
            lastFrameTime = now
            invalidateSelf()
        }
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        val now = SystemClock.uptimeMillis()
        val delay = `when` - now
        val targetWhen = if (delay <= 0) {
            `when`
        } else {
            val currentLastFrame = lastFrameTime
            val earliestNextFrame = currentLastFrame + minFrameDurationMs
            if (`when` < earliestNextFrame) {
                earliestNextFrame
            } else {
                `when`
            }
        }
        scheduleSelf(what, targetWhen)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        unscheduleSelf(what)
    }
}
