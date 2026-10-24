package com.example.galeria

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GalleryApp() }
    }
}

@Composable
fun GalleryApp() {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(permission) }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        if (!granted) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BasicText("Precisamos de acesso às suas fotos.")
                Spacer(Modifier.height(16.dp))
                BasicText(
                    "Permitir",
                    style = TextStyle(color = Color.White, fontWeight = FontWeight.Medium),
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF4285F4))
                        .clickable { launcher.launch(permission) }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
            return
        }

        var images by remember { mutableStateOf(emptyList<Uri>()) }
        var selected by remember { mutableStateOf<Int?>(null) }

        LaunchedEffect(granted) {
            images = withContext(Dispatchers.IO) { loadImages(context) }
        }

        val index = selected
        if (index != null) {
            BackHandler { selected = null }
            Viewer(images, index)
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                BasicText(
                    "Galeria",
                    style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(16.dp)
                )
                if (images.isEmpty()) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        BasicText("Nenhuma foto encontrada")
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(images) { i, uri ->
                            Photo(uri, 300, ContentScale.Crop,
                                Modifier.aspectRatio(1f).clickable { selected = i })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Viewer(images: List<Uri>, start: Int) {
    val pager = rememberPagerState(initialPage = start) { images.size }
    HorizontalPager(pager, Modifier.fillMaxSize().background(Color.Black)) { page ->
        Photo(images[page], 1600, ContentScale.Fit, Modifier.fillMaxSize())
    }
}

@Composable
fun Photo(uri: Uri, size: Int, scale: ContentScale, modifier: Modifier) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri, size) {
        value = withContext(Dispatchers.IO) { decode(context, uri, size) }
    }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = scale)
    else Box(modifier.background(Color(0xFFEEEEEE)))
}

private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

private fun decode(context: Context, uri: Uri, size: Int): ImageBitmap? {
    val key = "$uri#$size"
    cache.get(key)?.let { return it }
    return try {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565.takeIf { size <= 300 } ?: Bitmap.Config.ARGB_8888
        }
        var bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val degrees = cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees != 0f) {
            bitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(degrees) }, true)
        }
        bitmap.asImageBitmap().also { cache.put(key, it) }
    } catch (e: Exception) {
        null
    }
}

fun loadImages(context: Context): List<Uri> {
    val list = mutableListOf<Uri>()
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    context.contentResolver.query(
        collection,
        arrayOf(MediaStore.Images.Media._ID),
        null, null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (c.moveToNext()) {
            list += ContentUris.withAppendedId(collection, c.getLong(idCol))
        }
    }
    return list
}
