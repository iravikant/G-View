package com.acoder.gallery.core.pdf
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
object PdfGenerator {
 data class Options(val pageWidth:Int=595,val pageHeight:Int=842,val quality:Int=90)
 fun generate(context:Context,uris:List<Uri>,options:Options=Options()):File {val dir=File(context.cacheDir,"shared").apply{mkdirs()};val file=File(dir,"Gallery_${System.currentTimeMillis()}.pdf");val doc=PdfDocument();uris.forEachIndexed{index,uri->decodeForPage(context,uri,options.pageWidth,options.pageHeight)?.let{bmp->val page=doc.startPage(PdfDocument.PageInfo.Builder(options.pageWidth,options.pageHeight,index+1).create());val scale=minOf(options.pageWidth.toFloat()/bmp.width,options.pageHeight.toFloat()/bmp.height)*.92f;val w=bmp.width*scale;val h=bmp.height*scale;page.canvas.drawBitmap(bmp,null,android.graphics.RectF((options.pageWidth-w)/2,(options.pageHeight-h)/2,(options.pageWidth+w)/2,(options.pageHeight+h)/2),null);doc.finishPage(page);bmp.recycle()}};FileOutputStream(file).use{doc.writeTo(it)};doc.close();return file}
 private fun decodeForPage(context:Context,uri:Uri,maxW:Int,maxH:Int):Bitmap? {
  val resolver=context.contentResolver; val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}; resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)} ?: return null
  if(bounds.outWidth<=0||bounds.outHeight<=0)return null
  val sample=kotlin.math.max(1,kotlin.math.min(bounds.outWidth/maxW,bounds.outHeight/maxH)); val opts=BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}; return resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,opts)}
 }
}
