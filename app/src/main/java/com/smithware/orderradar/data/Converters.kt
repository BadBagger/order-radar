package com.smithware.orderradar.data

import androidx.room.TypeConverter
import com.smithware.orderradar.domain.DeliCategory
import com.smithware.orderradar.domain.DeliOcrTextSourceType
import com.smithware.orderradar.domain.DeliScanSessionProgressState
import com.smithware.orderradar.domain.DeliTextSourceKind
import com.smithware.orderradar.domain.InventoryLocation
import com.smithware.orderradar.domain.PromoDealType

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
    @TypeConverter fun deliCategory(value: DeliCategory) = value.name
    @TypeConverter fun toDeliCategory(value: String) = DeliCategory.valueOf(value)
    @TypeConverter fun inventoryLocation(value: InventoryLocation) = value.name
    @TypeConverter fun toInventoryLocation(value: String) = InventoryLocation.valueOf(value)
    @TypeConverter fun promoDealType(value: PromoDealType) = value.name
    @TypeConverter fun toPromoDealType(value: String) = PromoDealType.valueOf(value)
    @TypeConverter fun deliTextSourceKind(value: DeliTextSourceKind) = value.name
    @TypeConverter fun toDeliTextSourceKind(value: String) = DeliTextSourceKind.valueOf(value)
    @TypeConverter fun deliOcrTextSourceType(value: DeliOcrTextSourceType) = value.name
    @TypeConverter fun toDeliOcrTextSourceType(value: String) = DeliOcrTextSourceType.valueOf(value)
    @TypeConverter fun deliScanSessionProgressState(value: DeliScanSessionProgressState) = value.name
    @TypeConverter fun toDeliScanSessionProgressState(value: String) = DeliScanSessionProgressState.valueOf(value)
}
