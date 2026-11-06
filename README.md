# Tone Dial

Lecteur de musique Android hors ligne et sans publicité. Une application **Thousand Labs**.

**Télécharger la dernière version : [ToneDial.apk](https://github.com/ThousandLabs/Tone-Dial/releases/latest/download/ToneDial.apk)**

## Fonctions

- Détection automatique de toutes les musiques du téléphone (MP3, FLAC, AAC, WAV, OGG…)
- Onglets Titres, Albums, Artistes, Dossiers et Playlists, dans l'ordre de votre choix, plus une recherche
- Lecture en arrière-plan, notification et commandes sur l'écran verrouillé, écouteurs et Bluetooth
- Lecture aléatoire, répétition (tout / un titre), file d'attente réorganisable par glisser-déposer, même en aléatoire
- Identification des artistes depuis les noms de fichiers (« Artiste - Titre », feat., ft.) sans modifier les fichiers
- Pochette en grand dans la notification, l'écran verrouillé et le centre de contrôle (logo Tone Dial à défaut)
- Sélection multiple (appui long) pour ajouter plusieurs titres à une playlist, à la file ou aux favoris en une fois
- Balayage gauche / droite sur la barre de lecture ou la pochette pour changer de titre
- Favoris et playlists automatiques : les plus écoutés, écoutés récemment, ajoutés récemment
- Playlists personnelles (créer, renommer, changer l'image, supprimer, ajouter ou retirer des titres), avec un écran d'ajout qui masque les titres déjà présents, un tri propre à chaque playlist et une réorganisation par glisser-déposer
- Égaliseur avec préréglages, amplificateur de basses et effet d'espace
- Paroles : intégrées au fichier (MP3, FLAC), fichier `.lrc`, recherche automatique sur LRCLIB, recherche Google et collage, avec défilement synchronisé
- Minuterie de sommeil (durée ou fin du titre), avec baisse progressive du son
- Widget d'écran d'accueil et raccourcis sur l'icône (Aléatoire, Reprendre, Rechercher)
- Dossiers masqués (audios WhatsApp, enregistrements…), correction manuelle des informations sans modifier les fichiers
- Pochettes manquantes recherchées en ligne (Deezer, iTunes, AnimeThemes pour les openings) même avec des noms de fichiers abîmés, ou choisies dans la galerie ; fond du lecteur aux couleurs de la pochette
- Vitesse de lecture de 0,5× à 2× sans changement de tonalité
- Statistiques d'écoute (7 jours, 30 jours, depuis toujours)
- Sauvegarde et restauration des playlists, favoris, paroles et réglages
- Définir un titre comme sonnerie, partager ou supprimer un fichier, informations détaillées
- Reprise automatique de la lecture là où elle s'était arrêtée

## Nouveautés

- **1.13** : nouvelle icône nette dans le style Thousand Labs, onglets de l'accueil réorganisables, image personnalisée pour les playlists, paroles recherchées automatiquement dès le début du morceau et recherche groupée, pochettes acceptées pour les clips plus longs que le titre officiel.
- **1.11** : recherche de pochettes bien plus efficace : Deezer en plus d'iTunes, noms de fichiers abîmés nettoyés, vérification par la durée, openings d'anime (AnimeThemes), faux albums des téléchargeurs détectés, liste des titres sans pochette.
- **1.10** : dossiers masqués (audios WhatsApp…), correction manuelle titre/artiste/album, pochettes en ligne et depuis la galerie, sauvegarde et restauration, raccourcis d'icône, lecteur à glisser vers le bas, suppression de fichiers, vitesse de lecture, fond du lecteur aux couleurs de la pochette, statistiques d'écoute.
- **1.9** : file d'attente en une seule liste entièrement réorganisable (y compris au-dessus du morceau en cours), numéros de position, bouton « En cours », vibration au glisser, file mémorisée dans l'ordre choisi (aléatoire compris) au redémarrage.
- **1.8** : file d'attente en écran complet comme une playlist (en cours, à suivre, titres précédents repliés, enregistrer comme playlist) ; en aléatoire, le morceau choisi est toujours joué en premier.
- **1.7** : identification automatique des artistes (« Artiste - Titre », mentions parasites retirées, artistes invités), pochettes et logo en grand dans la notification et le centre de contrôle, file d'attente réorganisable même en aléatoire, paroles en ligne (LRCLIB, Google, Coller).
- **1.6** : mise à jour plus fiable. Si la fenêtre d'installation est fermée, l'appli propose « Relancer l'installation » sans retélécharger.
- **1.5** : tri des playlists (ordre d'ajout, titre, artiste, album, date de téléchargement, durée, ordre personnalisé), réorganisation par glisser-déposer, tri des Favoris.
- **1.4** : mises à jour intégrées en deux appuis (bandeau, téléchargement dans l'appli, confirmation Android).
- **1.3** : bouton « Ajouter des titres » dans les playlists, qui masque les titres déjà présents pour éviter les doublons, avec recherche et sélection multiple.
- **1.2** : sélection multiple par appui long, bouton retour qui revient à l'onglet Titres, double appui pour quitter, balayage pour changer de titre, fondu de fin de minuterie.
- **1.1** : première version.

## Installation

1. Sur le téléphone, ouvrez le lien de téléchargement ci-dessus.
2. Ouvrez le fichier `ToneDial.apk`. La première fois, Android demande d'autoriser l'installation depuis le navigateur : acceptez.
3. Au premier lancement, autorisez l'accès à la musique.
4. Conseillé sur Tecno, Infinix, Itel et Xiaomi : Paramètres > Applications > Tone Dial > Batterie > « Pas de restriction », pour que la musique ne soit pas coupée en arrière-plan.

## Mises à jour

À partir de la version 1.4, Tone Dial se met à jour toute seule, en deux appuis :

1. Au lancement, l'appli vérifie en arrière-plan si une nouvelle version existe. Si c'est le cas, un bandeau apparaît en haut de l'accueil.
2. Appuyez sur « Mettre à jour » : l'appli télécharge la nouvelle version elle-même.
3. Confirmez dans la fenêtre d'Android.

La première fois, Android demande d'autoriser Tone Dial à installer des applications. Sans connexion, rien ne se passe et l'appli s'ouvre normalement. La vérification automatique peut être désactivée dans Menu > À propos et mises à jour. Les playlists, favoris et réglages sont conservés.

Chaque modification publiée sur ce dépôt produit automatiquement une nouvelle version dans [Releases](https://github.com/ThousandLabs/Tone-Dial/releases).

## Technique

- Kotlin, Jetpack Compose (Material 3), Media3 / ExoPlayer
- Compilation et signature automatiques par GitHub Actions (`.github/workflows/build.yml`)
- Aucune publicité. Internet sert uniquement aux mises à jour (GitHub), à la recherche de paroles (LRCLIB) et de pochettes (Deezer, iTunes, AnimeThemes) ; toutes les données restent sur le téléphone

Site : [thousandlabs.github.io](https://thousandlabs.github.io)
