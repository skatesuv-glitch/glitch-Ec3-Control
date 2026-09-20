package com.ec3control.data.stellantis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class OtpNetworkResult(val ok:Boolean,val message:String)

class StellantisOtpNetwork(private val http:OkHttpClient=OkHttpClient()){
 private var activeOtp:StellantisOtpActivation?=null
 suspend fun activate(accessToken:String,smsCode:String,pin:String):OtpNetworkResult=withContext(Dispatchers.IO){
  require(accessToken.length>=16){"OAuth token unavailable"}
  require(smsCode.isNotBlank()){"SMS code required"}
  require(pin.length==4 && pin.all(Char::isDigit)){"4 digit PIN required"}
  val otp=StellantisOtpActivation(INWEBO_ACCESS_ID,accessToken.take(16))
  try{
   val setup=get(otp.setupParams(smsCode),true)
   otp.acceptSetup(setup)
   val fin=get(otp.finalizeParams(smsCode,pin),false)
   val first=otp.synchronize(fin,pin)
   if(!first.ok) return@withContext OtpNetworkResult(false,first.message)
   val ms=otp.buildMsSync(fin,pin)
   if(ms!=null){
    val wire=ms.params.filterKeys{!it.startsWith("_local_")}
    val msResponse=get(wire,false)
    val msSync=otp.acceptMsSync(msResponse,pin,ms)
    if(!msSync.ok) return@withContext OtpNetworkResult(false,msSync.message)
    otp.sessionState()
   }
   OtpNetworkResult(true,"OTP activado. Preparado para solicitar token RemoteServices.")
  }catch(_:Exception){
   OtpNetworkResult(false,"No se pudo completar la activación OTP. Solicita un código nuevo y vuelve a intentarlo.")
  }
 }
 suspend fun generatePasswordOtp(pin:String):String=withContext(Dispatchers.IO){
  require(pin.length==4 && pin.all(Char::isDigit)){"4 digit PIN required"}
  val otp=requireNotNull(activeOtp){"OTP session unavailable"}
  fun cycle():Pair<Boolean,String>{
   val setup=get(otp.otpSetupParams(),true)
   otp.acceptOtpSetup(setup)
   val fin=get(otp.otpFinalizeParams(),false)
   val twice=otp.acceptOtpFinalize(fin,pin)
   val defi=requireNotNull(fin["defi"]){"OTP defi missing"}
   return twice to defi
  }
  var result=cycle()
  if(result.first) result=cycle()
  otp.generateOtp(result.second)
 }
 private fun get(params:Map<String,String>,setup:Boolean):Map<String,String>{
  val b=HttpUrl.Builder().scheme("https").host("otp.mpsa.com").addPathSegments("iwws/MAC")
  params.forEach{(k,v)->b.addQueryParameter(k,v)}
  val req=Request.Builder().url(b.build()).header("Connection","Keep-Alive").header("Host","otp.mpsa.com")
   .header("User-Agent","Dalvik/2.1.0 (Linux; U; Android 8.0.0; Android SDK built for x86_64 Build/OSR1.180418.004)").get().build()
  http.newCall(req).execute().use{r->
   if(!r.isSuccessful) throw IllegalStateException("OTP HTTP")
   return parse(r.body?.string().orEmpty(),if(setup)"ActionSetup" else "ActionFinalize")
  }
 }
 private fun parse(raw:String,tag:String):Map<String,String>{
  val doc=DocumentBuilderFactory.newInstance().apply{
   setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
   setFeature("http://xml.org/sax/features/external-general-entities",false)
   setFeature("http://xml.org/sax/features/external-parameter-entities",false)
  }.newDocumentBuilder().parse(ByteArrayInputStream(raw.toByteArray()))
  val root=doc.getElementsByTagName(tag).item(0) as? Element ?: error("Bad OTP response")
  val out=linkedMapOf<String,String>()
  for(i in 0 until root.childNodes.length){val n=root.childNodes.item(i);if(n is Element)out[n.tagName]=n.textContent.orEmpty()}
  root.attributes?.let{a->for(i in 0 until a.length){val n=a.item(i);out["@"+n.nodeName]=n.nodeValue}}
  return out
 }
 companion object{private const val INWEBO_ACCESS_ID="bb8e981582b0f31353108fb020bead1c"}
}
