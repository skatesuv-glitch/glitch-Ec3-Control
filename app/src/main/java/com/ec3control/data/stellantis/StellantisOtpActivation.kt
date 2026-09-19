package com.ec3control.data.stellantis

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal data class OtpActivationSetup(val kfact:String,val kiw:String,val pinmode:String)
internal data class OtpActivationResult(val ok:Boolean,val message:String)\ninternal data class OtpMsRequest(val params:Map<String,String>)

/** Local state for the InWebo activation handshake. Secrets are kept in memory only. */
internal class StellantisOtpActivation(
    private val macId:String,
    private val deviceId:String,
    private val random:SecureRandom=SecureRandom()
){
    private val iwalea=ByteArray(16).also(random::nextBytes).toHex()
    private var iwid=""
    private var iwTsync="0"
    private var iwK0=""
    private var iwK1=""
    private var kfact=""
    private var kiw=""
    private var pinmode=""

    fun setupParams(smsCode:String)=mapOf(
        "action" to "ActionSetup","mode" to "activate","id" to iwid,"lastsync" to iwTsync,
        "version" to "Generator-1.0/0.2.11","macid" to macId,"code" to smsCode
    )

    fun acceptSetup(xml:Map<String,String>):OtpActivationSetup{
        require(xml["err"]=="OK"){"OTP setup rejected"}
        kiw=requireNotNull(xml["Kiw"]); kfact=requireNotNull(xml["Kfact"]); pinmode=requireNotNull(xml["pinmode"])
        return OtpActivationSetup(kfact,kiw,pinmode)
    }

    fun finalizeParams(smsCode:String,pin:String):Map<String,String>{
        require(pin.length==4 && pin.all(Char::isDigit)){"PIN must have 4 digits"}
        val modulus=BigInteger(kfact,16)
        val kma=generateKma(pin)
        val kmaCrypt=StellantisOaep.encode(kma.hexToBytes(),modulus,random=random).toHex()
        val pinCrypt=StellantisOaep.encode(pin.toByteArray(),modulus,random=random).toHex()
        val r=getR(pin)
        return mapOf(
            "action" to "ActionFinalize","mode" to "activate","id" to iwid,"lastsync" to iwTsync,
            "version" to "Generator-1.0/0.2.11","lang" to "fr","ack" to "","macid" to macId,
            "serial" to serial(),"code" to smsCode,"Kma" to kmaCrypt,"pin" to pinCrypt,
            "name" to "Android SDK built for x86_64 / UNKNOWN"
        )+r
    }

    fun synchronize(xml:Map<String,String>,pin:String):OtpActivationResult{
        if(xml["err"]!="OK") return OtpActivationResult(false,"OTP activation rejected")
        val key=SecretKeySpec(generateKma(pin).hexToBytes(),"AES")
        val aes=Cipher.getInstance("AES/ECB/NoPadding").apply{init(Cipher.DECRYPT_MODE,key)}
        xml["id"]?.takeIf{it.isNotEmpty()}?.let{iwid=it}
        xml["Tsync"]?.takeIf{it.isNotEmpty()}?.let{iwTsync=it}
        xml["K0"]?.takeIf{it.isNotEmpty()}?.let{iwK0=aes.doFinal(it.hexToBytes()).toHex()}
        xml["K1"]?.takeIf{it.isNotEmpty()}?.let{iwK1=aes.doFinal(it.hexToBytes()).toHex()}
        xml["dK1"]?.takeIf{it.isNotEmpty()}?.let{iwK1=sha256Hex((iwK1+";"+it).toByteArray()).take(32)}
        return OtpActivationResult(true,if((xml["ms_n"]?.toIntOrNull() ?: 0)>0)"OTP activation accepted; MS sync pending" else "OTP activation accepted")
    }

    fun buildMsSync(xml:Map<String,String>,pin:String):OtpMsRequest?{
        val count=xml["ms_n"]?.toIntOrNull() ?: 0
        if(count==0) return null
        require(count==1){"Unsupported MS sync count"}
        val challenge=requireNotNull(xml["challenge"])
        val serverModulus=StellantisOaep.decodePublicOperation(requireNotNull(xml["ms_key"]).hexToBytes(),BigInteger(kfact,16))
        val randomKey=ByteArray(16).also(random::nextBytes)
        val encodedKey=StellantisOaep.encode(randomKey,BigInteger(1,serverModulus),random=random).toHex()
        val aes=Cipher.getInstance("AES/ECB/NoPadding").apply{init(Cipher.ENCRYPT_MODE,SecretKeySpec(generateKma(pin).hexToBytes(),"AES"))}
        val secVal=aes.doFinal(randomKey).toHex()
        val secId=requireNotNull(xml["s_id"])
        val iw=iwK0
        val r=mapOf(
            "R0" to sha256Hex((challenge+";"+iw+";"+serial()).toByteArray()),
            "R1" to sha256Hex((challenge+";"+iw+";"+iwK1).toByteArray()),
            "R2" to sha256Hex((challenge+";"+iw+";"+pin).toByteArray())
        )
        return OtpMsRequest(mapOf(
            "action" to "ActionFinalize","mode" to "ms","ms_id0" to requireNotNull(xml["ms_id"]),
            "ms_val0" to encodedKey,"macid" to macId,"id" to iwid,"lastsync" to iwTsync,"ms_n" to "1"
        )+r+mapOf("_local_sec_id" to secId,"_local_sec_val" to secVal))
    }

    private fun serial()=deviceId+"/_/"+iwalea
    private fun generateKma(pin:String)=sha256Hex((pin+";"+serial()).toByteArray()).take(32)
    private fun getR(pin:String):Map<String,String>{
        val iw=iwK0
        val challenge=""
        return mapOf(
            "R0" to sha256Hex((challenge+";"+iw+";"+serial()).toByteArray()),
            "R1" to sha256Hex((challenge+";"+iw+";"+iwK1).toByteArray()),
            "R2" to sha256Hex((challenge+";"+iw+";").toByteArray())
        )
    }
}
private fun sha256Hex(v:ByteArray)=MessageDigest.getInstance("SHA-256").digest(v).toHex()
private fun ByteArray.toHex()=joinToString(""){"%02x".format(it)}
private fun String.hexToBytes()=chunked(2).map{it.toInt(16).toByte()}.toByteArray()
