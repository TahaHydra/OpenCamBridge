package com.opencambridge.android.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ocb2ConformanceTest {
    @Test
    fun sharedCorpus() {
        val corpus = javaClass.classLoader!!.getResource("ocb2-corpus.json")!!.readText()
        val cases = Json.parseToJsonElement(corpus).jsonObject.getValue("cases").jsonArray
        for (caseElement in cases) {
            val case = caseElement.jsonObject
            val parser = Ocb2.Parser()
            val records = mutableListOf<Ocb2.Record>()
            var error: Ocb2.ParseException? = null
            case.getValue("actions").jsonArray.forEach actionLoop@{ actionElement ->
                val action = actionElement.jsonObject
                if (action["reset"]?.jsonPrimitive?.boolean == true) {
                    parser.reset()
                    return@actionLoop
                }
                val bytes = decodeHex(action.getValue("hex").jsonPrimitive.content)
                val fragmentSizes = action["fragmentSizes"]?.jsonArray?.map { it.jsonPrimitive.int }
                    ?: listOf(bytes.size)
                var offset = 0
                fragmentSizes.forEach { requested ->
                    val count = requested.coerceAtMost(bytes.size - offset)
                    parser.push(bytes, offset, count)
                    offset += count
                    try {
                        drain(parser, records)
                    } catch (caught: Ocb2.ParseException) {
                        error = caught
                    }
                }
                if (offset < bytes.size) {
                    parser.push(bytes, offset, bytes.size - offset)
                    try {
                        drain(parser, records)
                    } catch (caught: Ocb2.ParseException) {
                        error = caught
                    }
                }
            }

            val id = case.getValue("id").jsonPrimitive.content
            val expectedError = case["expectedError"]?.jsonPrimitive?.content
            if (expectedError != null) {
                assertEquals(id, expectedError, error?.code)
                continue
            }
            assertNull("$id unexpected error", error)
            val expected = case.getValue("expectedRecords").jsonArray
            assertEquals("$id record count", expected.size, records.size)
            expected.zip(records).forEach { (expectedElement, actual) ->
                val item = expectedElement.jsonObject
                assertEquals(id, item.getValue("type").jsonPrimitive.int.toShort(), actual.type)
                assertEquals(id, item.getValue("flags").jsonPrimitive.int, actual.flags)
                assertEquals(id, item.getValue("sequence").jsonPrimitive.long, actual.sequence)
                // Absent on the cases written before the field existed, and those
                // headers carry the old reserved zero, so defaulting to 0 is the
                // backward-compatibility assertion rather than a way to skip it.
                assertEquals(
                    "$id send delta",
                    item["sendDeltaUs"]?.jsonPrimitive?.int ?: 0,
                    actual.sendDeltaUs
                )
                assertArrayEquals(id, decodeHex(item.getValue("payloadHex").jsonPrimitive.content), actual.payload)
            }
            assertEquals(
                "$id keyframe policy",
                case.getValue("acceptedVideoSequences").jsonArray.map { it.jsonPrimitive.long },
                acceptedVideoSequences(records)
            )
        }
    }

    private fun drain(parser: Ocb2.Parser, records: MutableList<Ocb2.Record>) {
        while (true) records += parser.next() ?: return
    }

    private fun acceptedVideoSequences(records: List<Ocb2.Record>): List<Long> {
        var waitingForKeyframe = true
        val accepted = mutableListOf<Long>()
        records.forEach { record ->
            if (record.type == Ocb2.TYPE_STREAM_INFO || record.flags and Ocb2.FLAG_DISCONTINUITY != 0) {
                waitingForKeyframe = true
            }
            if (record.type == Ocb2.TYPE_VIDEO_ACCESS_UNIT) {
                if (record.flags and Ocb2.FLAG_KEYFRAME != 0) waitingForKeyframe = false
                if (!waitingForKeyframe) accepted += record.sequence
            }
        }
        return accepted
    }

    private fun decodeHex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
