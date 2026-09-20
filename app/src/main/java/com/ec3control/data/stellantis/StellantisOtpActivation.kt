package com.ec3control.data.stellantis

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal data class OtpActivationSetup(val kfact:String,val kiw:String,val pinmode:String)
internal data class OtpActivationResult(val ok:Boolean,val message:String)
internal data class OtpMsRequest(val params:Map<String,String>,val secId:String,val secVal:String)
internal data class OtpSessionState(val iwid:String,val iwTsync:String,val iwK0:String,val iwK1:String,val iwsecid:String,val iwsecval:String,val iwalea:String,val deviceId:String)

/** Local state for the InWebo activation handshake. Secrets are kept in memory only. */
internal class StellantisOtpActivation(
    private val macId:String,
    private val deviceId:String,
    restored:OtpSessionState?=null,
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
    private var challenge=""
    private var iwsecid=""
    private var iwsecval=""

    init {
        if(restored!=null){
            iwid=restored.iwid; iwTsync=restored.iwTsync; iwK0=restored.iwK0; iwK1=restored.iwK1; iwsecid=restored.iwsecid; iwsecval=restored.iwsecval
        }
        // Exact initial IWData fields consumed by load.py/load1xx from DEFAULT_TOKEN.
        // Empty tokens become zero, matching Tokenizer.nextTokenI().
        if(restored==null){
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
    }

    fun setupParams(smsCode:String)=mapOf(
        "action" to "ActionSetup","mode" to "activate","id" to iwid,"lastsync" to iwTsync,
        "version" to "Generator-1.0/0.2.11","macid" to macId,"code" to smsCode
    )

    fun acceptSetup(xml:Map<String,String>):OtpActivationSetup{
        require(xml["err"]=="OK"){"OTP setup rejected"}
        val encodedKiw=requireNotNull(xml["Kiw"]); kfact=requireNotNull(xml["Kfact"]); pinmode=requireNotNull(xml["pinmode"])
        kiw=StellantisOaep.decodePublicOperation(encodedKiw.hexToBytes(),BigInteger(kfact,16)).toHex()
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
        xml["challenge"]?.let{challenge=it}
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
        val challenge=requireNotNull(xml["challenge"]).also{this.challenge=it}
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

    fun acceptMsSync(xml:Map<String,String>,pin:String,request:OtpMsRequest):OtpActivationResult{
        if(xml["err"]!="OK") return OtpActivationResult(false,"OTP MS synchronization rejected")
        iwsecid=request.secId
        iwsecval=request.secVal
        return synchronize(xml,pin)
    }

    fun sessionState():OtpSessionState{
        require(iwid.isNotBlank() && iwK1.isNotBlank() && iwsecid.isNotBlank() && iwsecval.isNotBlank()){"OTP session incomplete"}
        return OtpSessionState(iwid,iwTsync,iwK0,iwK1,iwsecid,iwsecval,iwalea,deviceId)
    }

    fun otpSetupParams()=mapOf(
        "action" to "ActionSetup","mode" to "otp","id" to iwid,"lastsync" to iwTsync,
        "version" to "Generator-1.0/0.2.11","macid" to macId,"sid" to iwsecid
    )

    fun acceptOtpSetup(xml:Map<String,String>){
        require(xml["err"]=="OK"){"OTP setup rejected"}
        challenge=requireNotNull(xml["challenge"])
    }

    fun otpFinalizeParams():Map<String,String>{
        val iw=iwK0
        val r=mapOf(
            "R0" to sha256Hex((challenge+";"+iw+";"+serial()).toByteArray()),
            "R1" to sha256Hex((challenge+";"+iw+";"+iwK1).toByteArray()),
            "R2" to sha256Hex((challenge+";"+iw+";").toByteArray())
        )
        return mapOf(
            "action" to "ActionFinalize","mode" to "otp","id" to iwid,"lastsync" to iwTsync,
            "version" to "Generator-1.0/0.2.11","lang" to "fr","ack" to "","macid" to macId,
            "keytype" to "0","sid" to iwsecid
        )+r
    }

    fun acceptOtpFinalize(xml:Map<String,String>,pin:String):Boolean{
        val sync=synchronize(xml,pin)
        require(sync.ok){sync.message}
        return xml.containsKey("J")
    }

    fun generateOtp(defi:String):String{
        require(iwK1.isNotBlank() && iwsecval.isNotBlank()){"OTP session incomplete"}
        val digest=MessageDigest.getInstance("SHA-256").digest((iwK1+":"+defi+":"+iwsecval).toByteArray())
        fun u32(off:Int):Long=((digest[off].toLong() and 255L) shl 24) or ((digest[off+1].toLong() and 255L) shl 16) or ((digest[off+2].toLong() and 255L) shl 8) or (digest[off+3].toLong() and 255L)
        var n=((u32(0) and 0x0fffffffL)*1024L)+(u32(4) and 1023L)
        if(n==0L) return "0"
        val alphabet="abcdefghijklmnopqrstuvwxyz0123456789"
        val out=StringBuilder()
        while(n>0){out.append(alphabet[(n%36L).toInt()]);n/=36L}
        return out.toString()
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
        val currentChallenge=challenge
        return mapOf(
            "R0" to sha256Hex((currentChallenge+";"+iw+";"+serial()).toByteArray()),
            "R1" to sha256Hex((currentChallenge+";"+iw+";"+iwK1).toByteArray()),
            "R2" to sha256Hex((currentChallenge+";"+iw+";").toByteArray())
        )
    }
}
private fun sha256Hex(v:ByteArray)=MessageDigest.getInstance("SHA-256").digest(v).toHex()
private fun ByteArray.toHex()=joinToString(""){"%02x".format(it)}
private fun String.hexToBytes()=chunked(2).map{it.toInt(16).toByte()}.toByteArray()
