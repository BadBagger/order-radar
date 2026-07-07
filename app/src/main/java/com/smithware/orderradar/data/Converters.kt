package com.smithware.orderradar.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun productCategory(value: ProductCategory) = value.name
    @TypeConverter fun toProductCategory(value: String) = ProductCategory.valueOf(value)
    @TypeConverter fun countSource(value: CountSource) = value.name
    @TypeConverter fun toCountSource(value: String) = CountSource.valueOf(value)
    @TypeConverter fun movementType(value: MovementType) = value.name
    @TypeConverter fun toMovementType(value: String) = MovementType.valueOf(value)
    @TypeConverter fun orderDraftStatus(value: OrderDraftStatus) = value.name
    @TypeConverter fun toOrderDraftStatus(value: String) = OrderDraftStatus.valueOf(value)
    @TypeConverter fun deliveryStatus(value: DeliveryStatus) = value.name
    @TypeConverter fun toDeliveryStatus(value: String) = DeliveryStatus.valueOf(value)
    @TypeConverter fun varianceType(value: VarianceType) = value.name
    @TypeConverter fun toVarianceType(value: String) = VarianceType.valueOf(value)
    @TypeConverter fun displayStatus(value: DisplayStatus) = value.name
    @TypeConverter fun toDisplayStatus(value: String) = DisplayStatus.valueOf(value)
}
