package com.smithware.orderradar.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Product::class, TruckSchedule::class, ProductTruckLink::class, InventoryCount::class,
        MovementEntry::class, OrderDraft::class, OrderLine::class, DeliveryRecord::class,
        DeliveryLine::class, VarianceLog::class, DisplayPlan::class, Recipe::class,
        RecipeIngredient::class, ProductionLog::class, PhotoAttachment::class, VisionCorrection::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class OrderRadarDatabase : RoomDatabase() {
    abstract fun orderRadarDao(): OrderRadarDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `VisionCorrection` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `productId` INTEGER NOT NULL,
                        `productName` TEXT NOT NULL,
                        `aiEstimatedQuantity` REAL NOT NULL,
                        `confirmedQuantity` REAL NOT NULL,
                        `aiConfidencePercent` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun create(context: Context): OrderRadarDatabase = Room.databaseBuilder(
            context,
            OrderRadarDatabase::class.java,
            "order-radar.db"
        ).addMigrations(MIGRATION_1_2).build()
    }
}
