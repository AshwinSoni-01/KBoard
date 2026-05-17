// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

class KlipyHistoryDao private constructor(context: Context) {
    private val dbHelper = Database.getInstance(context)

    fun addHistory(id: String, url: String, type: String, width: Int = 0, height: Int = 0) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // Remove existing entry for the same ID and type to move it to the top
            db.delete(TABLE_NAME, "$COLUMN_ID = ? AND $COLUMN_TYPE = ?", arrayOf(id, type))

            val values = ContentValues().apply {
                put(COLUMN_ID, id)
                put(COLUMN_URL, url)
                put(COLUMN_TYPE, type)
                put(COLUMN_TIMESTAMP, System.currentTimeMillis())
                put(COLUMN_WIDTH, width)
                put(COLUMN_HEIGHT, height)
            }
            db.insert(TABLE_NAME, null, values)

            // Keep only the last MAX_HISTORY entries
            db.execSQL("""
                DELETE FROM $TABLE_NAME
                WHERE $COLUMN_TYPE = '$type' AND $COLUMN_ID NOT IN (
                    SELECT $COLUMN_ID FROM $TABLE_NAME
                    WHERE $COLUMN_TYPE = '$type'
                    ORDER BY $COLUMN_TIMESTAMP DESC
                    LIMIT $MAX_HISTORY
                )
            """.trimIndent())

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getHistory(type: String): List<KlipyItem> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<KlipyItem>()
        db.rawQuery(
            "SELECT $COLUMN_ID, $COLUMN_URL, $COLUMN_WIDTH, $COLUMN_HEIGHT FROM $TABLE_NAME WHERE $COLUMN_TYPE = ? ORDER BY $COLUMN_TIMESTAMP DESC",
            arrayOf(type)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(KlipyItem(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getInt(2),
                    cursor.getInt(3)
                ))
            }
        }
        return list
    }

    companion object {
        const val TABLE_NAME = "KLIPY_HISTORY"
        const val COLUMN_ID = "ID"
        const val COLUMN_URL = "URL"
        const val COLUMN_TYPE = "TYPE" // "GIF" or "STICKER"
        const val COLUMN_TIMESTAMP = "TIMESTAMP"
        const val COLUMN_WIDTH = "WIDTH"
        const val COLUMN_HEIGHT = "HEIGHT"

        const val TYPE_GIF = "GIF"
        const val TYPE_STICKER = "STICKER"
        const val MAX_HISTORY = 50

        const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COLUMN_ID TEXT,
                $COLUMN_URL TEXT,
                $COLUMN_TYPE TEXT,
                $COLUMN_TIMESTAMP INTEGER,
                $COLUMN_WIDTH INTEGER,
                $COLUMN_HEIGHT INTEGER,
                PRIMARY KEY ($COLUMN_ID, $COLUMN_TYPE)
            )
        """

        private var instance: KlipyHistoryDao? = null
        fun getInstance(context: Context): KlipyHistoryDao {
            if (instance == null) instance = KlipyHistoryDao(context)
            return instance!!
        }
    }

    data class KlipyItem(val id: String, val url: String, val width: Int, val height: Int)
}
