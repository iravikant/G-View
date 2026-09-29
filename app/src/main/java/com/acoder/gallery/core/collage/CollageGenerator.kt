package com.acoder.gallery.core.collage
import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
object CollageGenerator {
 fun generate(context:Context,uris:List<Uri>,columns:Int=2,spacing:Int=8,corner:Int=20):File {val size=1080;val rows=kotlin.math.ceil(uris.size/columns.toDouble()).toInt().coerceAtLeast(1);val cell=(size-spacing*(columns+1))/columns;val h=rows*cell+spacing*(rows+1);val out=Bitmap.createBitmap(size,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(out);canvas.drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG);uris.forEachIndexed{i,uri->decode(context,uri,cell)?.let{bmp->val l=spacing+(i%columns)*(cell+spacing);val t=spacing+(i/columns)*(cell+spacing);val dst=Rect(l,t,l+cell,t+cell);val path=Path().apply{addRoundRect(RectF(dst),corner.toFloat(),corner.toFloat(),Path.Direction.CW)};canvas.save();canvas.clipPath(path);canvas.drawBitmap(bmp,null,dst,paint);canvas.restore();bmp.recycle()}};val dir=File(context.cacheDir,"shared").apply{mkdirs()};val f=File(dir,"Collage_${System.currentTimeMillis()}.jpg");FileOutputStream(f).use{out.compress(Bitmap.CompressFormat.JPEG,94,it)};out.recycle();return f}
 private fun decode(context:Context,uri:Uri,max:Int):Bitmap? {
  val r=context.contentResolver; val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}; r.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)} ?: return null
  if(bounds.outWidth<=0||bounds.outHeight<=0)return null
  val sample=kotlin.math.max(1,kotlin.math.min(bounds.outWidth/max,bounds.outHeight/max)); return r.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})}
 }
}
