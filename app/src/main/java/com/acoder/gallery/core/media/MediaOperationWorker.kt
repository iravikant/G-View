package com.acoder.gallery.core.media
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
class MediaOperationWorker(app:Context,params:WorkerParameters):CoroutineWorker(app,params){
 override suspend fun doWork()=withContext(Dispatchers.IO){ try{ val mode=inputData.getString("mode")?:return@withContext androidx.work.ListenableWorker.Result.failure(); val dest=DocumentFile.fromTreeUri(applicationContext,Uri.parse(inputData.getString("dest")?:return@withContext androidx.work.ListenableWorker.Result.failure()) )?:return@withContext androidx.work.ListenableWorker.Result.failure(); val uris=inputData.getStringArray("uris")?:emptyArray(); uris.forEachIndexed{i,s-> val src=Uri.parse(s); val resolver=applicationContext.contentResolver; val name=resolver.query(src,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else"file_$i"}?:"file_$i"; val mime=resolver.getType(src)?:"application/octet-stream"; val file=dest.createFile(mime,name)?:return@withContext androidx.work.ListenableWorker.Result.failure(); resolver.openInputStream(src)?.use{input->resolver.openOutputStream(file.uri)?.use{output->input.copyTo(output)}}; if(mode=="move") resolver.delete(src,null,null) }; androidx.work.ListenableWorker.Result.success()}catch(_:Exception){androidx.work.ListenableWorker.Result.failure()} }
}
