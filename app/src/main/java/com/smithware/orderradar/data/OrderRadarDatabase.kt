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
    version = 5,
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `Product` ADD COLUMN `visualIdentifiers` TEXT")
            }
        }

        // Seeds the real rotisserie chicken/wings/tenders lineup the manager described in chat
        // (color-coded lids, box shapes, wing-number endings) so AI Shelf Count has real
        // products to match against instead of only the generic sample data. Skips a name if a
        // product with that exact name already exists, in case it was added by hand first.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                val defaultNotes = "Added from your description of the store's color/box-shape system -- adjust safety stock, reorder point, and storage location to match reality."
                val seeds = listOf(
                    "Original Rotisserie Chicken" to "Square color swatch on front-left of box: orange = Original.",
                    "Lemon Pepper Rotisserie Chicken" to "Square color swatch on front-left of box: yellow = Lemon Pepper.",
                    "Mojo Rotisserie Chicken" to "Square color swatch on front-left of box: brown = Mojo.",
                    "8-Piece Chicken" to "No color swatch on front-left square -- this is the 8-piece size, not a flavor variant.",
                    "Hot & Spicy Wings" to "Box has a white band around the top holding the lid on, no front handle (unlike Tenders). Box number ends in 1.",
                    "Plain Wings" to "Box has a white band around the top holding the lid on, no front handle (unlike Tenders). Box number ends in 0.",
                    "Mardi Gras Wings" to "Almost fully square box, more square than other wing boxes. Has a label but it's low-contrast and hard to read -- identify by the near-square shape, not the label text.",
                    "Tenders" to "Narrow, small box with handles on the front (unlike Wings boxes, which have no front handle)."
                )
                seeds.forEach { (name, visualId) ->
                    db.execSQL(
                        """
                        INSERT INTO `Product` (name, category, itemNumber, upc, vendor, department, storageLocation, defaultUnit, caseSize, boxWeight, safetyStock, reorderPoint, active, productPhotoUri, notes, visualIdentifiers, createdAt, updatedAt)
                        SELECT ?, 'PREPARED_FOOD', NULL, NULL, NULL, 'Deli', NULL, 'boxes', NULL, NULL, 1.0, 1.0, 1, NULL, ?, ?, ?, ?
                        WHERE NOT EXISTS (SELECT 1 FROM `Product` WHERE name = ?)
                        """.trimIndent(),
                        arrayOf<Any>(name, defaultNotes, visualId, now, now, name)
                    )
                }
            }
        }

        // Removes the two leftover generic sample products the manager confirmed aren't real
        // ("Chicken Breast Box", "Ham Case") now that the real lineup exists (MIGRATION_3_4).
        // Only deletes by exact name, so a product the manager renamed to reuse is untouched.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM `Product` WHERE name IN ('Chicken Breast Box', 'Ham Case')")
            }
        }

        fun create(context: Context): OrderRadarDatabase = Room.databaseBuilder(
            context,
            OrderRadarDatabase::class.java,
            "order-radar.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
