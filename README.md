# Tone Dial

Lecteur de musique Android hors ligne et sans publicité. Une application **Thousand Labs**.

**Télécharger la dernière version : [ToneDial.apk](https://github.com/ThousandLabs/Tone-Dial/releases/latest/download/ToneDial.apk)**

## Fonctions

- Détection automatique de toutes les musiques du téléphone (MP3, FLAC, AAC, WAV, OGG…)
- Onglets Titres, Albums, Artistes, Dossiers et Playlists, plus une recherche
- Lecture en arrière-plan, notification et commandes sur l'écran verrouillé, écouteurs et Bluetooth
- Lecture aléatoire, répétition (tout / un titre), file d'attente modifiable
- Sélection multiple (appui long) pour ajouter plusieurs titres à une playlist, à la file ou aux favoris en une fois
- Balayage gauche / droite sur la barre de lecture ou la pochette pour changer de titre
- Favoris et playlists automatiques : les plus écoutés, écoutés récemment, ajoutés récemment
- Playlists personnelles (créer, renommer, supprimer, ajouter ou retirer des titres), avec un écran d'ajout qui masque les titres déjà présents, un tri propre à chaque playlist et une réorganisation par glisser-déposer
- Égaliseur avec préréglages, amplificateur de basses et effet d'espace
- Paroles hors ligne : intégrées au fichier (MP3, FLAC), fichier `.lrc`, ou saisies dans l'appli, avec défilement synchronisé
- Minuterie de sommeil (durée ou fin du titre), avec baisse progressive du son
- Widget d'écran d'accueil
- Définir un titre comme sonnerie, partager un fichier, informations détaillées
- Reprise automatique de la lecture là où elle s'était arrêtée

## Nouveautés

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
- Aucune publicité. Internet sert uniquement aux mises à jour (GitHub) ; toutes les données restent sur le téléphone

Site : [thousandlabs.github.io](https://thousandlabs.github.io)
