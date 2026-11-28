package mv.haluvi.updates

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/** GitHub-only updater. No credentials or shared-storage permissions. */
class HaluviUpdater(private val activity: Activity, private val safeToUpdate: () -> Boolean = { true }) {
    private val id=activity.packageName
    private val prefs=activity.getSharedPreferences("haluvi_updates",0)
    private var busy=false
    private var dialog:AlertDialog?=null
    private var pending:File?=null
    private var awaitingInstaller=false
    private var pendingUpdate:Update?=null
    private var waitingSettings=false
    @Volatile private var cancelled=false
    private var lastCheck=0L
    private val base="https://raw.githubusercontent.com/hshareef77/haluvi-app-updates/main/distribution/apps/"
    private val downloadPrefix="https://github.com/hshareef77/haluvi-app-updates/releases/download/"
    private data class Update(val code:Long,val name:String,val notes:String,val required:Boolean,val url:String,val hash:String,val size:Long)
    @Suppress("DEPRECATION")
    private fun installed():Long {
        val p=activity.packageManager.getPackageInfo(id,0)
        return if(Build.VERSION.SDK_INT>=28)p.longVersionCode else p.versionCode.toLong()
    }
    private fun connect(url:String):HttpURLConnection {
        val u=URL(url)
        require(u.protocol=="https" && u.userInfo==null)
        return (u.openConnection() as HttpURLConnection).apply{
            connectTimeout=10000;readTimeout=30000;instanceFollowRedirects=false
            setRequestProperty("Cache-Control","no-cache")
        }
    }
    private fun manifest():Update? {
        val c=connect(base+id+".json")
        try{
            if(c.responseCode==404)return null
            require(c.responseCode==200){"Update service unavailable"}
            val bytes=c.inputStream.use{input->
                val out=java.io.ByteArrayOutputStream();val buf=ByteArray(4096)
                while(true){val n=input.read(buf);if(n<0)break;require(out.size()+n<=65536);out.write(buf,0,n)}
                out.toByteArray()
            }
            val j=JSONObject(String(bytes,Charsets.UTF_8))
            require(j.getInt("schemaVersion")==1 && j.getString("packageName")==id)
            if(!j.getBoolean("enabled"))return null
            val code=j.getLong("versionCode")
            if(code<=installed())return null
            val url=j.getString("downloadUrl")
            val hash=j.getString("sha256")
            val size=j.getLong("sizeBytes")
            require(url.startsWith(downloadPrefix)&&URL(url).host=="github.com"&&URL(url).userInfo==null)
            require(hash.matches(Regex("[a-f0-9]{64}"))&&size in 1L..500_000_000L)
            return Update(code,j.getString("versionName"),j.getString("description"),j.getBoolean("required"),url,hash,size)
        }finally{c.disconnect()}
    }
    fun check(manual:Boolean=false){
        if(busy||dialog?.isShowing==true||activity.isFinishing||activity.isDestroyed||!safeToUpdate())return
        if(id.endsWith(".setup"))return
        if(!manual && prefs.getLong("required",0)<=installed() && android.os.SystemClock.elapsedRealtime()-lastCheck<60000 && lastCheck!=0L)return
        lastCheck=android.os.SystemClock.elapsedRealtime()
        busy=true
        if(prefs.getLong("required",0)>installed()){
            dialog=AlertDialog.Builder(activity).setTitle("Update required").setMessage("Checking approved update…").setCancelable(false).create()
            dialog?.show()
        }
        Thread{
            val result=runCatching{manifest()}
            activity.runOnUiThread{
                busy=false
                if(activity.isFinishing||activity.isDestroyed)return@runOnUiThread
                dialog?.dismiss()
                if(!safeToUpdate())return@runOnUiThread
                if(result.isSuccess){
                    val u=result.getOrNull()
                    prefs.edit().putLong("required",if(u?.required==true)u.code else 0).apply()
                    if(u!=null)show(u)
                    else if(manual)message("No update available.")
                }else if(prefs.getLong("required",0)>installed())blocked()
                else if(manual)message("Unable to check for updates. Try again.")
            }
        }.start()
    }
    private fun message(text:String){AlertDialog.Builder(activity).setMessage(text).setPositiveButton("OK",null).show()}
    private fun blocked(){
        dialog=AlertDialog.Builder(activity).setTitle("Update required").setMessage("Connect to the internet and check again before continuing.")
            .setCancelable(false).setPositiveButton("Retry"){d,_->d.dismiss();dialog=null;check(true)}
            .setNegativeButton("Exit"){_,_->activity.finishAffinity()}.create()
        dialog?.show()
    }
    private fun show(u:Update){
        val builder=AlertDialog.Builder(activity).setTitle(if(u.required)"Update required" else "Update available")
            .setMessage("New version: "+u.name+"\n\n"+u.notes)
            .setCancelable(!u.required)
            .setPositiveButton("Update now"){d,_->d.dismiss();dialog=null;download(u)}
        if(!u.required)builder.setNegativeButton("Later",null)
        dialog=builder.create();dialog?.show()
    }
    @Suppress("DEPRECATION")
    private fun signers(path:String?):Set<String>{
        val pm=activity.packageManager
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val p=if(path==null)pm.getPackageInfo(id,flags)else pm.getPackageArchiveInfo(path,flags)?:error("Invalid APK")
        require(p.packageName==id){"Wrong application"}
        val signatures=if(Build.VERSION.SDK_INT>=28)p.signingInfo?.apkContentsSigners else p.signatures
        return signatures?.map{it.toCharsString()}?.toSet().orEmpty()
    }
    private fun download(u:Update){
        if(busy||!safeToUpdate())return
        busy=true
        cancelled=false
        val progress=AlertDialog.Builder(activity).setTitle("Downloading update").setMessage("Preparing download…").setCancelable(false).create()
        if(!u.required)progress.setButton(AlertDialog.BUTTON_NEGATIVE,"Cancel") { _,_-> cancelled=true }
        dialog=progress;progress.show()
        Thread{
            val result=runCatching{
                val dir=File(activity.cacheDir,"haluvi-updates").apply{mkdirs()}
                val file=File(dir,"update.apk")
                file.delete()
                try{
                    var url=u.url
                    var c=connect(url)
                    try{
                        var redirects=0
                        while(c.responseCode in 300..399){
                            require(++redirects<=5)
                            val next=URL(URL(url),c.getHeaderField("Location")?:error("Missing redirect"))
                            require(next.protocol=="https"&&next.userInfo==null&&(next.host=="github.com"||next.host.endsWith(".githubusercontent.com")))
                            c.disconnect();url=next.toString();c=connect(url)
                        }
                        require(c.responseCode==200){"Download failed"}
                        val md=MessageDigest.getInstance("SHA-256")
                        var total=0L;var last=-10
                        c.inputStream.use{input->file.outputStream().use{out->
                            val buf=ByteArray(65536)
                            while(true){require(!cancelled){"Download cancelled"};val n=input.read(buf);if(n<0)break;total+=n;require(total<=u.size);out.write(buf,0,n);md.update(buf,0,n)
                                val percent=(total*100/u.size).toInt()
                                if(percent>=last+10){last=percent;activity.runOnUiThread{progress.setMessage("Downloaded "+percent+" percent")}}
                            }
                        }}
                        require(total==u.size)
                        require(md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==u.hash){"Checksum mismatch"}
                    }finally{c.disconnect()}
                    @Suppress("DEPRECATION") val archive=activity.packageManager.getPackageArchiveInfo(file.absolutePath,0)?:error("Invalid APK")
                    @Suppress("DEPRECATION") val code=if(Build.VERSION.SDK_INT>=28)archive.longVersionCode else archive.versionCode.toLong()
                    require(archive.packageName==id&&code==u.code&&code>installed()){"Wrong APK version"}
                    val current=signers(null);require(current.isNotEmpty()&&current==signers(file.absolutePath)){"Signing certificate mismatch"}
                    file
                }catch(e:Exception){file.delete();throw e}
            }
            activity.runOnUiThread{
                busy=false;progress.dismiss()
                if(activity.isFinishing||activity.isDestroyed)return@runOnUiThread
                if(cancelled){result.getOrNull()?.delete();return@runOnUiThread}
                result.onSuccess{pending=it;pendingUpdate=u;if(safeToUpdate())install(it)}.onFailure{
                    dialog=AlertDialog.Builder(activity).setTitle("Update failed").setMessage(it.message?:"Try again.")
                        .setCancelable(!u.required).setPositiveButton("Retry"){_,_->download(u)}
                        .setNegativeButton(if(u.required)"Exit" else "Later"){_,_->if(u.required)activity.finishAffinity()}.create()
                    dialog?.show()
                }
            }
        }.start()
    }
    private fun install(file:File){
        if(!safeToUpdate()||activity.isFinishing||activity.isDestroyed)return
        val expected=pendingUpdate ?: return
        if(busy)return
        busy=true
        Thread {
            val approved=runCatching { val latest=manifest(); latest!=null && latest.code==expected.code && latest.hash==expected.hash && latest.url==expected.url }.getOrDefault(false)
            activity.runOnUiThread {
                busy=false
                if(activity.isFinishing||activity.isDestroyed)return@runOnUiThread
                if(!approved){file.delete();pending=null;pendingUpdate=null;check(true)}
                else if(safeToUpdate())launchInstaller(file)
            }
        }.start()
    }
    private fun launchInstaller(file:File){
        if(Build.VERSION.SDK_INT>=26&&!activity.packageManager.canRequestPackageInstalls()){
            dialog=AlertDialog.Builder(activity).setTitle("Allow installation").setMessage("Allow this app to install updates, then return here.")
                .setCancelable(false).setPositiveButton("Open settings"){_,_->waitingSettings=true;activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+id)))}
                .setNegativeButton(if(pendingUpdate?.required==true)"Exit" else "Later"){_,_->if(pendingUpdate?.required==true)activity.finishAffinity()else{pending?.delete();pending=null;pendingUpdate=null}}.create()
            dialog?.show();return
        }
        try{
            val uri=FileProvider.getUriForFile(activity,id+".haluviupdates",file)
            awaitingInstaller=true
            activity.startActivityForResult(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).putExtra(Intent.EXTRA_RETURN_RESULT,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),INSTALL_REQUEST)
        }catch(e:Exception){
            awaitingInstaller=false
            installationRetry()
        }
    }
    private fun installationRetry(){
        val required=prefs.getLong("required",0)>installed()
        val builder=AlertDialog.Builder(activity).setTitle("Update installation").setMessage("Installation was not completed.")
            .setCancelable(!required).setPositiveButton("Retry"){d,_->d.dismiss();dialog=null;val f=pending;if(f!=null&&f.exists())install(f)else check(true)}
            .setNegativeButton(if(required)"Exit" else "Later"){_,_->if(required)activity.finishAffinity()else{pending?.delete();pending=null}}
        dialog=builder.create();dialog?.show()
    }
    fun onActivityResult(requestCode:Int){
        if(requestCode!=INSTALL_REQUEST)return
        awaitingInstaller=false
        if(activity.isFinishing||activity.isDestroyed)return
        if(installed()>=(pendingUpdate?.code ?: Long.MAX_VALUE)){pending?.delete();pending=null;pendingUpdate=null;return}
        installationRetry()
    }
    fun resume(){
        if(awaitingInstaller||!safeToUpdate())return
        if(waitingSettings){waitingSettings=false;dialog?.dismiss();dialog=null}
        val file=pending
        if(file!=null&&activity.packageManager.canRequestPackageInstalls()&&dialog?.isShowing!=true){install(file);return}
        if(file!=null&&dialog?.isShowing!=true){installationRetry();return}
        check()
    }
    companion object { const val INSTALL_REQUEST=8316 }
}
