package com.thousandlabs.tonedial

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.thousandlabs.tonedial.data.SongArtFetcher
import com.thousandlabs.tonedial.data.SongArtKeyer
import com.thousandlabs.tonedial.data.UserStore

class ToneDialApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        UserStore.init(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(SongArtFetcher.Factory())
                add(SongArtKeyer())
            }
            .crossfade(true)
            .build()
}
