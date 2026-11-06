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
import com.thousandlabs.tonedial.update.Updater

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
        Icons.Outlined.Person, "Identification des artistes",
        listOf(
            "Beaucoup de musiques téléchargées n'ont pas d'artiste renseigné, et leur nom ressemble à « Oliver Tree - Life Goes On ». Tone Dial sépare alors automatiquement l'artiste et le titre.",
            "Les mentions inutiles sont retirées des titres : (Official Video), [Lyrics], (Audio), (Clip Officiel), noms de sites de téléchargement, numéros de piste…",
            "Les artistes invités sont reconnus (feat., ft., virgules, « & ») : un titre « Dadju feat. Tayc » apparaît chez Dadju et chez Tayc dans l'onglet Artistes. Le menu d'un titre propose d'ouvrir chacun de ses artistes.",
            "Vos fichiers ne sont jamais modifiés. Pour désactiver cette fonction : Menu > Réglages > Identifier les artistes.",
        ),
    ),
    GuideSection(
        Icons.Outlined.PlayArrow, "Lecture",
        listOf(
            "Appuyez sur un titre pour lancer la lecture, ou sur « Lecture aléatoire » pour tout mélanger.",
            "La barre du bas montre le titre en cours. Appuyez dessus pour ouvrir le lecteur complet.",
            "La musique continue écran éteint ou dans une autre appli. Les commandes sont aussi dans la notification et sur l'écran verrouillé.",
            "Débrancher les écouteurs ou couper le Bluetooth met la musique en pause automatiquement.",
            "La notification, l'écran verrouillé et le centre de contrôle affichent la pochette du titre en grand (celle de l'album ou celle intégrée au fichier), ou le logo Tone Dial si le titre n'en a pas.",
            "Au prochain lancement, Tone Dial reprend là où vous vous étiez arrêté.",
        ),
    ),
    GuideSection(
        Icons.Outlined.MoreVert, "Menu d'un morceau",
        listOf(
            "Appuyez sur les trois points d'un titre pour : le lire juste après, l'ajouter à la file d'attente ou à une playlist, le mettre en favori, voir son album ou son artiste, le définir comme sonnerie, le partager ou afficher ses informations.",
        ),
    ),
    GuideSection(
        Icons.Outlined.Checklist, "Sélectionner plusieurs titres",
        listOf(
            "Restez appuyé sur un titre pour le sélectionner, puis appuyez simplement sur les autres pour les ajouter ou les retirer de la sélection.",
            "La barre du haut indique le nombre de titres choisis. L'icône de liste avec un plus les ajoute à une playlist existante ou à une nouvelle playlist.",
            "Les trois points de cette barre permettent aussi de lire les titres maintenant, juste après, de les ajouter à la file d'attente ou aux favoris. Dans une de vos playlists, vous pouvez en retirer plusieurs d'un coup.",
            "L'icône « Tout sélectionner » choisit tous les titres affichés. La croix ou le bouton retour annule la sélection.",
            "Cela fonctionne dans Titres, les albums, artistes, dossiers, playlists et la recherche.",
        ),
    ),
    GuideSection(
        Icons.Outlined.Swipe, "Gestes et navigation",
        listOf(
            "Balayez la barre de lecture du bas, ou la pochette dans le lecteur, vers la gauche pour le titre suivant et vers la droite pour le précédent.",
            "Le bouton retour vous ramène d'abord à l'onglet Titres. Depuis Titres, appuyez deux fois de suite sur retour pour quitter l'appli. La musique continue si elle était en lecture.",
        ),
    ),
    GuideSection(
        Icons.Outlined.PlaylistPlay, "File d'attente",
        listOf(
            "Dans le lecteur, appuyez sur « File » pour voir les titres à venir, dans l'ordre où ils seront joués.",
            "Faites glisser la poignée à droite d'un titre pour le monter ou le descendre. Cela fonctionne aussi en lecture aléatoire : l'ordre mélangé est remplacé par le vôtre, l'aléatoire reste activé et la musique ne s'arrête pas.",
            "Restez appuyé sur un titre de la file pour « Lire ensuite » (juste après le morceau en cours), « Lire maintenant » ou « Retirer de la file ». La croix le retire aussi.",
            "« Lire ensuite », depuis le menu d'un titre, place bien le titre juste après le morceau en cours, même en aléatoire.",
        ),
    ),
    GuideSection(
        Icons.Outlined.QueueMusic, "Playlists et favoris",
        listOf(
            "Le cœur dans le lecteur ajoute le titre aux Favoris.",
            "Les playlists automatiques se remplissent toutes seules : Favoris, Les plus écoutés, Écoutés récemment, Ajoutés récemment.",
            "Pour créer votre playlist : onglet Playlists > Créer, ou menu d'un titre > Ajouter à une playlist.",
            "Dans une playlist, le bouton « Ajouter des titres » affiche toute votre musique sauf les titres déjà présents, ce qui évite les doublons. Cochez les titres voulus (recherche et « Tout sélectionner » disponibles), puis appuyez sur « Ajouter ».",
            "Pour trier une playlist, appuyez sur l'icône de tri en haut : ordre personnalisé, ajoutés récemment en premier, ajoutés en premier, titre (A → Z), artiste, album, téléchargés récemment ou durée. Chaque playlist garde son tri, et la lecture suit l'ordre affiché.",
            "Pour placer les titres où vous voulez : icône de tri > « Réorganiser à la main ». Faites glisser la poignée à droite d'un titre, puis appuyez sur « Terminé ». La playlist passe alors en ordre personnalisé.",
            "Les Favoris ont aussi leur bouton de tri.",
            "Dans une playlist, les trois points en haut permettent aussi de la renommer ou de la supprimer. Vos fichiers de musique ne sont jamais supprimés.",
        ),
    ),
    GuideSection(
        Icons.Outlined.Subject, "Paroles",
        listOf(
            "Dans le lecteur, appuyez sur « Paroles ».",
            "Tone Dial lit d'abord les paroles intégrées aux fichiers MP3 et FLAC, ou un fichier .lrc portant le même nom que la musique, dans le même dossier.",
            "S'il n'y en a pas, l'appli les cherche automatiquement sur LRCLIB, une base de paroles libre et gratuite. Les paroles trouvées sont enregistrées sur le téléphone : la fois suivante, plus besoin de connexion.",
            "Si rien n'est trouvé : « Google » ouvre la recherche dans le navigateur. Copiez les paroles, revenez dans Tone Dial et appuyez sur « Coller ». « Saisir » permet aussi de les taper ou de les modifier.",
            "Les paroles synchronisées (minutages comme [01:23.45]) défilent en rythme avec la musique, et un appui sur une ligne y fait sauter la lecture.",
            "Sous les paroles, « Options » permet d'en chercher d'autres, de les modifier ou de les supprimer.",
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
            "À la fin du délai, le son baisse doucement pendant quelques secondes avant la pause.",
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
            "Quand une nouvelle version existe, un bandeau apparaît en haut de l'accueil. Appuyez sur « Mettre à jour », puis confirmez dans la fenêtre d'Android : c'est tout.",
            "La première fois, Android demande d'autoriser Tone Dial à installer des applications. Activez l'option, revenez, et la mise à jour démarre toute seule.",
            "Vous pouvez aussi vérifier vous-même dans Menu > À propos et mises à jour. La vérification automatique se désactive au même endroit.",
            "Si vous quittez l'appli avant de confirmer, rien n'est perdu : en revenant, appuyez sur « Relancer l'installation ». Le fichier déjà téléchargé est réutilisé.",
            "Vos playlists, favoris et réglages sont conservés.",
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
                        "Lecteur de musique hors ligne et sans publicité.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TdColors.TextDim,
                    )
                    Text("Une application Thousand Labs.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
            item {
                SectionLabel("Mises à jour")
                UpdateSection()
                ListItemRow(Icons.Outlined.NewReleases, "Historique des versions", subtitle = "Nouveautés de chaque version", onClick = { open(RELEASES_URL) })
                SectionLabel("Liens officiels")
                ListItemRow(Icons.Outlined.Code, "Dépôt GitHub", subtitle = REPO_URL.removePrefix("https://"), onClick = { open(REPO_URL) })
                ListItemRow(Icons.Outlined.Language, "Site Thousand Labs", subtitle = SITE_URL.removePrefix("https://"), onClick = { open(SITE_URL) })
                SectionLabel("Confidentialité")
                Text(
                    "Vos musiques, playlists, favoris et statistiques d'écoute restent uniquement sur votre téléphone. Tone Dial ne contient aucune publicité. La connexion Internet sert uniquement à vérifier les mises à jour sur GitHub (désactivable ci-dessus) et à chercher les paroles sur LRCLIB : seuls le titre et l'artiste du morceau sont envoyés.",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TdColors.TextDim,
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// Mises à jour intégrées
// ------------------------------------------------------------------

/**
 * Lance la mise à jour, en demandant d'abord (une seule fois) l'autorisation
 * d'installer des applications. Reprend tout seul au retour des paramètres.
 */
@Composable
fun rememberUpdateLauncher(): () -> Unit {
    val context = LocalContext.current
    var askPermission by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && pending && Updater.canInstall(context)) {
                pending = false
                Updater.downloadAndInstall(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    if (askPermission) {
        AlertDialog(
            onDismissRequest = { askPermission = false },
            title = { Text("Une autorisation, une seule fois") },
            text = {
                Text("Pour se mettre à jour toute seule, Tone Dial doit être autorisée à installer des applications. Sur l'écran suivant, activez « Autoriser depuis cette source », puis revenez : la mise à jour démarrera automatiquement.")
            },
            confirmButton = {
                TextButton(onClick = {
                    askPermission = false
                    pending = true
                    runCatching { context.startActivity(Updater.installPermissionIntent(context)) }
                }) { Text("Continuer") }
            },
            dismissButton = { TextButton(onClick = { askPermission = false }) { Text("Plus tard") } },
            containerColor = TdColors.Surface,
        )
    }

    return {
        if (Updater.canInstall(context)) Updater.downloadAndInstall(context) else askPermission = true
    }
}

/** Bandeau affiché en haut de l'accueil quand une nouvelle version existe. */
@Composable
fun UpdateBanner() {
    val state by Updater.state.collectAsStateWithLifecycle()
    val dismissed by Updater.bannerDismissed.collectAsStateWithLifecycle()
    val launch = rememberUpdateLauncher()

    val release: Updater.State.Available? = when (val s = state) {
        is Updater.State.Available -> s
        is Updater.State.Downloading -> s.release
        is Updater.State.Installing -> s.release
        is Updater.State.Error -> s.release
        else -> null
    }
    if (release == null || (dismissed && state is Updater.State.Available)) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(TdColors.SurfaceHigh)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SystemUpdate, null, tint = TdColors.Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Version ${release.versionName} disponible", style = MaterialTheme.typography.titleMedium)
                val sub = when (val s = state) {
                    is Updater.State.Downloading -> "Téléchargement… ${(s.progress * 100).toInt()} %"
                    is Updater.State.Installing -> "Confirmez l'installation dans la fenêtre d'Android"
                    is Updater.State.Error -> s.message
                    else -> release.notes.lineSequence().firstOrNull { it.isNotBlank() } ?: "Améliorations et corrections"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim, maxLines = 2)
            }
            when (state) {
                is Updater.State.Downloading, is Updater.State.Installing -> Unit
                is Updater.State.Error -> TextButton(onClick = launch) {
                    Text(if ((state as Updater.State.Error).retryInstall) "Relancer l'installation" else "Réessayer")
                }
                else -> {
                    TextButton(onClick = launch) { Text("Mettre à jour") }
                    IconButton(onClick = { Updater.dismissBanner() }) {
                        Icon(Icons.Outlined.Close, "Plus tard", tint = TdColors.TextDim, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        val s = state
        if (s is Updater.State.Downloading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { s.progress },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                color = TdColors.Accent,
                trackColor = TdColors.Divider,
            )
        }
    }
}

/** Bloc « Mises à jour » de l'écran À propos. */
@Composable
fun UpdateSection() {
    val state by Updater.state.collectAsStateWithLifecycle()
    val launch = rememberUpdateLauncher()
    var auto by remember { mutableStateOf(Updater.autoEnabled()) }

    val (label, subtitle) = when (val s = state) {
        Updater.State.Idle -> "Rechercher une mise à jour" to "Version actuelle : ${BuildConfig.VERSION_NAME}"
        Updater.State.Checking -> "Recherche en cours…" to "Connexion à GitHub"
        Updater.State.UpToDate -> "Tone Dial est à jour" to "Version ${BuildConfig.VERSION_NAME} · appuyez pour revérifier"
        is Updater.State.Available -> "Installer la version ${s.versionName}" to "Téléchargement et installation en deux appuis"
        is Updater.State.Downloading -> "Téléchargement… ${(s.progress * 100).toInt()} %" to "Version ${s.release.versionName}"
        is Updater.State.Installing -> "Installation…" to "Confirmez dans la fenêtre d'Android"
        is Updater.State.Error -> (if (s.retryInstall) "Relancer l'installation" else "Réessayer") to s.message
    }
    ListItemRow(
        Icons.Outlined.SystemUpdate, label, subtitle = subtitle, tint = TdColors.Accent,
        onClick = {
            when (val s = state) {
                is Updater.State.Available -> launch()
                is Updater.State.Error -> if (s.release != null) launch() else Updater.check()
                Updater.State.Idle, Updater.State.UpToDate -> Updater.check()
                else -> Unit
            }
        },
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Vérifier automatiquement", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Au lancement, en arrière-plan. Sans connexion, rien ne se passe et l'appli s'ouvre normalement.",
                style = MaterialTheme.typography.bodySmall,
                color = TdColors.TextDim,
            )
        }
        Switch(
            checked = auto,
            onCheckedChange = { auto = it; Updater.setAutoEnabled(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = TdColors.Accent, checkedThumbColor = TdColors.Background),
        )
    }
}
