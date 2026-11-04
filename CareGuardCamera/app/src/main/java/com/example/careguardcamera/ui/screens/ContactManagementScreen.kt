package com.example.careguardcamera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.PatientContact
import com.example.careguardcamera.ui.components.Avatar
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.CGChip
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.components.IconCircle
import com.example.careguardcamera.ui.components.TrustNote
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactManagementScreen(onBack: () -> Unit, onNext: (() -> Unit)? = null) {
    val contacts by BackendRepository.contacts.collectAsState()
    val loading by BackendRepository.loading.collectAsState()
    val serverError by BackendRepository.error.collectAsState()
    val scope = rememberCoroutineScope()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = contacts.firstOrNull { it.id == editingId }
    var name by rememberSaveable(editingId, editing?.name) { mutableStateOf(editing?.name.orEmpty()) }
    var relation by rememberSaveable(editingId, editing?.relation) { mutableStateOf(editing?.relation ?: "자녀") }
    var phone by rememberSaveable(editingId, editing?.phone) { mutableStateOf(editing?.phone.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(serverError) {
        if (serverError != null) error = serverError
    }

    fun clearForm() {
        editingId = null
        name = ""
        relation = "자녀"
        phone = ""
        error = null
    }

    suspend fun saveDraft(): Boolean {
        val hasDraft = name.isNotBlank() || phone.isNotBlank()
        if (!hasDraft) return true
        val result = BackendRepository.saveContact(editingId, name, relation, phone)
        error = result
        if (result == null) clearForm()
        return result == null
    }

    val title = if (onNext == null) "보호자 관리" else "보호자·비상 연락처 · 2/3"

    Column(Modifier.fillMaxSize().background(CGColor.Surface).statusBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            CGTopBar(title = title, onBack = onBack, centerTitle = onNext != null)
            Spacer(Modifier.height(12.dp))
            Text(if (onNext == null) "연락처를 관리해주세요" else "연락 받을 사람을\n등록해주세요", style = CGType.Headline.copy(fontSize = 26.sp, lineHeight = 34.sp), color = CGColor.Ink)
            Spacer(Modifier.height(8.dp))
            Text("가족 계정 가입 여부와 상관없이 비상 연락처로 저장할 수 있어요.", style = CGType.Label, color = CGColor.Ink2)
            Spacer(Modifier.height(16.dp))
            TrustNote("첫 번째 연락처는 대표 보호자로 표시됩니다. 권한 부여나 초대 발송은 아직 하지 않습니다.")
            Spacer(Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                contacts.forEachIndexed { index, contact ->
                    ContactCard(
                        contact = contact,
                        badge = if (index == 0) "대표" else null,
                        selected = editingId == contact.id,
                        onEdit = {
                            editingId = contact.id
                            error = null
                        },
                        onRemove = {
                            scope.launch {
                                deletingId = contact.id
                                val result = BackendRepository.removeContact(contact.id)
                                deletingId = null
                                if (result == null) {
                                    if (editingId == contact.id) clearForm()
                                } else {
                                    error = result
                                }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            FormCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (editingId == null) "연락처 추가" else "연락처 수정", style = CGType.BodyBold, color = CGColor.Ink, modifier = Modifier.weight(1f))
                    if (editingId != null) {
                        Text("새로 입력", style = CGType.Caption, color = CGColor.Brand, modifier = Modifier.clickable { clearForm() })
                    }
                }
                Spacer(Modifier.height(14.dp))
                ContactFieldLabel("이름")
                CGTextField(name, { name = it; error = null }, placeholder = "보호자 이름")
                Spacer(Modifier.height(14.dp))
                ContactFieldLabel("관계")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("자녀", "배우자", "형제/자매", "친척", "지인", "기타").forEach { label ->
                        CGChip(label, relation == label, { relation = label; error = null })
                    }
                }
                Spacer(Modifier.height(14.dp))
                ContactFieldLabel("연락처")
                CGTextField(phone, { phone = it; error = null }, placeholder = "010-0000-0000", keyboardType = KeyboardType.Phone)
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error.orEmpty(), style = CGType.Caption, color = CGColor.Critical)
                }
                Spacer(Modifier.height(14.dp))
                CGButton(
                    text = if (editingId == null) "연락처 저장" else "수정 저장",
                    onClick = {
                        scope.launch {
                            saving = true
                            saveDraft()
                            saving = false
                        }
                    },
                    kind = ButtonKind.Soft,
                    icon = Icons.Outlined.Add,
                    enabled = !saving && !loading && deletingId == null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(
            Modifier.fillMaxWidth().background(CGColor.Surface).navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onNext != null) {
                CGButton(
                    "다음",
                    onClick = {
                        scope.launch {
                            saving = true
                            if (saveDraft()) {
                                if (BackendRepository.contacts.value.isEmpty()) error = "보호자 또는 비상 연락처를 1명 이상 등록해주세요." else onNext()
                            }
                            saving = false
                        }
                    },
                    kind = ButtonKind.Brand,
                    large = true,
                    enabled = !saving && !loading && deletingId == null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ContactCard(contact: PatientContact, badge: String?, selected: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CGColor.Paper)
            .border(if (selected) 2.dp else 1.dp, if (selected) CGColor.Brand else CGColor.Line, RoundedCornerShape(18.dp))
            .clickable { onEdit() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(contact.name.take(1).ifEmpty { "보" }, 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(contact.name, style = CGType.BodyBold, color = CGColor.Ink)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(CGColor.BrandSoft).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(badge, style = CGType.Caption, color = CGColor.BrandInk)
                    }
                }
            }
            Text("${contact.relation} · ${contact.phone}", style = CGType.Caption, color = CGColor.Ink3)
        }
        IconCircle(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = "삭제", tint = CGColor.Ink3)
        }
    }
}

@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CGColor.Paper)
            .border(1.dp, CGColor.Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun ContactFieldLabel(text: String) {
    Text(text, style = CGType.Label, color = CGColor.Ink2, modifier = Modifier.padding(bottom = 6.dp))
}
