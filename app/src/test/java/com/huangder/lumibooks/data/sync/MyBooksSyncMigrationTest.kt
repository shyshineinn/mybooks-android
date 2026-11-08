package com.huangder.lumibooks.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.database.DatabaseMigrations
import com.huangder.lumibooks.data.local.entity.BookEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MyBooksSyncMigrationTest {
    @Test fun migratesAndKeepsEdits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "sync22.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            db.bookDao().insertBook(BookEntity(id="mybooks-1", title="local", author="author", filePath="/local/book.pdf",
                coverPath=null, format="PDF", lastReadTime=123L, readingProgress=.75f, locatorJson="saved-position", createdAt=1L))
            val sql = db.openHelper.writableDatabase
            sql.execSQL("DROP TABLE mybooks_sync_state")
            sql.execSQL("DROP TABLE mybooks_pending_delete")
            sql.execSQL("""CREATE TABLE mybooks_sync_state(bookId TEXT NOT NULL PRIMARY KEY, remoteId INTEGER NOT NULL,
                serverUpdatedAt TEXT NOT NULL DEFAULT '', baseJson TEXT NOT NULL DEFAULT '', overridesJson TEXT NOT NULL DEFAULT '{}',
                editedAtJson TEXT NOT NULL DEFAULT '{}', baseTagsJson TEXT NOT NULL DEFAULT '[]', coverDirty INTEGER NOT NULL DEFAULT 0,
                coverEditedAt INTEGER NOT NULL DEFAULT 0, lastSyncAt INTEGER NOT NULL DEFAULT 0, lastError TEXT)""")
            sql.execSQL("""CREATE TABLE mybooks_pending_delete(remoteId INTEGER NOT NULL PRIMARY KEY, bookId TEXT NOT NULL,
                deletedAt INTEGER NOT NULL, lastAttemptAt INTEGER NOT NULL DEFAULT 0, lastError TEXT)""")
            sql.execSQL("""INSERT INTO mybooks_sync_state(bookId,remoteId,overridesJson) VALUES('mybooks-1',1,'{"title":"pending"}')""")
            sql.execSQL("INSERT INTO mybooks_pending_delete(remoteId,bookId,deletedAt) VALUES(2,'mybooks-2',123)")
            sql.execSQL("ALTER TABLE books DROP COLUMN isManuallyMarkedRead")
            sql.execSQL("ALTER TABLE highlight_rules DROP COLUMN highlightMode")
            sql.execSQL("ALTER TABLE highlight_rules DROP COLUMN highlightColor")
            sql.execSQL("ALTER TABLE reading_records DROP COLUMN listeningDuration")
            sql.version = 21
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(
                    DatabaseMigrations.MIGRATION_21_22, DatabaseMigrations.MIGRATION_22_23,
                    DatabaseMigrations.MIGRATION_23_24, DatabaseMigrations.MIGRATION_24_25,
                    DatabaseMigrations.MIGRATION_25_26, DatabaseMigrations.MIGRATION_26_27,
                    DatabaseMigrations.MIGRATION_27_28
                ).allowMainThreadQueries().build()
            assertEquals("{\"title\":\"pending\"}", db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
            assertFalse(db.myBooksSyncStateDao().get("mybooks-1")!!.remoteDeleted)
            assertNull(db.myBooksSyncStateDao().getPendingDelete(2)!!.expectedRevision)
            db.bookDao().updateBookTitleAuthor("mybooks-1", "new-local-title", "author", 20L)
            db.bookDao().applySyncedMetadata("mybooks-1", "server-title", "server-author", null,
                "EPUB", 100, 30, "local", "author", null)
            val book = db.bookDao().getBookById("mybooks-1")!!
            assertEquals("new-local-title", book.title)
            assertEquals("server-author", book.author)
            assertEquals(.75f, book.readingProgress, 0f)
            assertEquals("saved-position", book.locatorJson)
            assertEquals("/local/book.pdf", book.filePath)
            assertEquals("PDF", book.format)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
