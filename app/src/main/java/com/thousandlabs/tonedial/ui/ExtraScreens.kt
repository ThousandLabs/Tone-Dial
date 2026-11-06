@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.thousandlabs.tonedial.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thousandlabs.tonedial.BuildConfig
import com.thousandlabs.tonedial.playback.AudioFx

const val REPO_URL = "https://github.com/ThousandLabs/Tone-Dial"
const val LATEST_APK_URL = "$REPO_URL/releases/latest/download/ToneDial.apk"
const val RELEASES_URL = "$REPO_URL/releases"
const val SITE_URL = "https://thousandlabs.github.io"

// ------------------------------------------------------------------
// Égaliseur
// ------------------------------------------------------------------

@Composable
fun EqualizerScreen(nav: Nav) {
    val fx by AudioFx.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Égaliseur", nav.back, actions = {
            TextButton(onClick = { AudioFx.reset() }, enabled = fx.available) { Text("Réinitialiser") }
        })
        if (!fx.available) {
            EmptyState(
                Icons.Outlined.Equalizer,
                "Égaliseur pas encore prêt",
                "Lancez un morceau, puis revenez ici. Certains téléphones ne proposent pas d'égaliseur aux applications.",
            )
            return
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Activer les effets", style = MaterialTheme.typography.titleMedium)
                        Text("Égaliseur, basses et effet d'espace", style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
                    }
                    Switch(
                        checked = fx.enabled,
                        onCheckedChange = { AudioFx.setEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = TdColors.Accent, checkedThumbColor = TdColors.Background),
                    )
                }
            }
            if (fx.presets.isNotEmpty()) {
                item {
                    SectionLabel("Préréglages")
                    FlowRow(
                        Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        fx.presets.forEachIndexed { i, name ->
                            FilterChip(
                                selected = fx.currentPreset == i,
                                onClick = { AudioFx.usePreset(i) },
                                label = { Text(name) },
                                enabled = fx.enabled,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TdColors.AccentDim,
                                    selectedLabelColor = TdColors.Accent,
                                ),
                            )
                        }
                    }
                }
            }
            item {
                SectionLabel("Bandes de fréquence")
                fx.bandFreqs.forEachIndexed { i, hz ->
                    val level = fx.bandLevels.getOrElse(i) { 0 }
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatHz(hz), modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
                        Slider(
                            value = level.toFloat(),
                            onValueChange = { AudioFx.setBand(i, it.toInt()) },
                            valueRange = fx.minLevel.toFloat()..fx.maxLevel.toFloat(),
                            enabled = fx.enabled,
                            modifier = Modifier.weight(1f),
                            colors = sliderColors(),
                        )
                        Text(
                            "%+d dB".format(level / 100),
                            modifier = Modifier.width(52.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                SectionLabel("Effets")
                EffectSlider(Icons.Outlined.GraphicEq, "Amplificateur de basses", fx.bass, fx.enabled && fx.bassSupported) { AudioFx.setBass(it) }
                EffectSlider(Icons.Outlined.SurroundSound, "Effet d'espace (3D)", fx.virtualizer, fx.enabled && fx.virtualizerSupported) { AudioFx.setVirtualizer(it) }
                if (!fx.enabled) {
                    Text(
                        "Activez les effets en haut de l'écran pour les régler.",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = TdColors.TextDim,
                    )
                }
            }
        }
    }
}

@Composable
private fun EffectSlider(icon: ImageVector, label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = TdColors.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${value / 10} %", style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..1000f,
            enabled = enabled,
            colors = sliderColors(),
        )
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = TdColors.Accent,
    activeTrackColor = TdColors.Accent,
    inactiveTrackColor = TdColors.Divider,
)

private fun formatHz(hz: Int): String = if (hz >= 1000) "%.1f kHz".format(hz / 1000f).replace(".0", "") else "$hz Hz"

// ------------------------------------------------------------------
// Guide
// ------------------------------------------------------------------

private data class GuideSection(val icon: ImageVector, val title: String, val points: List<String>)

private val guide = listOf(
    GuideSection(
        Icons.Outlined.LibraryMusic, "Votre bibliothèque",
        listOf(
            "Tone Dial trouve automatiquement toutes les musiques enregistrées sur le téléphone : rien à importer.",
            "Les onglets Titres, Albums, Artistes, Dossiers et Playlists permettent de parcourir la musique de plusieurs façons. Glissez de gauche à droite pour passer d'un onglet à l'autre.",
            "Après avoir téléchargé de nouvelles musiques, ouvrez le menu (trois points en haut) puis « Actualiser la bibliothèque ».",
            "Les notes vocales et sons courts sont masqués. Réglez la durée minimale dans Menu > Réglages.",
        ),
    ),
    GuideSection(
        Icons.Outlined.PlayArrow, "Lecture",
        listOf(
            "Appuyez sur un titre pour lancer la lecture, ou sur « Lecture aléatoire » pour tout mélanger.",
            "La barre du bas montre le titre en cours. Appuyez dessus pour ouvrir le lecteur complet.",
            "La musique continue écran éteint ou dans une autre appli. Les commandes sont aussi dans la notification et sur l'écran verrouillé.",
            "Débrancher les écouteurs ou couper le Bluetooth met la musique en pause automatiquement.",
            "Au prochain lancement, Tone Dial reprend là où vous vous étiez arrêté.",
        ),
    ),
    GuideSection(
        Icons.Outlined.MoreVert, "Menu d'un morceau",
        listOf(
            "Appuyez sur les trois points d'un titre (ou restez appuyé dessus) pour : le lire juste après, l'ajouter à la file d'attente ou à une playlist, le mettre en favori, voir son album ou son artiste, le définir comme sonnerie, le partager ou afficher ses informations.",
        ),
    ),
    GuideSection(
        Icons.Outlined.QueueMusic, "Playlists et favoris",
        listOf(
            "Le cœur dans le lecteur ajoute le titre aux Favoris.",
            "Les playlists automatiques se remplissent toutes seules : Favoris, Les plus écoutés, Écoutés récemment, Ajoutés récemment.",
            "Pour créer votre playlist : onglet Playlists > Créer, ou menu d'un titre > Ajouter à une playlist.",
            "Dans une playlist, les trois points en haut permettent de la renommer ou de la supprimer. Vos fichiers de musique ne sont jamais supprimés.",
        ),
    ),
    GuideSection(
        Icons.Outlined.Subject, "Paroles",
        listOf(
            "Dans le lecteur, appuyez sur « Paroles ».",
            "Tone Dial lit les paroles intégrées aux fichiers MP3 et FLAC, ou un fichier .lrc portant le même nom que la musique, dans le même dossier.",
            "Vous pouvez aussi coller les paroles vous-même. Si elles sont au format .lrc (avec les minutages [01:23.45]), elles défilent en rythme avec la musique, et un appui sur une ligne y fait sauter la lecture.",
            "Tout fonctionne hors ligne.",
        ),
    ),
    GuideSection(
        Icons.Outlined.Equalizer, "Égaliseur",
        listOf(
            "Ouvrez l'égaliseur depuis l'icône en haut de l'accueil ou depuis le lecteur.",
            "Activez les effets, puis choisissez un préréglage (Rock, Pop, Jazz…) ou réglez chaque bande vous-même.",
            "L'amplificateur de basses et l'effet d'espace se règlent en bas de l'écran. Vos réglages sont gardés.",
        ),
    ),
    GuideSection(
        Icons.Outlined.Timer, "Minuterie de sommeil",
        listOf(
            "Dans le lecteur, appuyez sur « Minuterie » et choisissez une durée, ou « À la fin du titre en cours ».",
            "Le temps restant s'affiche sous l'icône. Appuyez à nouveau pour l'arrêter.",
        ),
    ),
    GuideSection(
        Icons.Outlined.Widgets, "Widget",
        listOf(
            "Restez appuyé sur l'écran d'accueil du téléphone, choisissez « Widgets », puis Tone Dial.",
            "Le widget affiche le titre en cours avec les boutons précédent, lecture/pause et suivant.",
        ),
    ),
    GuideSection(
        Icons.Outlined.BatteryChargingFull, "Si la musique se coupe toute seule",
        listOf(
            "Certains téléphones (Tecno, Infinix, Itel, Xiaomi…) ferment les applis en arrière-plan pour économiser la batterie.",
            "Allez dans Paramètres > Applications > Tone Dial > Batterie, et choisissez « Pas de restriction » (ou désactivez l'optimisation).",
        ),
    ),
    GuideSection(
        Icons.Outlined.SystemUpdate, "Mises à jour",
        listOf(
            "Menu > À propos et mises à jour > « Télécharger la dernière version ».",
            "Ouvrez le fichier téléchargé et appuyez sur « Mettre à jour ». Vos playlists, favoris et réglages sont conservés.",
        ),
    ),
)

@Composable
fun GuideScreen(nav: Nav) {
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Guide d'utilisation", nav.back)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(guide.size) { i ->
                val s = guide[i]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(TdColors.Surface)
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircleIcon(s.icon)
                        Spacer(Modifier.width(12.dp))
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    s.points.forEach { p ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Box(
                                Modifier
                                    .padding(top = 8.dp, end = 10.dp)
                                    .size(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(TdColors.Accent)
                            )
                            Text(p, style = MaterialTheme.typography.bodyMedium, color = TdColors.Text)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// À propos
// ------------------------------------------------------------------

@Composable
fun AboutScreen(nav: Nav) {
    val context = LocalContext.current
    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("À propos", nav.back)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(TdColors.Surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.GraphicEq, null, tint = TdColors.Accent, modifier = Modifier.size(44.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Row {
                        Text("Tone", style = MaterialTheme.typography.headlineSmall)
                        Text(" Dial", style = MaterialTheme.typography.headlineSmall, color = TdColors.Accent)
                    }
                    Text("Version ${BuildConfig.VERSION_NAME}", color = TdColors.TextDim, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Lecteur de musique hors ligne, sans publicité et sans connexion Internet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TdColors.TextDim,
                    )
                    Text("Une application Thousand Labs.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
            item {
                SectionLabel("Mises à jour")
                ListItemRow(
                    Icons.Outlined.SystemUpdate, "Télécharger la dernière version",
                    subtitle = "Ouvrez ensuite le fichier et appuyez sur « Mettre à jour »",
                    tint = TdColors.Accent,
                    onClick = { open(LATEST_APK_URL) },
                )
                ListItemRow(Icons.Outlined.NewReleases, "Historique des versions", subtitle = "Nouveautés de chaque version", onClick = { open(RELEASES_URL) })
                SectionLabel("Liens officiels")
                ListItemRow(Icons.Outlined.Code, "Dépôt GitHub", subtitle = REPO_URL.removePrefix("https://"), onClick = { open(REPO_URL) })
                ListItemRow(Icons.Outlined.Language, "Site Thousand Labs", subtitle = SITE_URL.removePrefix("https://"), onClick = { open(SITE_URL) })
                SectionLabel("Confidentialité")
                Text(
                    "Tone Dial ne demande pas l'accès à Internet. Vos musiques, playlists, favoris et statistiques d'écoute restent uniquement sur votre téléphone.",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TdColors.TextDim,
                )
            }
        }
    }
}
