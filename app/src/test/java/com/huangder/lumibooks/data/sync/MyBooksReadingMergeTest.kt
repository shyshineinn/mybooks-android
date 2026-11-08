package com.huangder.lumibooks.data.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MyBooksReadingMergeTest {
    private fun note(id: String, text: String, at: Long = 1) = JSONObject().put("syncId", id).put("note", text).put("updatedAt", at)
    private fun data(vararg notes: JSONObject) = emptyMyBooksReading().put("notes", JSONArray(notes.toList()))
    private fun tombstone(id: String) = JSONObject().put("namespace", "note").put("itemId", id).put("deletedAt", 20).put("deviceId", "a")
    private fun record(device: String, duration: Long) = JSONObject().put("date", "2026-10-28").put("sourceDeviceId", device)
        .put("duration", duration).put("startTime", 1).put("endTime", duration + 1).put("updatedAt", duration + 1)
    private fun position(format: String, fraction: Double, hash: String = "") = JSONObject().put("format", format).put("fileSha256", hash)
        .put("lastReadTime", 10).put("readingProgress", fraction).put("locatorJson", JSONObject.NULL).put("sourceDeviceId", "a")

    @Test fun independentAnnotationsAreMergedAndReplayDoesNotDuplicateThem() {
        val base = data(note("shared", "old"))
        val local = data(note("shared", "old"), note("a", "device a"))
        val remote = data(note("shared", "old"), note("b", "device b"))
        val merged = mergeMyBooksReading(base, local, remote)
        assertTrue(merged.conflicts.isEmpty())
        assertEquals(setOf("shared", "a", "b"), readingItems(merged.data, "notes").map { it.getString("syncId") }.toSet())
        assertEquals(readingCanonical(merged.data), readingCanonical(mergeMyBooksReading(merged.data, merged.data, remote).data))
    }
    @Test fun concurrentEditsRequireAChoiceEvenWhenOneDeviceClockIsFarAhead() {
        val base = data(note("n", "old"))
        val local = data(note("n", "local", 2))
        val remote = data(note("n", "remote", 999999999))
        assertEquals(listOf("notes:n"), mergeMyBooksReading(base, local, remote).conflicts)
        assertEquals("local", readingItems(mergeMyBooksReading(base, local, remote, true).data, "notes").single().getString("note"))
        assertEquals("remote", readingItems(mergeMyBooksReading(base, local, remote, false).data, "notes").single().getString("note"))
    }
    @Test fun explicitDeletionPropagatesButDeletionVersusEditRequiresAChoice() {
        val base = data(note("n", "old"))
        val deleted = emptyMyBooksReading().put("tombstones", JSONArray().put(tombstone("n")))
        val result = mergeMyBooksReading(base, deleted, base)
        assertTrue(result.conflicts.isEmpty()); assertTrue(readingItems(result.data, "notes").isEmpty())
        assertEquals(1, readingItems(result.data, "tombstones").size)
        assertEquals(listOf("notes:n"), mergeMyBooksReading(base, deleted, data(note("n", "edited"))).conflicts)
        assertEquals(1, readingItems(mergeMyBooksReading(base, deleted, deleted).data, "tombstones").size)
    }
    @Test fun missingItemsAreNotImplicitDeletionCommands() {
        val base = data(note("n", "retained"))
        assertEquals(1, readingItems(mergeMyBooksReading(base, emptyMyBooksReading(), base).data, "notes").size)
    }
    @Test fun dailyReadingAndListeningUseIndependentMaximumsPerDevice() {
        val local = emptyMyBooksReading().put("reading_records", JSONArray().put(record("a", 50).put("listeningDuration", 90)))
        val remote = emptyMyBooksReading().put("reading_records", JSONArray().put(record("a", 70)).put(record("b", 30).put("listeningDuration", 40)))
        val result = mergeMyBooksReading(emptyMyBooksReading(), local, remote)
        assertEquals(100L, readingItems(result.data, "reading_records").sumOf { it.getLong("duration") })
        assertEquals(130L, readingItems(result.data, "reading_records").sumOf { it.optLong("listeningDuration") })
        val replay = mergeMyBooksReading(result.data, local, result.data)
        assertEquals(readingCanonical(result.data), readingCanonical(replay.data))
    }
    @Test fun progressForDifferentFormatsOrBodiesNeverOverwritesAnotherPosition() {
        val local = emptyMyBooksReading().put("positions", JSONArray().put(position("EPUB", .4, "a".repeat(64))))
        val remote = emptyMyBooksReading().put("positions", JSONArray().put(position("PDF", .9)).put(position("EPUB", .2, "b".repeat(64))))
        val result = mergeMyBooksReading(emptyMyBooksReading(), local, remote)
        assertTrue(result.conflicts.isEmpty()); assertEquals(3, readingItems(result.data, "positions").size)
    }
    @Test fun conflictingPositionsOnTheSameBodyRequireAChoice() {
        val base = emptyMyBooksReading().put("positions", JSONArray().put(position("EPUB", .1)))
        val local = emptyMyBooksReading().put("positions", JSONArray().put(position("EPUB", .2)))
        val remote = emptyMyBooksReading().put("positions", JSONArray().put(position("EPUB", .3)))
        assertEquals(1, mergeMyBooksReading(base, local, remote).conflicts.size)
    }
    @Test fun roomFloatRoundTripsDoNotCreateFalseChanges() {
        assertEquals(readingCanonical(position("EPUB", .4)), readingCanonical(position("EPUB", .4f.toDouble())))
    }
}
