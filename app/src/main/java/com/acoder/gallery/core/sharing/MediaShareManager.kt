package com.acoder.gallery.core.sharing
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
object MediaShareManager {
 fun share(context:Context, uris:List<Uri>, mime:String="*/*") { val i=if(uris.size==1) Intent(Intent.ACTION_SEND).apply{type=mime;putExtra(Intent.EXTRA_STREAM,uris.first())} else Intent(Intent.ACTION_SEND_MULTIPLE).apply{type=mime;putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(uris))}; i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); context.startActivity(Intent.createChooser(i,"Share media")) }
 fun fileUri(context:Context,file:File):Uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
}
