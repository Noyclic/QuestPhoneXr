package com.noyclic.questphonexr.runtime
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import java.io.File

enum class OpenXrRuntimeDiscoveryState { ACTIVE_RUNTIME_FOUND, NO_ACTIVE_RUNTIME, BROKER_UNAVAILABLE, RUNTIME_LIBRARY_MISSING, UNSUPPORTED_PROCESS_ABI }
data class OpenXrRuntimeCandidate(val packageName:String,val libraryPath:String,val loaderAbi:String,val brokerAuthority:String,val hasFunctionRemapping:Boolean,val libraryFileExists:Boolean)
data class OpenXrRuntimeDiscoveryReport(val state:OpenXrRuntimeDiscoveryState,val message:String,val processAbi:String?,val candidates:List<OpenXrRuntimeCandidate>) {
 val activeCandidate:OpenXrRuntimeCandidate? get()=candidates.firstOrNull{it.libraryFileExists}
}
/** Khronos Android Runtime Broker probe. Discovers metadata; never dlopens a runtime. */
object OpenXrRuntimeDiscovery {
 private const val SYSTEM="org.khronos.openxr.system_runtime_broker"
 private const val INSTALLABLE="org.khronos.openxr.runtime_broker"
 fun inspect(context:Context):OpenXrRuntimeDiscoveryReport {
  val abi=currentProcessLoaderAbi() ?: return report(OpenXrRuntimeDiscoveryState.UNSUPPORTED_PROCESS_ABI,"Не удалось определить ABI OpenXR-процесса.",null)
  val resolver=context.applicationContext.contentResolver
  var brokers=0
  val candidates=mutableListOf<OpenXrRuntimeCandidate>()
  listOf(INSTALLABLE,SYSTEM).forEach{authority->
   if(runCatching{context.packageManager.resolveContentProvider(authority,0)!=null}.getOrDefault(false)) brokers++
   val uri=Uri.parse("content://$authority/openxr/1/abi/$abi/runtimes/active")
   val rows=runCatching{
    resolver.query(uri,arrayOf("package_name","native_lib_dir","so_filename","has_functions"),null,null,null)?.use{cursor->
     val p=cursor.getColumnIndex("package_name"); val d=cursor.getColumnIndex("native_lib_dir")
     val f=cursor.getColumnIndex("so_filename"); val h=cursor.getColumnIndex("has_functions")
     if(p<0||d<0||f<0) emptyList() else buildList{
      while(cursor.moveToNext()){
       val pkg=cursor.getString(p).orEmpty(); val dir=cursor.getString(d).orEmpty(); val file=cursor.getString(f).orEmpty()
       if(pkg.isBlank()||dir.isBlank()||file.isBlank()||file.contains('/')) continue
       val lib=File(dir,file)
       add(OpenXrRuntimeCandidate(pkg,lib.absolutePath,abi,authority,h>=0&&!cursor.isNull(h)&&cursor.getInt(h)!=0,lib.isFile))
      }
     }
    }.orEmpty()
   }.getOrDefault(emptyList())
   candidates+=rows
  }
  val usable=candidates.firstOrNull{it.libraryFileExists}
  val state=when{usable!=null->OpenXrRuntimeDiscoveryState.ACTIVE_RUNTIME_FOUND;candidates.isNotEmpty()->OpenXrRuntimeDiscoveryState.RUNTIME_LIBRARY_MISSING;brokers>0->OpenXrRuntimeDiscoveryState.NO_ACTIVE_RUNTIME;else->OpenXrRuntimeDiscoveryState.BROKER_UNAVAILABLE}
  val message=when(state){
   OpenXrRuntimeDiscoveryState.ACTIVE_RUNTIME_FOUND->"Найден Runtime ${usable!!.packageName}; библиотека найдена. XR-инициализация ещё не проверена."
   OpenXrRuntimeDiscoveryState.NO_ACTIVE_RUNTIME->"Khronos Runtime Broker найден, но активный Runtime не выбран."
   OpenXrRuntimeDiscoveryState.BROKER_UNAVAILABLE->"Khronos Runtime Broker не найден. Установи официальный OpenXR Runtime Broker, открой его и выбери Monado XR (Out-of-Proc), затем проверь статус снова."
   OpenXrRuntimeDiscoveryState.RUNTIME_LIBRARY_MISSING->"Broker сообщил о Runtime, но его .so библиотека не найдена."
   OpenXrRuntimeDiscoveryState.UNSUPPORTED_PROCESS_ABI->"ABI процесса не поддерживается проверкой."
  }
  return report(state,message,abi,candidates)
 }
 internal fun currentProcessLoaderAbi(supportedAbis:List<String> = Build.SUPPORTED_ABIS.toList(),is64Bit:Boolean=Process.is64Bit()):String? =
  supportedAbis.firstNotNullOfOrNull{abi->val bits=abi=="arm64-v8a"||abi=="x86_64"||abi=="riscv64";if(bits!=is64Bit)null else toLoaderAbi(abi)}
 /** Android ContentProvider ABI values match the Khronos loader's NDK ABI string. */
 internal fun toLoaderAbi(androidAbi:String):String?=when(androidAbi){"arm64-v8a"->"arm64-v8a";"armeabi-v7a"->"armeabi-v7a";"x86_64"->"x86_64";"x86"->"x86";"riscv64"->"riscv64";else->null}
 private fun report(s:OpenXrRuntimeDiscoveryState,m:String,a:String?,c:List<OpenXrRuntimeCandidate> = emptyList())=OpenXrRuntimeDiscoveryReport(s,m,a,c)
}
