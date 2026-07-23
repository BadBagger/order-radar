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
        RecipeIngredient::class, ProductionLog::class, PhotoAttachment::class, VisionCorrection::class,
        DeliScanSessionRecord::class, DeliScanSourceRecord::class, DeliInventorySnapshotRecord::class,
        DeliInventorySnapshotItemRecord::class, DeliPromoItemRecord::class,
        DeliSupplierOrderLineRecord::class, DeliVerifyLabelRecord::class, DeliStickyNoteRecord::class
    ],
    version = 7,
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

        // Seeds the real Blount Fine Foods soup lineup the manager read off the case labels --
        // these boxes are only told apart by a printed "Item #" code (no color/shape system),
        // so this is the first seed to actually populate the itemNumber column instead of
        // stuffing the code into visualIdentifiers.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                val defaultNotes = "Added from your description of the Blount Fine Foods soup case-code system -- adjust safety stock, reorder point, and storage location to match reality."
                val seeds = listOf(
                    "Chili" to "80142",
                    "Chicken Noodle" to "80041",
                    "Chicken Wild Rice" to "80017",
                    "Enchilada Soup" to "75164",
                    "Chicken Sausage and Kale" to "75034",
                    "Spring Vegetable" to "80189",
                    "Lobster Bisque" to "80130",
                    "Tomato Bisque" to "80194",
                    "Potato Cheddar Soup" to "80014",
                    "Broccoli Cheddar" to "80091",
                    "Clam Chowder" to "7500",
                    "Mushroom Bisque" to "80152"
                )
                seeds.forEach { (name, itemNumber) ->
                    db.execSQL(
                        """
                        INSERT INTO `Product` (name, category, itemNumber, upc, vendor, department, storageLocation, defaultUnit, caseSize, boxWeight, safetyStock, reorderPoint, active, productPhotoUri, notes, visualIdentifiers, createdAt, updatedAt)
                        SELECT ?, 'PREPARED_FOOD', ?, NULL, 'Blount Fine Foods', 'Deli', NULL, 'boxes', NULL, NULL, 1.0, 1.0, 1, NULL, ?, NULL, ?, ?
                        WHERE NOT EXISTS (SELECT 1 FROM `Product` WHERE name = ?)
                        """.trimIndent(),
                        arrayOf<Any>(name, itemNumber, defaultNotes, now, now, name)
                    )
                }
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `DeliScanSessionRecord` (
                        `sessionId` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        `progressState` TEXT NOT NULL,
                        `completedSources` INTEGER NOT NULL,
                        `totalSources` INTEGER NOT NULL,
                        `progressMessage` TEXT NOT NULL,
                        `progressUpdatedAtMillis` INTEGER NOT NULL,
                        `failedReason` TEXT,
                        `duplicateSourceIds` TEXT NOT NULL,
                        `totalSourceCount` INTEGER NOT NULL,
                        `duplicateSourceCount` INTEGER NOT NULL,
                        `processedSourceCount` INTEGER NOT NULL,
                        `inventoryItemCount` INTEGER NOT NULL,
                        `promoItemCount` INTEGER NOT NULL,
                        `orderLineCount` INTEGER NOT NULL,
                        `verifyItemCount` INTEGER NOT NULL,
                        `stickyNoteCount` INTEGER NOT NULL,
                        `locationTags` TEXT NOT NULL,
                        PRIMARY KEY(`sessionId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `DeliScanSourceRecord` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `sourceId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `textSourceType` TEXT NOT NULL,
                        `photoId` TEXT,
                        `uri` TEXT,
                        `capturedAtMillis` INTEGER,
                        `receivedAtMillis` INTEGER NOT NULL,
                        `location` TEXT,
                        `locationTags` TEXT NOT NULL,
                        `widthPx` INTEGER,
                        `heightPx` INTEGER,
                        `sizeBytes` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `DeliInventorySnapshotRecord` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `capturedAtMillis` INTEGER NOT NULL,
                        `weekStartEpochDay` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `sourceRefs` TEXT NOT NULL,
                        `notes` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `DeliInventorySnapshotItemRecord` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `snapshotId` INTEGER NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `sku` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `casesOnHand` REAL NOT NULL,
                        `caseWeightLbs` REAL,
                        `useByEpochDay` INTEGER,
                        `location` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `verified` INTEGER NOT NULL,
                        `brandVendor` TEXT,
                        `sourceRefs` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `DeliPromoItemRecord` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `sku` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `retailPrice` REAL,
                        `salePrice` REAL,
                        `dealType` TEXT NOT NULL,
                        `discountPct` REAL,
                        `adStartEpochDay` INTEGER NOT NULL,
                        `adEndEpochDay` INTEGER NOT NULL,
                        `placement` TEXT,
                        `expectedDemandMultiplier` REAL,
                        `sourceRefs` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `DeliSupplierOrderLineRecord` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `sku` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `packSize` TEXT,
                        `suggestedCases` REAL NOT NULL,
                        `forecastDemandCases` REAL NOT NULL,
                        `safetyStockCases` REAL NOT NULL,
                        `orderIndex` INTEGER NOT NULL,
                        `sourceRefs` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `DeliVerifyLabelRecord` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `itemName` TEXT NOT NULL,
                        `sku` TEXT,
                        `packSize` TEXT,
                        `caseWeightLbs` REAL,
                        `packDateEpochDay` INTEGER,
                        `useByEpochDay` INTEGER,
                        `brandVendor` TEXT,
                        `confidence` REAL NOT NULL,
                        `sourcePhotoId` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `DeliStickyNoteRecord` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `noteText` TEXT NOT NULL,
                        `sourceRefs` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DeliInventorySnapshotRecord_weekStartEpochDay` ON `DeliInventorySnapshotRecord` (`weekStartEpochDay`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DeliInventorySnapshotItemRecord_snapshotId` ON `DeliInventorySnapshotItemRecord` (`snapshotId`)")
            }
        }

        fun create(context: Context): OrderRadarDatabase = Room.databaseBuilder(
            context,
            OrderRadarDatabase::class.java,
            "order-radar.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
    }
}
