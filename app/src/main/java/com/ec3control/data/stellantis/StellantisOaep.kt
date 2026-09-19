package com.ec3control.data.stellantis

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** Pure Kotlin/JCA port of the OAEP-SHA256 primitive used by the community
 * Stellantis/InWebo implementation. No credentials are logged or persisted. */
internal object StellantisOaep {
    private const val HLEN=32

    fun encode(message:ByteArray, modulus:BigInteger, exponent:BigInteger=BigInteger.valueOf(17), random:SecureRandom=SecureRandom()):ByteArray{
        val k=(modulus.bitLength()+7)/8
        require(message.size<=k-2*HLEN-2){"OAEP message too long"}
        val lHash=sha256(byteArrayOf())
        val db=ByteArray(k-HLEN-1)
        System.arraycopy(lHash,0,db,0,HLEN)
        db[db.size-message.size-1]=1
        System.arraycopy(message,0,db,db.size-message.size,message.size)
        val seed=ByteArray(HLEN).also(random::nextBytes)
        val dbMask=mgf1(seed,db.size)
        val maskedDb=xor(db,dbMask)
        val seedMask=mgf1(maskedDb,HLEN)
        val maskedSeed=xor(seed,seedMask)
        val em=byteArrayOf(0)+maskedSeed+maskedDb
        return i2osp(BigInteger(1,em).modPow(exponent,modulus),k)
    }

    /** Mirrors community MyOAEP.decrypt: public exponent operation, then OAEP decode. */
    fun decodePublicOperation(ciphertext:ByteArray, modulus:BigInteger, exponent:BigInteger=BigInteger.valueOf(17)):ByteArray{
        val k=(modulus.bitLength()+7)/8
        require(ciphertext.size==k){"OAEP ciphertext length"}
        val em=i2osp(BigInteger(1,ciphertext).modPow(exponent,modulus),k)
        require(em[0].toInt()==0){"OAEP leading byte"}
        val maskedSeed=em.copyOfRange(1,1+HLEN)
        val maskedDb=em.copyOfRange(1+HLEN,em.size)
        val seed=xor(maskedSeed,mgf1(maskedDb,HLEN))
        val db=xor(maskedDb,mgf1(seed,maskedDb.size))
        require(db.copyOfRange(0,HLEN).contentEquals(sha256(byteArrayOf()))){"OAEP label hash"}
        var one=-1
        for(i in HLEN until db.size){ if(db[i].toInt()==1){one=i;break}; require(db[i].toInt()==0){"OAEP padding"} }
        require(one>=0){"OAEP separator"}
        return db.copyOfRange(one+1,db.size)
    }

    private fun mgf1(seed:ByteArray,len:Int):ByteArray{
        val out=ByteArray(len); var pos=0; var counter=0
        while(pos<len){
            val c=byteArrayOf((counter ushr 24).toByte(),(counter ushr 16).toByte(),(counter ushr 8).toByte(),counter.toByte())
            val h=sha256(seed+c); val n=minOf(h.size,len-pos); System.arraycopy(h,0,out,pos,n); pos+=n; counter++
        }
        return out
    }
    private fun sha256(v:ByteArray)=MessageDigest.getInstance("SHA-256").digest(v)
    private fun xor(a:ByteArray,b:ByteArray)=ByteArray(a.size){(a[it].toInt() xor b[it].toInt()).toByte()}
    private fun i2osp(v:BigInteger,size:Int):ByteArray{
        val raw=v.toByteArray(); val unsigned=if(raw.size>1&&raw[0].toInt()==0)raw.copyOfRange(1,raw.size) else raw
        require(unsigned.size<=size); return ByteArray(size).also{System.arraycopy(unsigned,0,it,size-unsigned.size,unsigned.size)}
    }
}
