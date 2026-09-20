package com.ec3control.data.stellantis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

data class OtpNetworkResult(val ok:Boolean,val message:String,val session:String?=null)

class StellantisOtpNetwork(private val http:OkHttpClient=OkHttpClient()){
 private var activeOtp:StellantisOtpActivation?=null
 suspend fun activate(accessToken:String,smsCode:String,pin:String):OtpNetworkResult=withContext(Dispatchers.IO){
  require(accessToken.length>=16){"OAuth token unavailable"}
  require(smsCode.isNotBlank()){"SMS code required"}
  require(pin.length==4 && pin.all(Char::isDigit)){"4 digit PIN required"}
  val otp=StellantisOtpActivation(INWEBO_ACCESS_ID,accessToken.take(16))
  try{
   val setup=try{get(otp.setupParams(smsCode),true)}catch(e:Exception){throw IllegalStateException("SETUP: "+(e.message ?: "error"))}
   try{otp.acceptSetup(setup)}catch(e:Exception){throw IllegalStateException("SETUP-CRYPTO: "+(e.message ?: "error"))}
   val fin=try{get(otp.finalizeParams(smsCode,pin),false)}catch(e:Exception){throw IllegalStateException("FINALIZE: "+(e.message ?: "error"))}
   val first=try{otp.synchronize(fin,pin)}catch(e:Exception){throw IllegalStateException("FINALIZE-SYNC: "+(e.message ?: "error"))}
   if(!first.ok) return@withContext OtpNetworkResult(false,first.message)
   val ms=otp.buildMsSync(fin,pin)
   if(ms!=null){
    val wire=ms.params.filterKeys{!it.startsWith("_local_")}
    val msResponse=try{get(wire,false)}catch(e:Exception){throw IllegalStateException("MS-SYNC: "+(e.message ?: "error"))}
    val msSync=try{otp.acceptMsSync(msResponse,pin,ms)}catch(e:Exception){throw IllegalStateException("MS-SYNC-CRYPTO: "+(e.message ?: "error"))}
    if(!msSync.ok) return@withContext OtpNetworkResult(false,msSync.message)
    otp.sessionState()
   }
   otp.sessionState()
   activeOtp=otp
   OtpNetworkResult(true,"OTP activado. Preparado para solicitar token RemoteServices.")
  }catch(e:Exception){
   OtpNetworkResult(false,"Activación OTP: "+(e.message ?: "error desconocido")+". Solicita un código nuevo y vuelve a intentarlo.")
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
 suspend fun requestRemoteServicesToken(accessToken:String,pin:String,realm:String="clientsB2CCitroen"):OtpNetworkResult=withContext(Dispatchers.IO){
  try{
   val password=generatePasswordOtp(pin)
   val url=HttpUrl.Builder().scheme("https").host("api.groupe-psa.com")
    .addPathSegments("connectedcar/v4/virtualkey/remoteaccess/token")
    .addQueryParameter("client_id",com.ec3control.BuildConfig.CITROEN_CLIENT_ID).addQueryParameter("locale","es-ES").build()
   val body=("{\"grant_type\":\"password\",\"password\":\""+password+"\"}").toRequestBody("application/json".toMediaType())
   val req=Request.Builder().url(url).header("Authorization","Bearer $accessToken").header("x-introspect-realm",realm)
    .header("User-Agent","okhttp/4.8.0").header("Accept","application/hal+json").post(body).build()
   http.newCall(req).execute().use{r->
    val raw=r.body?.string().orEmpty()
    if(!r.isSuccessful) return@withContext OtpNetworkResult(false,"RemoteServices token HTTP "+r.code)
    val obj=Json.parseToJsonElement(raw).jsonObject
    val remoteToken=requireNotNull(obj["access_token"]?.jsonPrimitive?.content){"RemoteServices access token missing"}
    OtpNetworkResult(true,"Token RemoteServices obtenido. No se ha enviado ninguna orden al vehículo.",remoteToken)
   }
  }catch(_:Exception){
   OtpNetworkResult(false,"No se pudo obtener el token RemoteServices.")
  }
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
  val factory=DocumentBuilderFactory.newInstance().apply{
   isNamespaceAware=false
   setExpandEntityReferences(false)
   fun safeFeature(name:String,value:Boolean){ try{ setFeature(name,value) }catch(_:Exception){} }
   safeFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
   safeFeature("http://xml.org/sax/features/external-general-entities",false)
   safeFeature("http://xml.org/sax/features/external-parameter-entities",false)
   safeFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false)
  }
  val start=raw.indexOf("<"+tag)
  require(start>=0){"OTP response missing "+tag}
  val endTag="</"+tag+">"
  val end=raw.indexOf(endTag,start)
  require(end>=0){"OTP response incomplete "+tag}
  val cleaned=raw.substring(start,end+endTag.length)
  val doc=factory.newDocumentBuilder().parse(ByteArrayInputStream(cleaned.toByteArray(Charsets.UTF_8)))
  val root=doc.getElementsByTagName(tag).item(0) as? Element ?: error("Bad OTP response")
  val out=linkedMapOf<String,String>()
  for(i in 0 until root.childNodes.length){val n=root.childNodes.item(i);if(n is Element)out[n.tagName]=n.textContent.orEmpty()}
  root.attributes?.let{a->for(i in 0 until a.length){val n=a.item(i);out["@"+n.nodeName]=n.nodeValue}}
  return out
 }
 companion object{private const val INWEBO_ACCESS_ID="bb8e981582b0f31353108fb020bead1c"}
}
