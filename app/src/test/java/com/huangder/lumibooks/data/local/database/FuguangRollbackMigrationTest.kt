package com.huangder.lumibooks.data.local.database

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.huangder.lumibooks.data.local.entity.BookEntity
import com.huangder.lumibooks.data.local.entity.ReadingRecordEntity
import com.huangder.lumibooks.domain.model.DailyTotal
import kotlinx.coroutines.flow.first
import com.huangder.lumibooks.data.local.entity.MyBooksSyncStateEntity
import com.huangder.lumibooks.data.local.entity.SyncStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class)
class FuguangRollbackMigrationTest {
    @Test fun v21LibraryUpgradesWithoutDeletingData() = verify(21)
    @Test fun v22LibraryUpgradesWithoutDeletingData() = verify(22)
    @Test fun v23LibraryUpgradesWithoutDeletingData() = verify(23)
    @Test fun v24LibraryUpgradesWithoutDeletingData() = verify(24)

    @Test fun v25LibraryUpgradesWithoutDeletingData() = verify(25)
    @Test fun v26LibraryUpgradesWithoutDeletingData() = verify(26)

    @Test fun readingAndListeningContributeToDailyTotalsAndListeningOnlyBooksAreActive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.readingRecordDao()
            val date = "2026-11-02"
            dao.insertRecord(ReadingRecordEntity(bookId = "read", date = date,
                duration = 30_000, startTime = 10, endTime = 30_010, sourceDeviceId = "device"))
            dao.insertRecord(ReadingRecordEntity(bookId = "listen", date = date,
                duration = 0, listeningDuration = 60_000, startTime = 10, endTime = 60_010, sourceDeviceId = "device"))
            assertEquals(DailyTotal(date, 90_000), dao.getDailyTotalsBetween(date, date).first().single())
            assertEquals(1, dao.getActiveDaysByBookId("listen").first())
            assertEquals(60_000L, dao.getTotalDurationByBookId("listen").first())
            assertEquals("listen", dao.getMostReadBooksBetween(date, date).first().first().bookId)
        } finally {
            db.close()
        }
    }

    private fun verify(previousVersion: Int) = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val name="fuguang-rollback-" + previousVersion + ".db"
        context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,AppDatabase::class.java,name).allowMainThreadQueries().build()
        try {
            db.bookDao().insertBook(BookEntity(id="mybooks-1",title="Book",author="Author",filePath="/local/book.epub",
                coverPath=null,format="EPUB",lastReadTime=123L,readingProgress=.75f,locatorJson="saved-position",createdAt=1L))
            db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity(bookId="mybooks-1",remoteId=1,
                overridesJson="{\"translators\":[\"甲\",\"乙\"]}"))
            db.syncStateDao().putState(SyncStateEntity("book_import_metadata:local-book","{\"fields\":{\"translators\":[\"丙\"]}}"))
            val sql=db.openHelper.writableDatabase
            sql.execSQL("INSERT INTO reading_records(bookId,date,duration,startTime,endTime,sourceDeviceId,updatedAt) VALUES('mybooks-1','2026-11-02',30000,100,30100,'device',30100)")
            if (previousVersion == 26) {
                sql.execSQL("UPDATE reading_records SET listeningDuration = 5000")
                sql.execSQL("UPDATE books SET isArchived = 1, archiveUpdatedAt = 42")
            }
            val before=rows(sql,"reading_records")
            if (previousVersion < 26) sql.execSQL("ALTER TABLE reading_records DROP COLUMN listeningDuration")
            sql.execSQL("ALTER TABLE books DROP COLUMN isHidden")
            sql.execSQL("ALTER TABLE books DROP COLUMN isManuallyMarkedRead")
            sql.execSQL("ALTER TABLE highlight_rules DROP COLUMN highlightMode")
            sql.execSQL("ALTER TABLE highlight_rules DROP COLUMN highlightColor")
            sql.execSQL("DROP INDEX index_tag_groups_type_normalizedName")
            sql.execSQL("DROP TABLE tag_groups")
            sql.execSQL("ALTER TABLE tags DROP COLUMN groupId")
            sql.execSQL("ALTER TABLE annotation_tags DROP COLUMN groupId")
            if (previousVersion < 25) {
                sql.execSQL("ALTER TABLE books DROP COLUMN isArchived")
                sql.execSQL("ALTER TABLE books DROP COLUMN archiveUpdatedAt")
            }
            if (previousVersion < 22) {
                sql.execSQL("ALTER TABLE mybooks_sync_state DROP COLUMN remoteDeleted")
                sql.execSQL("ALTER TABLE mybooks_sync_state DROP COLUMN conflictFieldsJson")
                sql.execSQL("ALTER TABLE mybooks_pending_delete DROP COLUMN expectedRevision")
                sql.execSQL("ALTER TABLE mybooks_pending_delete DROP COLUMN expectedUuid")
                sql.execSQL("ALTER TABLE mybooks_pending_delete DROP COLUMN expectedUpdatedAt")
                sql.execSQL("ALTER TABLE mybooks_pending_delete DROP COLUMN title")
            }
            if(previousVersion >= 23) createRetiredSessions(sql,previousVersion)
            val sessionsBefore=if(previousVersion >= 23) rows(sql,"reading_sessions") else emptyList()
            sql.execSQL("UPDATE room_master_table SET identity_hash='legacy-library-schema' WHERE id=42")
            sql.version=previousVersion
            db.close()
            db=Room.databaseBuilder(context,AppDatabase::class.java,name)
                .addMigrations(
                    DatabaseMigrations.MIGRATION_21_22, DatabaseMigrations.MIGRATION_22_23, DatabaseMigrations.MIGRATION_23_24,
                    DatabaseMigrations.MIGRATION_24_25, DatabaseMigrations.MIGRATION_25_26,
                    DatabaseMigrations.MIGRATION_26_27, DatabaseMigrations.MIGRATION_27_28
                )
                .allowMainThreadQueries().build()
            val restored=db.bookDao().getBookById("mybooks-1")!!
            assertEquals(.75f,restored.readingProgress,0f)
            assertEquals("saved-position",restored.locatorJson)
            assertEquals("/local/book.epub",restored.filePath)
            assertEquals("{\"translators\":[\"甲\",\"乙\"]}",db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
            assertEquals("{\"fields\":{\"translators\":[\"丙\"]}}",db.syncStateDao().getValue("book_import_metadata:local-book"))
            val after=db.openHelper.readableDatabase
            assertEquals(28,after.version)
            assertEquals(previousVersion == 26, restored.isArchived)
            assertEquals(if (previousVersion == 26) 42L else 0L, restored.archiveUpdatedAt)
            assertEquals(if (previousVersion == 26) 35_000L else 30_000L,
                db.readingRecordDao().getTotalDurationByBookId("mybooks-1").first())
            assertEquals(before,rows(after,"reading_records"))
            if(previousVersion >= 23) assertEquals(sessionsBefore,rows(after,"reading_sessions"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun rows(db:SupportSQLiteDatabase,table:String):List<List<String?>> = db.query(if (table == "reading_records") "SELECT id, bookId, date, duration, startTime, endTime, sourceDeviceId, updatedAt FROM reading_records ORDER BY id" else "SELECT * FROM $table ORDER BY id").use { cursor ->
        buildList { while(cursor.moveToNext()) add((0 until cursor.columnCount).map { if(cursor.isNull(it)) null else cursor.getString(it) }) }
    }

    private fun createRetiredSessions(db:SupportSQLiteDatabase,version:Int) {
        db.execSQL("""CREATE TABLE reading_sessions(
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,bookId TEXT NOT NULL,date TEXT NOT NULL,
            startTime INTEGER NOT NULL,endTime INTEGER NOT NULL,duration INTEGER NOT NULL,
            startChapterIndex INTEGER NOT NULL,endChapterIndex INTEGER NOT NULL,
            startChapterTitle TEXT,endChapterTitle TEXT,startProgress REAL NOT NULL,endProgress REAL NOT NULL,
            mode TEXT NOT NULL,sourceDeviceId TEXT NOT NULL,note TEXT,mood TEXT,updatedAt INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX index_reading_sessions_bookId_startTime ON reading_sessions(bookId,startTime)")
        db.execSQL("CREATE INDEX index_reading_sessions_startTime ON reading_sessions(startTime)")
        db.execSQL("CREATE INDEX index_reading_sessions_date ON reading_sessions(date)")
        db.execSQL("""INSERT INTO reading_sessions VALUES(1,'mybooks-1','2026-11-02',100,30100,30000,0,2,'Start','End',0.2,0.3,'paged','device','Kept note','calm',30100)""")
        if(version>=24) {
            db.execSQL("ALTER TABLE reading_sessions ADD COLUMN sessionKey TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE reading_sessions ADD COLUMN contributedDuration INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE reading_sessions ADD COLUMN contributionsJson TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("CREATE INDEX index_reading_sessions_sessionKey ON reading_sessions(sessionKey)")
            db.execSQL("UPDATE reading_sessions SET sessionKey='session-1',contributedDuration=30000,contributionsJson='{\"2026-11-02\":30000}'")
        }
    }
}
