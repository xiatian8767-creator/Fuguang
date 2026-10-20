package com.example.parallaxwallpaper

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parallaxwallpaper.depth.DepthEstimator
import com.example.parallaxwallpaper.gallery.PhotoEntry
import com.example.parallaxwallpaper.gallery.PhotoStore
import com.example.parallaxwallpaper.gallery.VideoExporter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoExportTest {
    @Test fun encodeDecodeSaveShareAndCancel(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "gallery/" + UUID.randomUUID()).apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
        for(y in 0 until 480) for(x in 0 until 640) bitmap.setPixel(x,y,
            if(x in 200..440 && y in 60..420) Color.RED else if(y<240) Color.BLUE else Color.GREEN)
        File(dir,"photo.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
        val n = DepthEstimator.INPUT_SIZE
        DataOutputStream(File(dir,"depth.bin").outputStream().buffered()).use { out ->
            out.writeInt(n);out.writeInt(n)
            for(y in 0 until n) for(x in 0 until n) out.writeFloat(if(x in n*200/640..n*440/640 && y in n*60/480..n*420/480) 0.9f else 0.1f)
        }
        val photo = PhotoEntry(dir.name,"导出验收",System.currentTimeMillis(),File(dir,"photo.jpg").absolutePath,640,480,0.035f)
        PhotoStore(context).writeMeta(photo)
        // Keep fixture for the subsequent UI acceptance on this emulator.
        val video = withContext(Dispatchers.Default) { VideoExporter.export(context,photo) {} }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            assertEquals("640",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("480",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("Duration $duration",duration in 5900..6100)
            val first = retriever.getFrameAtTime(1_000_000,MediaMetadataRetriever.OPTION_CLOSEST)!!
            assertTrue(Color.blue(first.getPixel(40,40))>150)
            assertTrue(Color.green(first.getPixel(40,430))>150)
            assertTrue(Color.red(first.getPixel(320,240))>150)
            first.recycle()
        } finally { retriever.release() }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.absolutePath)
            assertEquals(1,extractor.trackCount)
            assertEquals("video/avc",extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(0)
            var last = -1L;var frames = 0
            while(extractor.sampleTime>=0){assertTrue(extractor.sampleTime>last);last=extractor.sampleTime;frames++;extractor.advance()}
            assertEquals(180,frames)
        } finally { extractor.release() }
        val shared = VideoExporter.shareUri(context,video)
        assertEquals("content",shared.scheme)
        context.contentResolver.openInputStream(shared)!!.use { assertEquals(video.length(),it.readBytes().size.toLong()) }
        val saved = VideoExporter.saveToAlbum(context,video)
        context.contentResolver.openInputStream(saved)!!.use { assertEquals(video.length(),it.readBytes().size.toLong()) }
        context.contentResolver.delete(saved,null,null)
        video.copyTo(File(context.getExternalFilesDir(null),"export-sample.mp4"),overwrite=true)
        video.delete()
        val folder = File(context.cacheDir,"exports")
        val filesBefore = folder.listFiles()!!.map { it.name }.toSet()
        val cancelled = launch(Dispatchers.Default) { VideoExporter.export(context,photo) { if(it>0.05f) this.cancel() } }
        cancelled.join()
        assertEquals(filesBefore,folder.listFiles()!!.map { it.name }.toSet())
    }
}
