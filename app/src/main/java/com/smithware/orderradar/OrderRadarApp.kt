package com.smithware.orderradar

import android.app.Application
import com.smithware.orderradar.data.OrderRadarDatabase
import com.smithware.orderradar.data.OrderRadarRepository

class OrderRadarApp : Application() {
    val database by lazy { OrderRadarDatabase.create(this) }
    val repository by lazy { OrderRadarRepository(database.orderRadarDao()) }
}
