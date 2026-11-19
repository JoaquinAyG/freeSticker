package com.example.stickermaker

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder

class StickerApp : Application(), ImageLoaderFactory {

    /** Coil loader that can show video thumbnails and animated GIF / WebP previews. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ImageDecoderDecoder.Factory())
            add(VideoFrameDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
