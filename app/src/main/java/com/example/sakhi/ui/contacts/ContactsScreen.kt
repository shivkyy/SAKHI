package com.example.sakhi.ui.contacts

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sakhi.R
import com.example.sakhi.core.database.ContactRepository
import com.example.sakhi.core.designsystem.SakhiButton
import com.example.sakhi.core.designsystem.SakhiOutlinedButton
import com.example.sakhi.core.designsystem.StatusPill
import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.data.alerts.AlertDispatcher
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiLavenderAccent
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning
import kotlinx.coroutines.launch

@Composable
fun ContactsScreen(
    contactRepository: ContactRepository,
    alertDispatcher: AlertDispatcher
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts by contactRepository.contactsFlow.collectAsState(initial = emptyList())

    androidx.compose.runtime.LaunchedEffect(Unit) {
        contactRepository.refreshContacts()
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var contactToEdit by remember { mutableStateOf<Contact?>(null) }
    var contactToDelete by remember { mutableStateOf<Contact?>(null) }
    var testFeedback by remember { mutableStateOf<String?>(null) }

    Scaffold(
        floatingActionButton = {
            if (contacts.size < 5) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = SakhiPrimary,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Contact")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.contacts_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${contacts.size} of 5 contacts configured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                StatusPill(
                    text = if (contacts.isNotEmpty()) "Armed" else "Empty",
                    color = if (contacts.isNotEmpty()) SakhiSuccess else SakhiWarning
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            if (contacts.isEmpty()) {
                // Empty state card
                EmptyContactsCard(onAddClicked = { showAddDialog = true })
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(contacts, key = { it.id }) { contact ->
                        ContactItemCard(
                            contact = contact,
                            onEdit = { contactToEdit = contact },
                            onDelete = { contactToDelete = contact },
                            onToggleSms = { enabled ->
                                scope.launch {
                                    contactRepository.updateContact(contact.copy(smsEnabled = enabled))
                                }
                            },
                            onSendTestSms = {
                                scope.launch {
                                    testFeedback = "Sending real test SMS to ${contact.name}..."
                                    val result = alertDispatcher.dispatchAlertToContacts(
                                        contacts = listOf(contact),
                                        location = null,
                                        template = "TEST: This is a test alert from SAKHI safety app. No action needed.",
                                        includeAccuracy = false,
                                        onContactStatusUpdate = { _, _ -> }
                                    )
                                    testFeedback = when (result) {
                                        ContactAlertState.SENT -> "Test SMS sent successfully to ${contact.name}."
                                        ContactAlertState.DELIVERED -> "Test SMS delivered to ${contact.name}."
                                        else -> "Test SMS failed. Please verify SMS permissions and carrier network."
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Add Contact Dialog
    if (showAddDialog) {
        ContactEditDialog(
            title = "Add Trusted Contact",
            existingContact = null,
            nextPriority = contacts.size + 1,
            onDismiss = { showAddDialog = false },
            onSave = { newContact ->
                scope.launch {
                    contactRepository.addContact(newContact)
                    showAddDialog = false
                }
            }
        )
    }

    // Edit Contact Dialog
    contactToEdit?.let { contact ->
        ContactEditDialog(
            title = "Edit Contact",
            existingContact = contact,
            nextPriority = contact.priority,
            onDismiss = { contactToEdit = null },
            onSave = { updated ->
                scope.launch {
                    contactRepository.updateContact(updated)
                    contactToEdit = null
                }
            }
        )
    }

    // Delete Confirmation Dialog
    contactToDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { contactToDelete = null },
            title = { Text("Delete Contact", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to remove ${contact.name} from your trusted emergency contacts?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            contactRepository.deleteContact(contact.id)
                            contactToDelete = null
                        }
                    }
                ) {
                    Text("Delete", color = SakhiEmergency, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Test SMS Feedback Dialog
    testFeedback?.let { msg ->
        AlertDialog(
            onDismissRequest = { testFeedback = null },
            title = { Text("SMS Dispatch Status", fontWeight = FontWeight.Bold) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { testFeedback = null }) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun ContactItemCard(
    contact: Contact,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleSms: (Boolean) -> Unit,
    onSendTestSms: () -> Unit
) {
    var isNumberMasked by remember { mutableStateOf(true) }
    val maskedNumber = remember(contact.phoneNumber, isNumberMasked) {
        if (!isNumberMasked || contact.phoneNumber.length < 5) {
            contact.phoneNumber
        } else {
            val prefix = contact.phoneNumber.take(3)
            val suffix = contact.phoneNumber.takeLast(4)
            "$prefix ••••• $suffix"
        }
    }

    val priorityLabel = when (contact.priority) {
        1 -> "Primary"
        2 -> "Secondary"
        else -> "Priority ${contact.priority}"
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(SakhiPrimary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = SakhiPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = contact.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { isNumberMasked = !isNumberMasked }
                        ) {
                            Text(
                                text = maskedNumber,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isNumberMasked) "(Show)" else "(Hide)",
                                style = MaterialTheme.typography.labelSmall,
                                color = SakhiLavenderAccent,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (contact.priority == 1) SakhiPrimary.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = priorityLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (contact.priority == 1) SakhiPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = contact.smsEnabled,
                        onCheckedChange = onToggleSms,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = SakhiSuccess,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (contact.smsEnabled) "SMS Armed" else "SMS Muted",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (contact.smsEnabled) SakhiSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSendTestSms) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send Test SMS",
                            tint = SakhiPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Contact",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Contact",
                            tint = SakhiEmergency,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyContactsCard(onAddClicked: () -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(SakhiWarning.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = null,
                    tint = SakhiWarning,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No Trusted Contacts Yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Add up to 5 trusted family members or friends. During an emergency, SAKHI immediately dispatches your live GPS coordinates to them via SMS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
            SakhiButton(
                text = "Add First Contact",
                onClick = onAddClicked,
                height = 50.dp
            )
        }
    }
}

@Composable
fun ContactEditDialog(
    title: String,
    existingContact: Contact?,
    nextPriority: Int,
    onDismiss: () -> Unit,
    onSave: (Contact) -> Unit
) {
    var name by remember { mutableStateOf(existingContact?.name ?: "") }
    var phone by remember { mutableStateOf(existingContact?.phoneNumber ?: "") }
    var priority by remember { mutableStateOf(existingContact?.priority ?: nextPriority) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMsg = null },
                    label = { Text("Full Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; errorMsg = null },
                    label = { Text("Phone Number (e.g. +91 9876543210)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMsg!!, color = SakhiEmergency, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cleanPhone = phone.trim()
                    val cleanName = name.trim()
                    if (cleanName.isBlank()) {
                        errorMsg = "Please enter contact's name."
                        return@TextButton
                    }
                    if (cleanPhone.length < 5) {
                        errorMsg = "Please enter a valid phone number."
                        return@TextButton
                    }
                    val contact = existingContact?.copy(
                        name = cleanName,
                        phoneNumber = cleanPhone,
                        priority = priority
                    ) ?: Contact(
                        name = cleanName,
                        phoneNumber = cleanPhone,
                        priority = priority,
                        smsEnabled = true
                    )
                    onSave(contact)
                }
            ) {
                Text("Save", fontWeight = FontWeight.Bold, color = SakhiPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
