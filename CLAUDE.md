# Bucheron

Mod Minecraft (26.2, Fabric + NeoForge, modid `bucheron`, MIT) : abattage d'arbre
entier, immersif. L'arbre bascule sous son poids, écrase ce qui se trouve sur son
passage, projette ses feuilles, et ne rebondit pas. Auteur : Payangar.

## Source de vérité

- `DESIGN.md` : design doc complet. Le lire avant tout travail. Les décisions de
  playtest y sont datées, la section "Vérifié dans le vanilla 26.2" liste les API
  effectivement constatées dans les sources décompilées (et non supposées).
- Référence conceptuelle : Panda's Falling Trees (`.sources/pandas-falling-trees`,
  MC 1.21.11, Kotlin, **GPL-3.0**). Architecture seulement, jamais de code repris.

## Statut (2026-07-27)

Toute la demande initiale est couverte, build vert deux loaders, chaque point
validé en jeu par Pierre au fil de la journée :

- abattage par mixin sur `ServerPlayerGameMode.destroyBlock`, scan bûches + feuilles ;
- chute en pendule inversé simulée serveur et rejouée client, départ vertical
  amorcé par une vitesse angulaire, aucun rebond, arbre couché 2 s avant les drops ;
- dégâts par balayage en `v²` avec damage type et message de mort dédiés ;
- feuilles projetées pendant la chute (portance réfléchie), puis explosion en
  hémisphère supérieur au contact, craquements de feuilles fusionnés par zone ;
- sons custom `tree_falling` / `tree_down`, deux variantes chacun ;
- coût d'abattage : usure et épuisement proportionnels au nombre de bûches, temps
  de cassage proportionnel à la taille, x1.5 sans outil adapté (cache partagé
  client/serveur).

Versionné sur https://github.com/payangar-dev/bucheron (public). Pipeline de
release en place : un tag `v*` build les deux loaders, crée la Release GitHub et
publie sur Modrinth et CurseForge. Secrets `MODRINTH_TOKEN` / `CURSEFORGE_TOKEN`
configurés ; il manque les variables de dépôt `MODRINTH_ID` et `CURSEFORGE_ID`,
que Pierre fournira, sans quoi la publication échouera en 404.

Reste : arrêt de l'arbre sur obstacle (il traverse le terrain aujourd'hui),
vignes / cacaoyers / ruches emportés (abeilles libérées et miel au craquement),
config, gametests.

Questions ouvertes : yaw libre plutôt que quatre cardinales, plafond éventuel sur
le temps de cassage d'un arbre géant (aucun aujourd'hui, la proportionnalité est
respectée à la lettre), comportement au-delà du plafond de 256 bûches.

## Règles structurantes (détail dans DESIGN.md)

- **Survie et aventure uniquement.** Le créatif ne doit rien déclencher, ni chute,
  ni ralentissement de minage.
- **La physique produit le gameplay, pas des règles.** Le pendule donne l'accélération
  et le fait qu'un grand arbre tombe plus lentement ; `v = ω × r` donne à lui seul
  "la cime tue, la souche non". Ne pas remplacer ces intégrations par des courbes
  arbitraires ou des cas particuliers.
- **Déterminisme client/serveur.** Le client ne reçoit la forme qu'une fois puis
  rejoue la même intégration. Toute grandeur qui alimente le pendule doit être
  dérivée des pièces des deux côtés, jamais transmise ni supposée.
- **Une entité exige toujours un renderer**, même vide : sans lui le client crashe
  dès qu'elle entre dans le champ de vision.
- **Particules de feuilles** : `sendParticles` avec `count = 0` (sinon les trois
  derniers arguments sont des offsets, pas une vélocité), et la vélocité doit être
  réinjectée côté client car les providers vanilla la jettent.
- Vérifier chaque API dans les sources décompilées 26.2 avant de l'utiliser. La
  mémoire du modèle est périmée sur cette version, et le cache NeoForm est patché
  NeoForge : le module `common` doit s'en tenir au vanilla.
