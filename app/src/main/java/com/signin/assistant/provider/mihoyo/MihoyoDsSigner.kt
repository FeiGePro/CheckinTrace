package com.signin.assistant.provider.mihoyo

import java.security.MessageDigest

object MihoyoDsSigner {
    private const val SALT_ANDROID = "BIPaooxbWZW02fGHZL1If26mYCljPgst"
    private const val SALT_DATA = "t0qEgfub6cvueAPgR5m9aQWWVciEer7v"
    private const val SALT_X4 = "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs"
    private const val SALT_K2 = "OvOIsZRXrUbXoUlpQuhEx4tgAwNVUMmp"

    fun x4(
        query: String,
        body: String = "",
        epochSeconds: Long,
        randomNumber: Int,
    ): String {
        val input = "salt=$SALT_X4&t=$epochSeconds&r=$randomNumber&b=$body&q=$query"
        return "$epochSeconds,$randomNumber,${input.md5()}"
    }

    fun k2(
        exactJsonBody: String,
        epochSeconds: Long,
        randomLetters: String,
    ): String {
        require(randomLetters.length == 6) { "K2 random value must contain six letters" }
        val input = "salt=$SALT_K2&t=$epochSeconds&r=$randomLetters&b=$exactJsonBody&q="
        return "$epochSeconds,$randomLetters,${input.md5()}"
    }

    fun androidSimple(epochSeconds: Long, randomLetters: String): String {
        require(randomLetters.length == 6)
        val input = "salt=$SALT_ANDROID&t=$epochSeconds&r=$randomLetters"
        return "$epochSeconds,$randomLetters,${input.md5()}"
    }

    fun androidData(exactJsonBody: String, epochSeconds: Long, randomNumber: Int): String {
        val input = "salt=$SALT_DATA&t=$epochSeconds&r=$randomNumber&b=$exactJsonBody&q="
        return "$epochSeconds,$randomNumber,${input.md5()}"
    }

    private fun String.md5(): String = MessageDigest.getInstance("MD5")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
