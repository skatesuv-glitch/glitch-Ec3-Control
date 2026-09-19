package com.ec3control.data.stellantis

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal data class OtpActivationSetup(val kfact:String,val kiw:String,val pinmode:String)
internal data class OtpActivationResult(val ok:Boolean,val message:String)
internal data class OtpMsRequest(val params:Map<String,String>,val secId:String,val secVal:String)

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

    init {
        // Exact initial IWData fields consumed by load.py/load1xx from DEFAULT_TOKEN.
        // Empty tokens become zero, matching Tokenizer.nextTokenI().
        val t=DefaultIwTokenizer(DEFAULT_TOKEN)
        t.next()
        iwid=t.next()
        // The community implementation replaces the token's empty iwalea with a fresh random value.
        t.next()
        t.nextIntHex()
        t.nextIntHex()
        iwTsync=t.nextIntHex().toString()
        t.next() // initial kfact
        t.nextIntHex() // connected
        t.next() // server
        t.next() // J
        t.next() // K
        iwK0=t.next()
        iwK1=t.next()
    }

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
        val modulus=BigInteger(kiw,16)
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
        return OtpMsRequest(
            params=mapOf(
                "action" to "ActionFinalize","mode" to "ms","ms_id0" to requireNotNull(xml["ms_id"]),
                "ms_val0" to encodedKey,"macid" to macId,"id" to iwid,"lastsync" to iwTsync,"ms_n" to "1"
            )+r,
            secId=secId,
            secVal=secVal
        )
    }

    private fun serial()=deviceId+"/_/"+iwalea
    private fun generateKma(pin:String)=sha256Hex((pin+";"+serial()).toByteArray()).take(32)
    private class DefaultIwTokenizer(private val value:String){
        private var index=0
        fun next():String{
            if(index>=value.length) return ""
            val end=value.indexOf("&&",index)
            if(end<0){val out=value.substring(index);index=value.length;return out}
            val out=value.substring(index,end);index=end+2;return out
        }
        fun nextIntHex():Int=next().takeIf{it.isNotEmpty()}?.toInt(16) ?: 0
    }

    private companion object {
        private const val DEFAULT_TOKEN="0.2.11&&&&&&0&&0&&0&&9f13ba238fbabba08e85d93638e98ef5e48682a9d3e5bc325c3dd6fac8199a6ce09e9b4f373aa6a75a905c3d690f6e3335d1e8e5b748ecec3020a794149033f6ada6896db6d73b8d43b8365bbe15b9ac66f49d4e684a3628f1e9f3deda0c4e24aba771946e6085b92c5ad312477152acf8db01e6aea4b409d5ac1a05c2fd4e95&&0&&&&&&&&&&&&0&&0&&0&&0&&0&&0&&0&&0&&&&&&&&0&&0&&0&&0&&0&&2.0.0&&http://m.inwebo.com/&&"
    }

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
