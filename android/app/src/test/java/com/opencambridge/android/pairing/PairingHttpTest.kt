package com.opencambridge.android.pairing

import org.junit.Assert.*
import org.junit.Test

class PairingHttpTest {
    @Test fun acceptsOnlyBoundedLengthDelimitedSuccessfulResponses() {
        fun parse(value: String) = PairingHttp.readResponse(value.byteInputStream()) {}
        assertEquals("{}", parse("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}"))
        listOf(
            "HTTP/1.1 302 Found\r\nContent-Length: 2\r\n\r\n{}",
            "HTTP/1.1 200 OK\r\nContent-Length: 9000\r\n\r\n{}",
            "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\n{}",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
        ).forEach { assertThrows(Exception::class.java) { parse(it) } }
    }
}
