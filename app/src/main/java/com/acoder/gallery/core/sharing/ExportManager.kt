package com.acoder.gallery.core.sharing
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
object ExportManager {
 fun saveToDownloads(context:Context,file:File,mime:String,name:String):android.net.Uri? {
  if(Build.VERSION.SDK_INT<29){val dir=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?:context.cacheDir;val out=File(dir,name);file.inputStream().use{input->out.outputStream().use{input.copyTo(it)}};return MediaShareManager.fileUri(context,out)}
  val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime);put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/Gallery");put(MediaStore.MediaColumns.IS_PENDING,1)}
  val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:return null
  return try{context.contentResolver.openOutputStream(uri)?.use{out->file.inputStream().use{it.copyTo(out)}};context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null);uri}catch(e:Exception){context.contentResolver.delete(uri,null,null);null}
 }
}
