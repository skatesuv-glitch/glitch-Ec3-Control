package com.ec3control.data.stellantis

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class StoredOtpSession(val state:OtpSessionState,val pin:String)

internal class OtpSecureStore(private val context:Context){
 private val prefs=context.getSharedPreferences("ec3_secure_otp",Context.MODE_PRIVATE)
 private val alias="ec3_remote_otp_v1"
 private fun key():SecretKey{
  val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  (ks.getKey(alias,null) as? SecretKey)?.let{return it}
  val kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
  kg.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
   .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  return kg.generateKey()
 }
 fun save(state:OtpSessionState,pin:String){
  require(pin.length==4 && pin.all(Char::isDigit))
  val plain=buildJsonObject{
   put("iwid",state.iwid);put("ts",state.iwTsync);put("k0",state.iwK0);put("k1",state.iwK1)
   put("sid",state.iwsecid);put("sval",state.iwsecval);put("alea",state.iwalea);put("dev",state.deviceId);put("pin",pin)
  }.toString().toByteArray()
  val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
  val enc=cipher.doFinal(plain)
  prefs.edit().putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
   .putString("data",Base64.encodeToString(enc,Base64.NO_WRAP)).apply()
 }
 fun load():StoredOtpSession?=try{
  val iv=Base64.decode(prefs.getString("iv",null)?:return null,Base64.NO_WRAP)
  val enc=Base64.decode(prefs.getString("data",null)?:return null,Base64.NO_WRAP)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))}
  val o=Json.parseToJsonElement(String(cipher.doFinal(enc))).jsonObject
  fun s(k:String)=requireNotNull(o[k]?.jsonPrimitive?.content)
  StoredOtpSession(OtpSessionState(s("iwid"),s("ts"),s("k0"),s("k1"),s("sid"),s("sval"),s("alea"),s("dev")),s("pin"))
 }catch(_:Exception){null}
 fun clear(){prefs.edit().clear().apply()}
}
