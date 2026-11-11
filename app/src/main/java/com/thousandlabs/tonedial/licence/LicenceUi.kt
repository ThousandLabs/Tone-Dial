package com.thousandlabs.tonedial.licence

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thousandlabs.tonedial.ui.TdColors
import java.net.URLEncoder

private fun open(c: Context, url: String) {
    runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun copy(c: Context, text: String) {
    runCatching {
        (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Tone Dial", text))
        Toast.makeText(c, "Copié", Toast.LENGTH_SHORT).show()
    }
}

private fun paste(c: Context): String =
    runCatching {
        (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(c)?.toString()
    }.getOrNull().orEmpty()

fun fmtLeft(ms: Long): String {
    val m = maxOf(1L, (ms + 59_999) / 60_000)
    val h = m / 60
    return if (h > 0) "$h h ${(m % 60).toString().padStart(2, '0')} min" else "$m min"
}

/** Écran affiché tant que Tone Dial n'est ni en essai ni activée. */
@Composable
fun LockScreen(state: Licence.State, onTrial: () -> Unit, onActivated: () -> Unit) {
    val c = LocalContext.current
    var showCode by remember { mutableStateOf(state == Licence.State.EXPIRED) }
    Column(
        Modifier
            .fillMaxSize()
            .background(TdColors.Background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(TdColors.Surface),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.GraphicEq, null, tint = TdColors.Accent, modifier = Modifier.size(44.dp)) }
        Spacer(Modifier.height(14.dp))
        Row {
            Text("Tone", style = MaterialTheme.typography.headlineSmall)
            Text(" Dial", style = MaterialTheme.typography.headlineSmall, color = TdColors.Accent)
        }
        Text("par Thousand Labs", color = TdColors.TextDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(18.dp))
        if (state == Licence.State.EXPIRED) {
            Text("Votre essai gratuit est terminé", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                "Activez Tone Dial pour continuer. Vos playlists, favoris et réglages sont conservés.",
                color = TdColors.TextDim, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text("Votre musique, sans publicité.", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Feature(Icons.Outlined.LibraryMusic, "Toute votre musique", "Titres, albums, artistes, dossiers et playlists")
            Feature(Icons.Outlined.Subtitles, "Paroles et pochettes", "Paroles synchronisées, pochettes des openings d'anime")
            Feature(Icons.Outlined.WifiOff, "Hors ligne, sans compte", "Tout reste sur votre téléphone")
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onTrial,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TdColors.Accent),
            ) { Text("Essayer gratuitement 48 h", fontWeight = FontWeight.SemiBold) }
            val availableLeft = remember { Licence.trialAvailableLeft(c) }
            if (availableLeft < Licence.TRIAL_MS - 30 * 60_000L) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "L'essai compte à partir de l'installation : il reste ${fmtLeft(availableLeft)}.",
                    color = TdColors.TextDim, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Puis ${Licence.PRICE}, une seule fois", color = TdColors.TextDim, style = MaterialTheme.typography.bodySmall)
            if (!showCode) TextButton(onClick = { showCode = true }) { Text("J'ai déjà un code") }
        }
        if (showCode) {
            Spacer(Modifier.height(16.dp))
            ActivationPanel(onActivated)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(TdColors.Surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = TdColors.Accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, color = TdColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Code appareil, achat (Wave, Orange Money, WhatsApp) et saisie du code d'activation. */
@Composable
fun ActivationPanel(onActivated: () -> Unit) {
    val c = LocalContext.current
    val dev = remember { Licence.deviceId(c) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(TdColors.Surface).padding(16.dp),
    ) {
        Text("1. Achetez Tone Dial (${Licence.PRICE})", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Paiement unique, sans abonnement. Le code est lié à ce téléphone.",
            color = TdColors.TextDim, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { open(c, Licence.WAVE_URL) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = TdColors.Accent),
        ) {
            Icon(Icons.Outlined.Payments, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Payer avec Wave")
        }
        Text(
            "Orange Money : ${Licence.PHONE_FMT} · ou en espèces, en main propre.",
            color = TdColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text("2. Envoyez votre code appareil", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                dev, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = TdColors.Accent, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { copy(c, dev) }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copier le code appareil") }
        }
        OutlinedButton(
            onClick = { open(c, "https://wa.me/${Licence.WHATSAPP}?text=" + URLEncoder.encode(Licence.buyMessage(c), "UTF-8").replace("+", "%20")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Chat, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Envoyer ma demande sur WhatsApp")
        }
        Spacer(Modifier.height(14.dp))
        Text("3. Entrez le code reçu", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Code d'activation") },
            singleLine = false,
            maxLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            trailingIcon = {
                IconButton(onClick = { code = paste(c); error = null }) { Icon(Icons.Outlined.ContentPaste, contentDescription = "Coller") }
            },
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                when {
                    !Licence.keyInstalled -> error = "L'activation sera disponible dans la prochaine mise à jour de Tone Dial."
                    Licence.activate(c, code) -> {
                        Toast.makeText(c, "Tone Dial est activée. Merci !", Toast.LENGTH_LONG).show()
                        onActivated()
                    }
                    else -> error = "Code invalide pour ce téléphone. Vérifiez qu'il a été généré pour le code appareil $dev."
                }
            },
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = TdColors.Accent),
        ) {
            Icon(Icons.Outlined.Key, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Activer")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Une fois activée, Tone Dial le reste : plus besoin de saisir le code, même après une mise à jour. Après une réinstallation sur ce même téléphone, le même code fonctionne à nouveau.",
            color = TdColors.TextDim, style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Bandeau d'essai en haut de l'appli, avec accès à l'activation. */
@Composable
fun TrialBanner(leftMs: Long, onActivate: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(TdColors.AccentDim)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Redeem, null, tint = TdColors.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Essai gratuit : il reste ${fmtLeft(leftMs)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onActivate, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text("Activer") }
    }
}

/** Fenêtre d'activation ouverte depuis le bandeau d'essai ou l'écran « À propos ». */
@Composable
fun ActivationDialog(onDismiss: () -> Unit, onActivated: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        title = { Text("Activer Tone Dial") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { ActivationPanel { onActivated(); onDismiss() } } },
        containerColor = TdColors.Background,
    )
}

/** Section « Licence » de l'écran À propos. */
@Composable
fun LicenceSection() {
    val c = LocalContext.current
    var state by remember { mutableStateOf(Licence.state(c)) }
    var dialog by remember { mutableStateOf(false) }
    val dev = remember { Licence.deviceId(c) }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            when (state) {
                Licence.State.ACTIVE -> "Activée sur ce téléphone"
                Licence.State.TRIAL -> "Essai gratuit : il reste ${fmtLeft(Licence.trialLeft(c))}"
                else -> "Non activée"
            },
            style = MaterialTheme.typography.titleSmall,
            color = if (state == Licence.State.ACTIVE) TdColors.Accent else TdColors.Text,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Code appareil : $dev", color = TdColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { copy(c, dev) }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copier le code appareil") }
        }
        if (state != Licence.State.ACTIVE) {
            Button(onClick = { dialog = true }, colors = ButtonDefaults.buttonColors(containerColor = TdColors.Accent)) {
                Text("Acheter ou activer (${Licence.PRICE})")
            }
        }
    }
    if (dialog) ActivationDialog(onDismiss = { dialog = false }, onActivated = { state = Licence.State.ACTIVE })
}
