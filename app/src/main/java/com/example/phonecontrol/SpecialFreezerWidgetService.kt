package com.example.phonecontrol

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class SpecialFreezerWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return SpecialFreezerRemoteViewsFactory(this.applicationContext)
    }
}

class SpecialFreezerRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var specialFrozenAppsList = mutableListOf<String>()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val customSet = FreezerManager.getCustomWidgetApps(context)
        val finalSet = if (customSet.isNotEmpty()) customSet else FreezerManager.getSpecialFreezeApps(context)
        specialFrozenAppsList.clear()
        specialFrozenAppsList.addAll(finalSet.sorted())
    }

    override fun onDestroy() {
        specialFrozenAppsList.clear()
    }

    override fun getCount(): Int = specialFrozenAppsList.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_item_app)
        if (position >= specialFrozenAppsList.size) return views

        val pkg = specialFrozenAppsList[position]
        val pm = context.packageManager
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PackageManager.MATCH_DISABLED_COMPONENTS
            } else {
                PackageManager.GET_DISABLED_COMPONENTS
            }
            
            val appInfo = pm.getApplicationInfo(pkg, flags)
            val name = pm.getApplicationLabel(appInfo).toString()
            val icon = pm.getApplicationIcon(appInfo)
            
            views.setTextViewText(R.id.widget_app_name, name)
            views.setImageViewBitmap(R.id.widget_app_icon, drawableToMonochromeBitmap(icon))
            
            val fillInIntent = Intent().apply {
                putExtra(SpecialFreezerWidgetProvider.EXTRA_PACKAGE_NAME, pkg)
            }
            views.setOnClickFillInIntent(R.id.widget_app_icon, fillInIntent)
            views.setOnClickFillInIntent(R.id.widget_app_name, fillInIntent)
            
        } catch (e: Exception) {
            views.setTextViewText(R.id.widget_app_name, "Hidden")
        }

        return views
    }

    private fun drawableToMonochromeBitmap(drawable: Drawable): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && drawable is AdaptiveIconDrawable) {
            drawable.monochrome?.let {
                return drawableToBitmap(it, true)
            }
        }
        return drawableToBitmap(drawable, true)
    }

    private fun drawableToBitmap(drawable: Drawable, applyMonochrome: Boolean): Bitmap {
        val width = drawable.intrinsicWidth.coerceAtLeast(100)
        val height = drawable.intrinsicHeight.coerceAtLeast(100)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        if (applyMonochrome) {
            val paint = Paint()
            val matrix = ColorMatrix()
            matrix.setSaturation(0f)
            val contrast = 1.2f 
            val brightness = -20f
            val cm = ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            ))
            matrix.postConcat(cm)
            paint.colorFilter = ColorMatrixColorFilter(matrix)
            
            val tempBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val tempCanvas = Canvas(tempBitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(tempCanvas)
            
            canvas.drawBitmap(tempBitmap, 0f, 0f, paint)
        } else {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
        }
        return bitmap
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
