package com.ec3control.data.stellantis

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class OtpNetworkResult(val ok:Boolean,val message:String,val session:String?=null)

class StellantisOtpNetwork(private val context:Context?=null,private val http:OkHttpClient=OkHttpClient()){
 private val secureStore=context?.let{OtpSecureStore(it.applicationContext)}
 private var storedPin:String?=null
 private var activeOtp:StellantisOtpActivation?=null
 init{
  secureStore?.load()?.let{saved->
   storedPin=saved.pin
   activeOtp=StellantisOtpActivation(INWEBO_ACCESS_ID,saved.state.deviceId,restored=saved.state)
  }
 }
 fun hasStoredOtpSession()=activeOtp!=null && storedPin!=null
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
   secureStore?.save(otp.sessionState(),pin)
   storedPin=pin
   OtpNetworkResult(true,"OTP activado y sesión guardada cifrada en este teléfono.")
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
    OtpNetworkResult(true,"Token RemoteServices obtenido. No se ha enviado ninguna orden al vehículo.",remoteToken+"\u0000"+(obj["token_type"]?.jsonPrimitive?.content ?: "")+"\u0000"+(obj["expires_in"]?.jsonPrimitive?.content ?: ""))
   }
  }catch(_:Exception){
   OtpNetworkResult(false,"No se pudo obtener el token RemoteServices.")
  }
 }
 suspend fun requestRemoteServicesTokenStored(accessToken:String,realm:String="clientsB2CCitroen"):OtpNetworkResult{
  val pin=storedPin ?: return OtpNetworkResult(false,"No hay sesión OTP segura guardada.")
  return requestRemoteServicesToken(accessToken,pin,realm)
 }
 suspend fun probeMqttReadOnly(oauthToken:String,remoteSession:String,realm:String="clientsB2CCitroen"):OtpNetworkResult=withContext(Dispatchers.IO){
  val remoteParts=remoteSession.split("\u0000")
  val remoteToken=remoteParts.firstOrNull().orEmpty()
  val tokenType=remoteParts.getOrNull(1).orEmpty()
  val expires=remoteParts.getOrNull(2).orEmpty()
  var socket:SSLSocket?=null
  try{
   val associationUrl=HttpUrl.Builder().scheme("https").host("api.groupe-psa.com").addPathSegments("applications/cvs/v4/mauv/car-associations")
    .addQueryParameter("client_id",com.ec3control.BuildConfig.CITROEN_CLIENT_ID).addQueryParameter("locale","es-ES").build()
   val associationRequest=Request.Builder().url(associationUrl).header("Authorization","Bearer "+oauthToken).header("x-introspect-realm",realm)
    .header("x-transaction-id","1234").header("User-Agent","okhttp/4.8.0").header("Accept","application/hal+json").get().build()
   val raw=http.newCall(associationRequest).execute().use{r->if(!r.isSuccessful)error("association HTTP "+r.code);r.body?.string().orEmpty()}
   val association=Json.parseToJsonElement(raw).jsonArray.firstOrNull()?.jsonObject ?: error("association missing")
   val customer=requireNotNull(association["customer"]?.jsonPrimitive?.content){"customer missing"}
   val vehicle=requireNotNull(association["vehicle"]?.jsonPrimitive?.content){"vehicle missing"}
   socket=(SSLSocketFactory.getDefault().createSocket("mwa.mpsa.com",8885) as SSLSocket).apply{
    soTimeout=12000
    val p=sslParameters
    p.endpointIdentificationAlgorithm="HTTPS"
    sslParameters=p
    startHandshake()
   }
   val input=DataInputStream(socket.inputStream)
   val output=DataOutputStream(socket.outputStream)
   output.write(mqttConnectPacket(remoteToken));output.flush()
   val connack=readMqttPacket(input)
   if(connack.first!=0x20 || connack.second.size<2) error("CONNACK inválido")
   val connackCode=connack.second[1].toInt() and 0xff
   if(connackCode!=0) error("CONNACK código "+connackCode+" ("+mqttConnackMeaning(connackCode)+")")
   mqttSubscribe(output,1,"psa/RemoteServices/to/cid/"+customer+"/#")
   requireSubAck(input,1)
   mqttSubscribe(output,2,"psa/RemoteServices/events/MPHRTServices/"+vehicle)
   requireSubAck(input,2)
   output.write(byteArrayOf(0xE0.toByte(),0x00));output.flush()
   OtpNetworkResult(true,"MQTT 3.1.1 conectado con Client ID vacío y suscrito en solo lectura. Cero órdenes publicadas.")
  }catch(e:Exception){
   val detail=listOfNotNull(e::class.java.simpleName,e.message,e.cause?.message).filter{it.isNotBlank()}.distinct().joinToString(" | ")
   OtpNetworkResult(false,"MQTT ZERO-ID: "+detail+" · tokenType="+tokenType.ifBlank{"?"}+" · expires="+expires.ifBlank{"?"}+". Token oculto.")
  }finally{try{socket?.close()}catch(_:Exception){}}
 }
 private fun mqttConnackMeaning(code:Int)=when(code){
  0->"accepted"
  1->"unacceptable protocol version"
  2->"identifier rejected"
  3->"server unavailable"
  4->"bad username/password"
  5->"not authorized"
  else->"unknown"
 }
 private fun mqttConnectPacket(token:String):ByteArray{
  val vh=ByteArrayOutputStream();val d=DataOutputStream(vh)
  mqttUtf(d,"MQTT");d.writeByte(4);d.writeByte(0xC2);d.writeShort(60)
  mqttUtf(d,"");mqttUtf(d,"IMA_OAUTH_ACCESS_TOKEN");mqttUtf(d,token);d.flush()
  return mqttPacket(0x10,vh.toByteArray())
 }
 private fun mqttSubscribe(out:DataOutputStream,id:Int,topic:String){
  val b=ByteArrayOutputStream();val d=DataOutputStream(b);d.writeShort(id);mqttUtf(d,topic);d.writeByte(0);d.flush()
  out.write(mqttPacket(0x82,b.toByteArray()));out.flush()
 }
 private fun requireSubAck(input:DataInputStream,id:Int){
  val p=readMqttPacket(input)
  if(p.first!=0x90 || p.second.size<3) error("SUBACK inválido")
  val got=((p.second[0].toInt() and 0xff) shl 8) or (p.second[1].toInt() and 0xff)
  if(got!=id || (p.second[2].toInt() and 0xff)==0x80) error("SUBACK rechazado")
 }
 private fun readMqttPacket(input:DataInputStream):Pair<Int,ByteArray>{
  val type=input.readUnsignedByte();var mult=1;var len=0
  do{val b=input.readUnsignedByte();len+=(b and 127)*mult;mult*=128;if(mult>128*128*128*128)error("MQTT length")}while((b and 128)!=0)
  val payload=ByteArray(len);input.readFully(payload);return type to payload
 }
 private fun mqttPacket(header:Int,payload:ByteArray):ByteArray{
  val b=ByteArrayOutputStream();b.write(header);var x=payload.size
  do{var digit=x%128;x/=128;if(x>0)digit=digit or 128;b.write(digit)}while(x>0)
  b.write(payload);return b.toByteArray()
 }
 private fun mqttUtf(out:DataOutputStream,value:String){
  val bytes=value.toByteArray(Charsets.UTF_8);require(bytes.size<=65535);out.writeShort(bytes.size);out.write(bytes)
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
