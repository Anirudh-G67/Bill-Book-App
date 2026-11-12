package com.example.billbook.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val price: Double,
    val unit: String = "pkt"
)

data class BillLineItem(
    val productId: String,
    val name: String,
    val price: Double,
    val qty: Int,
    val unit: String
)

@Entity(tableName = "bills")
data class BillEntity(
    @PrimaryKey val billNo: String,
    val dateKey: String,          // YYYY-MM-DD for Daily Sales grouping
    val timestampMillis: Long,
    val customerName: String,
    val customerPhone: String,
    val itemsJson: String,        // Serialized List<BillLineItem>
    val subtotal: Double,
    val discountAmount: Double,
    val taxPercent: Double,
    val taxAmount: Double,
    val finalAmount: Double
)

@Dao
interface BillBookDao {
    @Query("SELECT * FROM products ORDER BY name ASC")
    fun observeProducts(): Flow<List<ProductEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProduct(product: ProductEntity)

    @Delete
    suspend fun deleteProduct(product: ProductEntity)

    @Query("SELECT * FROM bills ORDER BY timestampMillis DESC")
    fun observeAllBills(): Flow<List<BillEntity>>

    @Query("SELECT COALESCE(SUM(finalAmount), 0.0) FROM bills WHERE dateKey = :todayDateKey")
    fun observeDailyTotalSales(todayDateKey: String): Flow<Double>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBill(bill: BillEntity)
}

@Database(entities = [ProductEntity::class, BillEntity::class], version = 1, exportSchema = false)
abstract class BillBookDatabase : RoomDatabase() {
    abstract fun dao(): BillBookDao

    companion object {
        @Volatile private var INSTANCE: BillBookDatabase? = null
        fun get(context: Context): BillBookDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, BillBookDatabase::class.java, "billbook_offline.db")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}