package com.example.billbook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.billbook.data.*
import com.example.billbook.util.QrAndReceiptGenerator
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = BillBookDatabase.get(this)
        val prefs = getSharedPreferences("shop_profile", MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BillBookAppScreen(db.dao(), prefs)
            }
        }
    }
}

@Composable
fun BillBookAppScreen(dao: BillBookDao, prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val todayKey = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    val products by dao.observeProducts().collectAsState(initial = emptyList())
    val bills by dao.observeAllBills().collectAsState(initial = emptyList())
    val todaySales by dao.observeDailyTotalSales(todayKey).collectAsState(initial = 0.0)

    var shopName by remember { mutableStateOf(prefs.getString("name", "Sharma Kirana Store") ?: "Sharma Kirana Store") }
    var shopUpiId by remember { mutableStateOf(prefs.getString("upi", "sharmastore@okicici") ?: "sharmastore@okicici") }
    var shopPhone by remember { mutableStateOf(prefs.getString("phone", "+91 9811155220") ?: "+91 9811155220") }

    // Seed sample products on first launch so app works immediately in flight mode
    LaunchedEffect(Unit) {
        if (!prefs.getBoolean("seeded", false)) {
            listOf(
                ProductEntity("p1", "Aashirvaad Atta 5kg", 245.0, "bag"),
                ProductEntity("p2", "Fortune Sunlite Oil 1L", 140.0, "pouch"),
                ProductEntity("p3", "India Gate Basmati Rice 1kg", 120.0, "pkt"),
                ProductEntity("p4", "Tata Tea Gold 500g", 260.0, "pkt"),
                ProductEntity("p5", "Amul Taaza Milk 1L", 68.0, "pkt")
            ).forEach { dao.upsertProduct(it) }
            prefs.edit().putBoolean("seeded", true).apply()
        }
    }

    var activeTab by remember { mutableStateOf(0) }
    var cart by remember { mutableStateOf(listOf<BillLineItem>()) }
    var discountPercent by remember { mutableStateOf("5") }
    var taxPercent by remember { mutableStateOf("5") }

    val subtotal = cart.sumOf { it.price * it.qty }
    val discAmt = subtotal * ((discountPercent.toDoubleOrNull() ?: 0.0) / 100.0)
    val taxable = (subtotal - discAmt).coerceAtLeast(0.0)
    val taxAmt = taxable * ((taxPercent.toDoubleOrNull() ?: 0.0) / 100.0)
    val finalAmount = taxable + taxAmt

    Scaffold(
        topBar = {
            Surface(tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(shopName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("UPI: $shopUpiId • Offline Mode", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(String.format("Today: ₹%.2f", todaySales), fontWeight = FontWeight.ExtraBold)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                listOf("New Bill", "Products", "History", "Shop Info").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = activeTab == index,
                        onClick = { activeTab = index },
                        icon = {},
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(12.dp).fillMaxSize()) {
            when (activeTab) {
                0 -> {
                    Text("Tap Product to Add to Bill:", fontWeight = FontWeight.Bold)
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(products) { prod ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${prod.name} (₹${prod.price})")
                                Button(onClick = {
                                    val idx = cart.indexOfFirst { it.productId == prod.id }
                                    cart = if (idx >= 0) {
                                        cart.toMutableList().also { it[idx] = it[idx].copy(qty = it[idx].qty + 1) }
                                    } else {
                                        cart + BillLineItem(prod.id, prod.name, prod.price, 1, prod.unit)
                                    }
                                }) { Text("+ Add") }
                            }
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(String.format("Subtotal: ₹%.2f | Disc: -₹%.2f | GST: +₹%.2f", subtotal, discAmt, taxAmt))
                    Text(String.format("Final Payable: ₹%.2f", finalAmount), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)

                    val upiUri = QrAndReceiptGenerator.buildUpiDeepLinkUri(shopUpiId, shopName, finalAmount, "INV-${bills.size + 1001}")
                    val qrBitmap = remember(upiUri) { QrAndReceiptGenerator.generateUpiQrBitmap(upiUri, 240) }
                    Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "UPI QR", modifier = Modifier.size(140.dp).align(Alignment.CenterHorizontally))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (cart.isEmpty()) return@Button
                                val billNo = "INV-${bills.size + 1001}"
                                val entity = BillEntity(
                                    billNo = billNo,
                                    dateKey = todayKey,
                                    timestampMillis = System.currentTimeMillis(),
                                    customerName = "Walk-in Customer",
                                    customerPhone = "",
                                    itemsJson = Gson().toJson(cart),
                                    subtotal = subtotal,
                                    discountAmount = discAmt,
                                    taxPercent = taxPercent.toDoubleOrNull() ?: 0.0,
                                    taxAmount = taxAmt,
                                    finalAmount = finalAmount
                                )
                                scope.launch {
                                    dao.insertBill(entity)
                                    val bmp = QrAndReceiptGenerator.renderReceiptBitmap(shopName, shopUpiId, shopPhone, entity, cart)
                                    QrAndReceiptGenerator.shareBillAsImage(context, bmp, billNo)
                                    cart = emptyList()
                                }
                            }
                        ) { Text("Save & Share WhatsApp PNG") }
                    }
                }
                1 -> Text("Products Catalog (${products.size} items saved in Room SQLite)")
                2 -> Text("Saved Bills History (${bills.size} bills) • Today Sales: ₹$todaySales")
                3 -> Text("Shop Profile: $shopName ($shopUpiId)")
            }
        }
    }
}