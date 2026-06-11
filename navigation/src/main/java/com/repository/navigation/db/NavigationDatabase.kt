package com.repository.navigation.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [JourneySessionEntity::class, RecentDestinationEntity::class],
    version = 3
)
@TypeConverters(Converters::class)
abstract class NavigationDatabase : RoomDatabase() {

    abstract fun journeySessionDao(): JourneySessionDao
    abstract fun recentDestinationDao(): RecentDestinationDao

    companion object {
        @Volatile
        private var INSTANCE: NavigationDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE journey_sessions ADD COLUMN currentStepIndex INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `recent_destinations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `subtitle` TEXT NOT NULL,
                        `lat` REAL NOT NULL,
                        `lng` REAL NOT NULL,
                        `roundedLat` REAL NOT NULL,
                        `roundedLng` REAL NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL
                    )"""
                )
                db.execSQL(
                    """CREATE UNIQUE INDEX IF NOT EXISTS
                        `index_recent_destinations_roundedLat_roundedLng`
                        ON `recent_destinations` (`roundedLat`, `roundedLng`)"""
                )
            }
        }

        fun getInstance(context: Context): NavigationDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NavigationDatabase::class.java,
                    "navigation_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
