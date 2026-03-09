package com.splayer.video.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.splayer.video.data.local.dao.PlaybackPositionDao
import com.splayer.video.data.local.entity.PlaybackPosition

@Database(
    entities = [PlaybackPosition::class],
    version = 1,
    exportSchema = false
)
abstract class SPlayerDatabase : RoomDatabase() {

    abstract fun playbackPositionDao(): PlaybackPositionDao

    companion object {
        @Volatile
        private var INSTANCE: SPlayerDatabase? = null

        fun getInstance(context: Context): SPlayerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SPlayerDatabase::class.java,
                    "splayer_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
