package com.smithware.orderradar.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        Product::class, TruckSchedule::class, ProductTruckLink::class, InventoryCount::class,
        MovementEntry::class, OrderDraft::class, OrderLine::class, DeliveryRecord::class,
        DeliveryLine::class, VarianceLog::class, DisplayPlan::class, Recipe::class,
        RecipeIngredient::class, ProductionLog::class, PhotoAttachment::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class OrderRadarDatabase : RoomDatabase() {
    abstract fun orderRadarDao(): OrderRadarDao

    companion object {
        fun create(context: Context): OrderRadarDatabase = Room.databaseBuilder(
            context,
            OrderRadarDatabase::class.java,
            "order-radar.db"
        ).build()
    }
}
