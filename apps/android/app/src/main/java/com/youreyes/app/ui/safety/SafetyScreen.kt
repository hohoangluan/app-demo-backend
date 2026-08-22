package com.youreyes.app.ui.safety

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youreyes.app.fcm.FcmPushReceiver
import com.youreyes.app.ui.components.GradientButton
import com.youreyes.app.ui.components.RowCard
import com.youreyes.app.ui.components.ScreenShell
import com.youreyes.app.ui.components.SectionLabel
import com.youreyes.app.ui.components.SosButton
import com.youreyes.app.ui.overview.OverviewViewModel
import com.youreyes.app.ui.theme.accentTextColor
import com.youreyes.app.ui.theme.borderColor
import com.youreyes.app.ui.theme.dangerColor
import com.youreyes.app.ui.theme.inkColor
import com.youreyes.app.ui.theme.mutedColor
import com.youreyes.app.ui.theme.shadowColor
import com.youreyes.app.ui.theme.surfaceColor

@Composable
fun SafetyScreen(
    modifier: Modifier = Modifier,
    onNavigateToProfile: () -> Unit = {},
) {
    val context = LocalContext.current
    val overviewViewModel: OverviewViewModel = viewModel()
    val overviewState by overviewViewModel.uiState.collectAsState()

    val emergencyPhone = overviewState.emergencyContact.ifBlank {
        val prefs = context.getSharedPreferences(FcmPushReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(FcmPushReceiver.KEY_EMERGENCY_CONTACT, "") ?: ""
    }

    var editedPhone by remember(emergencyPhone) { mutableStateOf(emergencyPhone) }

    fun savePhone(newPhone: String) {
        val trimmed = newPhone.trim()
        overviewViewModel.onEmergencyContactChange(trimmed)
        val prefs = context.getSharedPreferences(FcmPushReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(FcmPushReceiver.KEY_EMERGENCY_CONTACT, trimmed).apply()
        Toast.makeText(context, "Đã lưu số điện thoại khẩn cấp: $trimmed", Toast.LENGTH_SHORT).show()
    }

    fun triggerEmergencyCall() {
        val targetNumber = emergencyPhone.trim()
        if (targetNumber.isBlank()) {
            Toast.makeText(context, "Vui lòng nhập số điện thoại người thân khẩn cấp bên dưới!", Toast.LENGTH_LONG).show()
            return
        }

        val cleanDigits = targetNumber.replace(" ", "").replace("-", "")
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        try {
            if (hasCallPermission) {
                // Perform REAL DIRECT CALL
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanDigits")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(callIntent)
                Toast.makeText(context, "Đang thực hiện cuộc gọi khẩn cấp tới $targetNumber...", Toast.LENGTH_SHORT).show()
            } else {
                // Fallback to DIAL
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanDigits")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(dialIntent)
                Toast.makeText(context, "Đã mở màn hình quay số tới $targetNumber", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Lỗi thực hiện cuộc gọi: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    ScreenShell(modifier = modifier) {
        // SOS Button Section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(22.dp),
                    spotColor = shadowColor,
                ),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Báo Động Khẩn Cấp SOS",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        color = inkColor,
                    ),
                )
                Text(
                    text = "Hệ thống bảo vệ an toàn rảnh tay 24/7",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = mutedColor,
                    ),
                    modifier = Modifier.padding(top = 2.dp, bottom = 18.dp),
                )

                SosButton(
                    onSosTriggered = { triggerEmergencyCall() },
                    emergencyPhone = emergencyPhone,
                )
            }
        }

        SectionLabel(text = "Cấu Hình Số ĐT Khẩn Cấp Thật (1 Người Thân)")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = editedPhone,
                    onValueChange = { editedPhone = it },
                    label = { Text("Nhập số điện thoại người thân thật (VD: 0912345678)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                GradientButton(
                    text = "Lưu Số Khẩn Cấp",
                    onClick = { savePhone(editedPhone) },
                )
            }
        }

        if (emergencyPhone.isNotBlank()) {
            RowCard(
                title = "Gọi Khẩn Cấp Thật Ngay Tức Thì",
                subtitle = "SĐT: $emergencyPhone (Nhấn để gọi điện trực tiếp)",
                icon = Icons.Default.Person,
                iconTone = dangerColor,
                onClick = { triggerEmergencyCall() },
                trailing = {
                    Text(
                        text = "Gọi ngay",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = dangerColor,
                        )
                    )
                }
            )
        }

        SectionLabel(text = "Hướng Dẫn An Toàn SOS")

        RowCard(
            title = "Báo động 3-Tap rảnh tay",
            subtitle = "Bấm nhanh nút SOS 3 lần trong 2 giây để ngay lập tức kích hoạt cuộc gọi khẩn cấp.",
            icon = Icons.Default.Info,
            iconTone = accentTextColor,
        )
    }
}
