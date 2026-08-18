package com.dd3boh.outertune.extensions

import androidx.sqlite.db.SimpleSQLiteQuery
import java.net.InetSocketAddress
import java.net.InetSocketAddress.createUnresolved

inline fun <reified T : Enum<T>> String?.toEnum(defaultValue: T): T =
    if (this == null) defaultValue
    else try {
        enumValueOf(this)
    } catch (e: IllegalArgumentException) {
        defaultValue
    }

fun String.toSQLiteQuery(): SimpleSQLiteQuery = SimpleSQLiteQuery(this)

/**
 * @throws IllegalArgumentException if this string isn't exactly "host:port" - callers that show
 * this to the person (rather than letting it crash) should catch that specifically, not just
 * Exception, since a bare Exception catch also swallows the very host/port bugs this is meant to
 * surface during development.
 */
fun String.toInetSocketAddress(): InetSocketAddress {
    val parts = split(":")
    require(parts.size == 2) { "Expected \"host:port\", got \"$this\"" }
    val port = parts[1].toIntOrNull()
    require(port != null) { "Port isn't a number: \"${parts[1]}\"" }
    return createUnresolved(parts[0], port)
}