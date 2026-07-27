# Bucheron : design doc

Mod d'abattage d'arbres pour Minecraft 26.2, Fabric et NeoForge, licence MIT.

État (2026-07-27) : tous les jalons implémentés et validés en jeu, build vert sur les deux loaders. Le statut courant et le reste à faire vivent dans CLAUDE.md.

Références de travail :
- Panda's Falling Trees (`.sources/pandas-falling-trees`, branche `master`, MC 1.21.11, Kotlin, GPL-3.0). Référence conceptuelle uniquement, aucune réutilisation de code.
- `soft-leaves` (projet local, MC 26.1.2) pour la résolution des particules de feuilles.
- Sources vanilla 26.2 décompilées (cache NeoForm) pour la vérification des API.

---

## Vision

Abattre un arbre est un acte physique qui a un poids. L'arbre entier tombe d'un bloc, dans une direction, en accélérant comme une masse qui bascule. Il peut tuer. Il fait pleuvoir des feuilles. Et une fois à terre, il ne rebondit pas : il s'arrête net, parce qu'un tronc de six mètres ne rebondit pas.

Le mod ne cherche pas à rendre le bûcheronnage plus rapide. Il le rend plus lourd et plus dangereux, tout en évitant la corvée de casser trente blocs un par un.

## Périmètre

Dedans :
- Détection de l'arbre à partir de la bûche cassée (bûches connectées et feuilles rattachées).
- Chute animée en rotation autour de la base, physique de pendule, sans rebond.
- Dégâts aux entités traversées pendant la rotation, joueur inclus.
- Particules de feuilles pendant la chute, et éclatement des blocs de feuilles au contact du terrain.
- Coût d'abattage indexé sur la taille de l'arbre : temps de cassage, durabilité de l'outil, épuisement alimentaire. Abattage sans outil possible mais très lent.
- Drops instantanés à l'impact.
- Vignes, cacaoyers et ruches emportés avec l'arbre.

Dedans, sous condition :
- **Survie et aventure uniquement.** En créatif, le mod ne s'active pas du tout : la casse reste vanilla, un bloc à la fois, sans chute ni dégâts. Construire avec des bûches ne doit pas déclencher d'abattage.

Dehors :
- Tronc couché persistant à débiter (écarté explicitement).
- Replantage automatique, coupe rase, mode "récolte", enchantements dédiés.
- Support des arbres modés au-delà de ce que les tags vanilla couvrent (les tags `#minecraft:logs` et `#minecraft:leaves` suffisent pour la majorité).

---

## Détection de l'arbre

Déclenchée à la casse d'une bûche par un joueur, côté serveur.

Deux passes, comme Panda, mais réécrites :

1. **Bûches** : BFS depuis la bûche cassée, voisinage 3x2x3 (les 8 voisins horizontaux sur la même couche et la couche du dessus). Ne descend jamais : un arbre pousse vers le haut, et on évite d'emporter une maison en rondins depuis sa fondation. Plafonné à `maxLogs` (défaut 256) ; au-delà, on abandonne et on laisse la casse vanilla se faire.
2. **Feuilles** : BFS depuis chaque bûche, rayon `maxLeavesRadius` (défaut 7), en s'appuyant sur la propriété `distance` du bloc de feuilles pour ne garder que celles réellement rattachées à ce tronc. Les feuilles `persistent=true` (posées par un joueur) sont ignorées.

Si aucune feuille n'est trouvée, ce n'est pas un arbre : c'est une construction en bûches. On laisse la casse vanilla. C'est le garde-fou principal contre l'effondrement d'une cabane.

3. **Blocs accrochés** : vignes (en descendant), cacaoyers, ruches et nids d'abeilles adjacents aux bûches ou aux feuilles. Ils partent avec l'arbre.

Le résultat est un `TreeShape` : positions des bûches, positions des feuilles, positions des blocs accrochés, drops calculés, pivot, hauteur.

### Ruches et nids

Emporter une ruche revient à la casser. Le comportement vanilla est reproduit **au craquement**, pas à l'impact : les abeilles n'attendent pas que l'arbre touche le sol pour comprendre ce qui se passe.

Au moment de l'abattage, pour chaque ruche ou nid emporté :
- `BeehiveBlockEntity.emptyAllLivingFromHive(player, state, BeeReleaseStatus.EMERGENCY)` : les abeilles jaillissent.
- Les abeilles voisines deviennent hostiles au joueur.
- Advancement `BEE_NEST_DESTROYED` déclenché.
- Burst de `ParticleTypes.FALLING_HONEY` à la position de la ruche.

Exception vanilla à respecter : si l'outil porte un enchantement du tag `PREVENTS_BEE_SPAWNS_WHEN_MINING` (Silk Touch), les abeilles restent dans la ruche et rien de tout cela ne se produit. La ruche est emportée pleine.

Le bloc lui-même est ensuite emporté par la chute comme les autres, et son item drop à l'impact avec le reste.

Piège d'implémentation : on ne peut pas simplement appeler `BeehiveBlock.playerDestroy(...)`, qui droppe l'item immédiatement à la position d'origine. Il faut reproduire ses effets sans son drop. `emptyAllLivingFromHive` est public, mais `angerNearbyBees` est privé : accessor mixin, ou reproduction (les abeilles dans un rayon prennent le joueur pour cible).

**Coût** : ce scan tourne aussi pendant le minage pour calculer le temps de cassage (voir plus bas). Il faut donc un cache par joueur, invalidé au changement de bloc visé.

---

## Chute

### Cinématique

L'arbre est traité comme une tige rigide qui bascule autour de l'arête basse de son tronc, du côté opposé au joueur. C'est un pendule inversé :

```
θ'' = (3g / 2L) · sin θ
```

où `θ` est l'angle depuis la verticale, `L` la hauteur de l'arbre en blocs, `g` la gravité.

Intégration d'Euler semi-implicite, un pas par tick :

```
ω += (3g / (2L)) · sin(θ)
θ += ω
```

Amorçage : l'accélération est proportionnelle à `sin θ`, donc nulle quand l'arbre est droit. Il faut amorcer le mouvement, et cela se fait par la **vitesse angulaire**, pas par l'angle : `θ₀ = 0`, `ω₀ ≈ 0.035 rad/tick`.

C'est à la fois plus juste et visuellement nécessaire. Plus juste, parce qu'un arbre bascule sous l'impulsion de la coupe et non parce qu'il penchait déjà. Nécessaire, parce que tout angle de départ non nul est une saccade visible sur la première image : la version initiale démarrait à 0.15 rad, soit 8,6°, et le décrochage se voyait (corrigé au playtest du 2026-07-27).

Cette approche donne gratuitement deux choses que la courbe de Panda (`cos` mise à l'échelle) n'a pas :
- L'arbre **accélère** en tombant, au lieu de suivre une courbe arbitraire.
- La durée de chute varie en `√(L/g)` : un grand arbre tombe **plus lentement** qu'un jeune, ce qui est à la fois physiquement juste et visuellement majestueux. Aucun code supplémentaire.

Calibration retenue après playtests : `g = 0.022`, soit environ la moitié de la gravité vanilla d'un bloc qui tombe (`FallingBlockEntity.getDefaultGravity()` = 0.04). Un arbre est une masse qui bascule, pas une pierre qui chute, et ce temps supplémentaire est précisément ce qui donne du poids à la chute. Un chêne tombe en 2,3 s environ.

Conséquence à ne jamais oublier en retouchant `g` : les dégâts suivent `v²` et le débit des feuilles suit `v`. Ralentir l'arbre d'un quart réduit la frappe de près de moitié. C'est le revers assumé du principe "la physique produit le gameplay" : tout réglage de la chute déplace l'équilibre des dégâts, et `DAMAGE_PER_SPEED_SQUARED` doit être recalé en conséquence (porté de 22 à 40 lors de ce ralentissement).

### Pas de rebond

Chez Panda, le rebond vient de deux endroits, tous deux supprimés ici :
- Rebond physique de la hitbox, `TreeEntity.kt:96-98` : `deltaMovement *= (1, -0.5, 1)` quand `onGround()`.
- Rebond angulaire de l'animation, `TreeRenderer.kt:50-52` : `bounceAnim` fait dépasser 90° puis remonter.

Chez nous, `θ` est monotone croissant et la simulation s'arrête net à l'impact. Un tronc qui touche le sol ne repart pas en arrière.

### Arrêt

La chute s'arrête au premier des deux événements :
- Une bûche entre en collision avec un bloc solide du monde. L'arbre se fige à cet angle. Cela gère le cas de l'arbre qui tombe contre une colline ou un autre arbre : il s'arrête à mi-course, ce qui est plus intéressant qu'une chute toujours parfaite à 90°.
- `θ` atteint 90°.

Sons : deux événements custom fournis par Pierre, `bucheron:tree_falling` au début de la bascule et `bucheron:tree_down` à l'impact, avec deux variantes chacun (le vanilla tire au sort) et une légère variation de pitch, pour que deux abattages ne sonnent pas identiques.

À l'arrêt : son d'impact, puis l'arbre **reste couché au sol** pendant environ deux secondes (`REST_TICKS`) avant de se transformer en items et de disparaître. Sans cette pause, la chute perd tout son poids : le tronc s'évapore à l'instant où il touche le sol. Décidé après le premier playtest de la chute (2026-07-27).

### Direction

Opposée au joueur, arrondie aux quatre directions cardinales, comme Panda. La grille de blocs reste alignée sur le monde, ce qui simplifie le rendu, les collisions et le balayage.

Un yaw libre serait plus naturel visuellement (un arbre ne tombe pas forcément plein nord) mais complique tout le reste. Voir "Questions ouvertes".

---

## Dégâts

C'est la différence structurelle avec Panda. Chez lui, la rotation n'existe **que dans le renderer client** : le serveur ne fait tomber qu'une hitbox invisible puis lâche les items. Il n'y a rien à heurter.

Ici, la rotation est simulée côté serveur, tick par tick. Chaque bûche a donc une position monde connue à chaque instant, et une vitesse.

**Vitesse d'un bloc** : `v = ω · r`, où `r` est la distance du bloc au pivot. Conséquence directe et voulue : la base ne fait presque rien, le sommet écrase. Se tenir près de la souche est sûr, se tenir à la cime ne l'est pas.

**Dégâts** : proportionnels à l'énergie cinétique, donc à `v²`, plafonnés.

```
damage = min(maxDamage, DAMAGE_COEF · v²)
```

En dessous d'un seuil de vitesse, aucun dégât (évite de blesser en frôlant).

**Cibles** : toutes les entités vivantes, joueur compris, hors créatif et spectateur. Sélecteur vanilla : `EntitySelector.NO_CREATIVE_OR_SPECTATOR.and(EntitySelector.LIVING_ENTITY_STILL_ALIVE)`.

**Détection** : une seule AABB englobant tout le volume balayé par l'arbre sur le tick, une seule requête `level.getEntities(...)`, puis test géométrique par entité contre les bûches proches. Une requête par tick, pas une par bloc.

**Multi-hit** : géré par l'invulnérabilité vanilla (`invulnerableTime`, 10 ticks). Pas de bookkeeping supplémentaire.

**Seules les bûches blessent.** Être balayé par du feuillage ne doit rien coûter.

**Volume balayé, pas position finale.** Chaque bûche est testée sur l'AABB englobant sa position au tick précédent et sa position courante. Sans cela, une cime rapide franchirait plus d'un bloc par tick et passerait à travers une cible sans jamais l'toucher.

Le tick d'atterrissage doit être balayé comme les autres : c'est le plus rapide de toute la chute, donc le plus meurtrier. Une première version de `tick()` sortait avant de l'évaluer.

Calibration de départ (`DAMAGE_PER_SPEED_SQUARED = 22`, plafond 30) : environ 5 cœurs pour la cime d'un chêne, mortel pour un grand arbre. À affiner en jeu.

**Source de dégâts** : type custom `bucheron:falling_tree`, déclaré en datapack (`data/bucheron/damage_type/falling_tree.json`), avec message de mort dédié. `damageSources().fallingBlock()` afficherait "écrasé par un bloc qui tombe", ce qui est faux et casse l'immersion.

---

## Feuilles et particules

Le mod réutilise l'approche déjà écrite dans `soft-leaves` (`LeafParticles.resolve`) : résoudre, côté serveur, la particule de feuille tombante propre au bloc concerné. Cerisier et chêne pâle portent la leur (`UntintedParticleLeavesBlock`), les autres utilisent `TINTED_LEAVES` teintée par la couleur de feuillage du biome. La classe fait une trentaine de lignes et se transpose telle quelle en 26.2.

**Pendant la chute** : chaque bloc de feuilles émet sa particule avec une probabilité par tick proportionnelle à sa vitesse tangentielle. Le sommet de la canopée, qui va vite, crache ; la base, presque immobile, non. La pluie de feuilles suit donc naturellement le mouvement.

Implémenté avec deux garde-fous : un plafond par tick, sinon une grande canopée inonderait les clients de paquets, et un balayage démarrant à un index aléatoire, sinon ce plafond favoriserait systématiquement les mêmes feuilles. En dessous d'une vitesse plancher, aucune feuille ne tombe : l'effet monte donc naturellement en intensité au fil de la chute.

**La traînée doit rester très discrète.** Réduite trois fois de suite au playtest du 2026-07-27, jusqu'à un huitième de sa densité initiale (probabilité 1.2 → 0.4 → 0.15, plafond 16 → 6 → 3 par tick). Toute la charge visuelle appartient à l'explosion du contact ; une traînée fournie noie cet événement au lieu de l'annoncer. Si elle devait encore diminuer, le levier suivant n'est plus le débit mais `MIN_SHED_SPEED`, qui réserverait l'effet à la toute fin de la chute.

**Les feuilles sont projetées, pas lâchées.** La fréquence seule ne suffit pas : il faut que chaque particule parte dans la direction du mouvement du bloc, à une vitesse proportionnelle. C'est la vitesse tangentielle `ω × r` (produit vectoriel de l'axe de rotation par le rayon), la même grandeur qui sert aux dégâts.

**La composante descendante est réfléchie en portance, pas conservée.** Analyse faite après deux playtests (2026-07-27) :

La tangente change d'orientation pendant la chute. Arbre presque vertical, elle est horizontale, vers l'avant ; arbre à plat, elle est verticale, vers le bas. Comme la fréquence d'émission suit la vitesse, l'essentiel des feuilles sort en fin de chute, donc précisément quand la tangente plonge vers le sol. Une portance simplement *ajoutée* en `+Y` ne fait alors qu'atténuer une vitesse descendante sans l'inverser, et les feuilles restent plaquées au sol.

Physiquement, une feuille ne suit pas la branche dans le sol : la canopée pousse un mur d'air devant elle, cet air s'échappe vers le haut en étant comprimé contre le sol, et emporte la feuille. On garde donc la composante horizontale telle quelle, et on **réfléchit** la composante verticale descendante (`Math.abs(v.y) * UPWARD_FLING`). Les feuilles situées de l'autre côté du pivot montent déjà, et la valeur absolue préserve leur portance.

L'arc vient ensuite gratuitement du moteur de particules : `v = v * friction - gravity` amortit exponentiellement l'élan initial, ce qui donne une montée vive, un apex, puis une descente lente. Cette asymétrie est ce qui se lit comme une feuille et non comme un caillou lancé.

Deux pièges vanilla, tous deux déjà rencontrés et résolus dans `soft-leaves` :
- `sendParticles` n'interprète ses trois derniers arguments comme une **vélocité** que si `count = 0`. Avec un `count` positif, ce sont des offsets aléatoires, et les feuilles restent suspendues là où elles sont nées. C'est ce qui rendait la première version inerte.
- Les providers de particules de feuilles **jettent la vélocité reçue**, et la particule a une friction de 1.0 et une gravité quasi nulle. Il faut donc la réinjecter côté client (`LeafFling` + un accessor sur `Particle` pour la gravité et la friction), via un mixin `@Inject(at = RETURN)` sur chacun des trois providers : `FallingLeavesParticle.TintedLeavesProvider`, `.CherryProvider` et `.PaleOakProvider`.

**Au contact du terrain** : quand un bloc de feuilles de l'arbre en rotation entre dans un bloc solide, il ne traverse pas. Il est retiré de l'arbre et éclate en un burst de sa propre particule. La canopée se désintègre progressivement en balayant le sol.

**C'est ici que se joue l'essentiel de l'effet**, pas dans la traînée en vol. Le contact est l'événement visible : une canopée qui balaie le sol se pulvérise. La traînée en l'air n'est qu'un indice de mouvement et doit rester discrète, sinon les deux se noient l'un dans l'autre (constaté au playtest du 2026-07-27, la traînée a été divisée par trois à cette occasion).

Deux points d'implémentation :
- La détection tourne **des deux côtés**. Le calcul est identique et le client connaît les blocs, donc la canopée se défait de la même façon chez lui sans aucun paquet. Seul le burst de particules est émis par le serveur.
- Le burst part en **un seul paquet** (`sendParticles` avec `count > 0`), ce qui disperse les particules aléatoirement. C'est précisément le bon comportement pour quelque chose qui éclate dans toutes les directions, et ça permet d'être généreux sur le nombre sans coût réseau.
- Le test porte sur la **forme de collision**, pas sur "n'est pas de l'air", sinon l'herbe haute et les fleurs déchiquetteraient la canopée.
- Le burst n'a lieu qu'**au moment où l'arbre se couche**, pas pendant la bascule. Étalé sur la chute, il se lisait comme une désintégration lente ; l'impact est l'événement.
- Le burst doit se lire comme une **explosion**, donc la vitesse d'éjection (`BURST_SPEED`) doit être franche. Trop faible, chaque feuille conserve son comportement propre de dérive vers le bas et le burst entier semble partir dans une seule direction au lieu d'éclater. C'est ce paramètre, et non le nombre de particules, qui produit l'effet.
- Les directions couvrent l'**hémisphère supérieur**, jamais la sphère entière : le sol est juste là, une feuille projetée vers le bas n'a aucun sens. Cela impose une vélocité par particule, donc un paquet chacune (`count = 0` est la seule forme qui transporte une vélocité), mais comme le burst n'arrive qu'une fois par arbre au lieu de chaque tick, le coût est ponctuel.
- Le contact joue aussi le **son de casse du bloc de feuilles**, sans rien casser dans le monde : ces feuilles appartiennent à l'arbre qui tombe, pas au terrain. Le son vient du `SoundType` du bloc, donc il suit automatiquement l'essence, cerisier compris.

### Fusion des sons d'impact

L'éclatement n'a lieu qu'une fois par arbre, au moment où il se couche. Les impacts tombant dans la même cellule d'une grille de 3 blocs sont **fusionnés en un seul son**, joué au centre de masse des impacts qu'il représente, avec un volume qui croît avec leur nombre puis sature.

Fusionner par localité plutôt que plafonner par tick n'est pas qu'une optimisation, c'est plus juste : un plafond fait taire les sons selon l'ordre d'itération, si bien que deux impacts aux extrémités opposées d'un grand arbre peuvent s'annuler l'un l'autre alors que deux impacts voisins se cumulent. La fusion spatiale préserve au contraire la répartition, et un craquement parcourt l'arbre au lieu de claquer d'un bloc.

Les particules, elles, ne sont pas fusionnées : le burst doit se produire exactement là où les feuilles se trouvaient.

---

## Coût de l'abattage

Panda a déjà le temps de cassage et la durabilité indexés sur la taille (`GenericTree.kt:111-118`). Ce n'est donc pas une nouveauté, mais le mod doit les avoir, avec ses propres courbes.

**Temps de cassage** : mixin sur `BlockBehaviour.getDestroyProgress(BlockState, Player, BlockGetter, BlockPos)`, injecté en RETURN pour que le progrès vanilla (outil et enchantements compris) soit calculé d'abord, puis divisé.

Le facteur est le **nombre de bûches**, donc strictement proportionnel à la taille. Le temps total est ainsi identique au vanilla : ce que le mod supprime, c'est la corvée, pas l'effort. Un chêne de six bûches coûte six fois le temps d'une bûche.

À cela s'ajoute un **×1.5 sans outil adapté**. La détection passe par la vitesse de destruction de l'outil sur le bloc (vanilla donne 1.0 pour les mains nues et pour tout ce qui est inadapté, davantage pour un outil qui mord), plutôt que par un tag : n'importe quelle hache modée compte, à condition d'être réellement efficace.

Le facteur est resté modéré volontairement. Il se multiplie à un facteur qui vaut déjà le nombre de bûches, donc il se cumule vite : un ×3 rendait un simple chêne insupportable à mains nues (playtest du 2026-07-27, réduit de 3 à 1.5). Le vanilla pénalise déjà les mains nues par la vitesse de base ; ce coefficient ne fait que s'ajouter par-dessus, il n'a pas à porter seul la punition.

Pas de plafond pour l'instant : la proportionnalité est respectée à la lettre. Conséquence à surveiller en jeu, un jungle géant de plus de cent bûches devient très long, et le mod ne laisse aucune échappatoire puisque casser une seule bûche déclenche l'abattage complet.

Piège : `getDestroyProgress` est évalué côté client pour l'animation de fissuration **et** côté serveur pour la validation. Les deux doivent produire le même résultat, sinon désynchronisation (le bloc casse côté client mais pas serveur). Le scan doit donc être déterministe et disponible des deux côtés, avec le même cache.

**Durabilité** : deux points par bûche emportée, appliqués à l'abattage via `hurtAndBreak`. Un chêne de 6 bûches coûte 12 points, un jungle géant peut détruire une hache. C'est le prix de l'abattage en un coup (relevé de 1 à 2 au playtest du 2026-07-27).

**Rendement en bois** : seule la moitié des bûches donne réellement du bois (`LOG_YIELD = 0.5`, au moins une). Abattre l'arbre entier d'un geste ne doit pas être plus rentable que de le miner bloc par bloc, sans quoi le mod devient une amélioration pure plutôt qu'un changement de rythme : le confort se paie en bois perdu.

Important : la réduction ne porte que sur les **drops**, jamais sur les pièces de l'entité. L'arbre doit tomber visuellement intact, ce sont deux choses distinctes dans le code. Les feuilles, elles, gardent leur rendement vanilla (pommes, pousses, bâtons).

**Épuisement alimentaire** : proportionnel lui aussi.

**Sans outil** : c'est la vraie différence avec Panda, qui refuse purement et simplement l'abattage sans la bonne hache (`requireTool` binaire, on casse juste le bloc visé). Ici l'abattage à mains nues est possible, mais le facteur de lenteur est multiplié (ordre de grandeur x5, à calibrer). Abattre un chêne à mains nues doit être une décision regrettable, pas une impossibilité.

---

## Architecture technique

Template multi-loader identique à `wild-instincts` et `contagious-fire` : `build-logic`, `common`, `fabric`, `neoforge`. Java 25, NeoForge 26.2.0.25-beta, Fabric API 0.155.2+26.2.

```
common/
  BucheronInit.java                 init commun
  Constants.java                    MOD_ID + logger
  LeafParticles.java                repris de soft-leaves
  BucheronSounds.java               événements sonores custom
  tree/TreeScanner.java             BFS bûches + feuilles
  tree/TreeShape.java               record : origin, logs, leaves
  tree/TreeFelling.java             abattage : drops, coûts, spawn de l'entité
  tree/TreeMiningSpeed.java         temps de cassage, cache partagé client/serveur
  entity/FallingTreeEntity.java     état, pendule (intégré en ligne), géométrie
  entity/BucheronEntities.java      EntityType construit ici, enregistré par loader
  damage/TreeSweep.java             balayage et application des dégâts
  damage/BucheronDamageTypes.java   clé du damage type datapack
  fx/LeafEffects.java               traînée, éclatement, sons de craquement fusionnés
  client/FallingTreeRenderer.java   rendu multi-blocs
  client/FallingTreeRenderState.java
  client/BucheronClientNetwork.java application de la forme reçue, côté client
  client/LeafFling.java             réinjection de la vélocité des particules
  network/TreeShapePayload.java     synchro de la forme de l'arbre
  mixin/ServerPlayerGameModeMixin   interception de l'abattage
  mixin/BlockBehaviourMixin         temps de cassage
  mixin/client/...                  providers de particules de feuilles + accessors
  platform/IPlatformHelper          services par loader
fabric/    entrypoints + registres + impl platform
neoforge/  entrypoints + registres + impl platform
```

Le pendule n'a pas eu la classe dédiée envisagée au départ (`TreeFallPhysics`) :
l'intégration tient en trois lignes dans le tick de l'entité, une classe n'aurait
rien apporté.

### Point d'interception de l'abattage

`ServerPlayerGameMode.destroyBlock(BlockPos)`, injection en HEAD annulable. La méthode existe en vanilla sur les deux loaders. Attention : sur NeoForge son corps est patché (`CommonHooks.fireBlockBreak` en tête), donc viser HEAD et surtout pas une ligne interne, sinon le mixin ne s'appliquera pas sur Fabric.

Première condition de sortie du mixin, avant tout scan : le mode de jeu. Créatif et spectateur passent la main au vanilla immédiatement. `ServerPlayerGameMode` expose déjà `gameModeForPlayer` (utilisé en interne par `blockActionRestricted`), accessible via `@Shadow`.

Le mixin de temps de cassage (`getDestroyProgress`) doit appliquer la même condition, sinon un joueur créatif verrait une animation de minage ralentie sur un bloc qu'il casse instantanément.

### Synchronisation client

Panda passe par un `EntityDataSerializer` custom pour envoyer la map de blocs. **Cette voie est fermée** : en 26.2, `EntityDataSerializers.registerSerializer` lève explicitement une exception pour les mods, et NeoForge impose son propre registre (`NeoForgeRegistries.ENTITY_DATA_SERIALIZERS`) que Fabric n'a pas.

À la place : payload réseau custom S2C envoyé aux joueurs qui trackent l'entité, via le mécanisme vanilla `ServerChunkCache.sendToTrackingPlayers(entity, new ClientboundCustomPayloadPacket(payload))`. Les destinataires sont ainsi exactement ceux du paquet de spawn ; seul l'enregistrement du payload (décodage et handler) reste par loader. La forme de l'arbre est envoyée une fois à l'apparition ; le client rejoue ensuite la même intégration de pendule localement, donc rien d'autre ne transite.

### Rendu

`SubmitNodeCollector.submitBlock` utilisé par Panda **n'existe plus en 26.2**. Le vanilla passe désormais par `submitNodeCollector.submitMovingBlock(poseStack, MovingBlockRenderState, outlineColor)`, chaque bloc nécessitant un `MovingBlockRenderState` (blockState, blockPos, randomSeedPos, biome, cardinalLighting, lightEngine). Voir `FallingBlockRenderer` 26.2.

Conséquence : pour un arbre de 100 blocs il faut 100 `MovingBlockRenderState`, reconstruits à chaque frame dans `extractRenderState`. La préallocation envisagée initialement est impossible : `EntityRenderer.createRenderState(entity, partialTick)` recrée le render state entier à chaque frame (vérifié dans les sources 26.2), et le `FallingBlockRenderer` vanilla alloue de la même façon. À l'échelle d'une chute de trois secondes, le coût est négligeable.

**Un renderer est obligatoire dès qu'une entité existe**, même quand il n'y a rien à dessiner. Sans renderer enregistré, `EntityRenderDispatcher.shouldRender` déréférence un renderer nul et le client crashe à la seconde où l'entité entre dans le champ de vision. Découvert en jeu à M1, où l'entité était censée rester invisible : un `FallingTreeRenderer` qui ne dessine rien a été ajouté. Il n'existe pas d'état « entité sans rendu ».

Le renderer applique une seule rotation (translation vers le pivot, rotation de `θ`, translation inverse) puis dessine chaque bloc à sa position relative.

---

## Vérifié dans le vanilla 26.2

Établi en lisant les sources décompilées, pas de mémoire :

| Élément | État |
| --- | --- |
| `ParticleTypes.TINTED_LEAVES`, `CHERRY_LEAVES`, `PALE_OAK_LEAVES` | présents |
| `TintedParticleLeavesBlock`, `UntintedParticleLeavesBlock` | présents, `LeafParticles` transposable |
| `BlockBehaviour.getDestroyProgress(BlockState, Player, BlockGetter, BlockPos)` | présent, signature inchangée |
| `ServerPlayerGameMode.destroyBlock(BlockPos)` et `Block.playerWillDestroy(...)` | présents |
| `SubmitNodeCollector.submitBlock` | **supprimé**, remplacé par `submitMovingBlock` |
| `EntityDataSerializers.registerSerializer` | **fermé aux mods**, lève une exception |
| `FallingBlockEntity` : `applyGravity()`, `getDefaultGravity()` = 0.04, `EntitySelector.NO_CREATIVE_OR_SPECTATOR` | présents, modèle de référence |
| `ParticleTypes.FALLING_HONEY`, `DRIPPING_HONEY`, `LANDING_HONEY`, `FALLING_NECTAR` | présents |
| `GameType.isSurvival()` | vaut `SURVIVAL || ADVENTURE`, exactement le périmètre voulu |
| `Block.getDrops(state, level, pos, blockEntity, breaker, tool)` | le dernier paramètre est un `ItemInstance` en 26.x, un `ItemStack` y passe |
| `EntityRenderer<T, EntityRenderState>` + `createRenderState()` | `EntityRenderState` est concret, utilisable tel quel pour un renderer sans état propre |
| `FMLEnvironment.dist` (NeoForge) | **supprimé** sur le loader de la 26.2, remplacé par `FMLEnvironment.getDist()` |
| `BeehiveBlockEntity.emptyAllLivingFromHive(player, state, BeeReleaseStatus)` | public, appelable |
| `BeehiveBlock.angerNearbyBees(level, pos)` | **privé**, accessor mixin ou reproduction |
| `EnchantmentTags.PREVENTS_BEE_SPAWNS_WHEN_MINING` | présent, condition vanilla à respecter |
| `CriteriaTriggers.BEE_NEST_DESTROYED` | présent |
| `ServerChunkCache.sendToTrackingPlayers(Entity, Packet)` | présent, public, cible les trackers exacts |
| `ClientboundCustomPayloadPacket` | record public, enveloppe un `CustomPacketPayload` |
| `EntityRenderer.createRenderState(entity, partialTick)` | recrée le render state à chaque frame, préallocation impossible |

Réserve : les sources lues proviennent du cache NeoForm, donc **patchées NeoForge** (`EventHooks`, `@OnlyIn`, extensions `IFallable`). Le module `common` doit s'en tenir strictement aux API vanilla. À revalider sur Fabric au premier build.

Résolu à M1, l'enregistrement d'un `EntityType` custom dans ce template : le type est **construit** dans `common` (`BucheronEntities`, via `EntityType.Builder.of(...).build(key)`) et **enregistré par chaque loader** sur le même objet. Fabric appelle `Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type)` ; NeoForge passe par `DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID)` avec un supplier vers ce même champ. Le type est `noSave()` : une chute dure une seconde, une chute restaurée depuis le disque serait pire que pas d'arbre du tout.

Reste à vérifier au moment d'implémenter : la déclaration d'un damage type custom côté datapack.

---

## Ordre de construction

Une seule release à la fin ; les jalons servent au développement.

- **M0** : scaffolding depuis le template 26.2, mod vide qui build sur les deux loaders. **Fait.**
- **M1** : scan + abattage. L'arbre disparaît, une entité tombe tout droit sans rotation, drops à l'impact. Vérifie le hook et le scan. **Fait, build vert, pas encore testé en jeu.**
- **M2** : rotation pendulaire serveur, payload réseau, renderer multi-blocs, arrêt net sans rebond. Le gros morceau. **Écrit, build vert, en attente de validation visuelle.**

Ajustements par rapport au découpage initial :
- Le **payload réseau** passe de M1 à M2. Tant qu'aucun renderer ne consomme la forme de l'arbre, l'envoyer ne vérifie rien et n'est que du code mort.
- Les **blocs accrochés** (vignes, cacaoyers, ruches) sont scannés à M4, avec le traitement des ruches. M1 ne ramasse que bûches et feuilles.
- Un **renderer minimal** a dû être ajouté dès M1, sans quoi le client crashe (voir la section Rendu).
- L'**arrêt sur obstacle** passe de M2 à M3. Il repose sur la position monde de chaque bûche à chaque tick, exactement la machinerie que le balayage de dégâts introduit. M2 s'arrête à 90°.

### Synchronisation retenue à M2

Le client reçoit la forme **une seule fois**, au tick 1 de l'entité (un tick après le paquet de spawn, pour que l'entité existe déjà côté client), envoyée aux joueurs qui trackent l'entité via `ServerChunkCache.sendToTrackingPlayers`. Aucun paquet ensuite : les deux côtés intègrent le même pendule depuis le même angle de départ, et la hauteur servant de longueur de pendule est recalculée depuis les pièces des deux côtés, ce qui interdit toute divergence.

Cela évite les hooks de tracking par loader (`EntityTrackingEvents` côté Fabric, `PlayerEvent.StartTracking` côté NeoForge) au prix d'un cas non couvert : un joueur qui arrive à portée en cours de chute ne verra pas l'arbre. Sur une seconde de chute, c'est acceptable.

Une première version envoyait la forme dans un rayon fixe de 96 blocs, inférieur au
`clientTrackingRange` de l'entité (10 chunks, 160 blocs) : un joueur entre les deux
recevait le spawn mais jamais la forme, donc un arbre invisible. Passer par les
trackers vanilla fait coïncider les deux ensembles par construction.

Le rendu interpole entre l'angle du tick précédent et l'angle courant, sinon l'animation serait saccadée à 20 images par seconde.
- **M3** : balayage de dégâts et damage type custom. **Balayage écrit, build vert.** Restent l'arrêt sur obstacle et l'éclatement des feuilles au contact du terrain.
- **M4** : particules de feuilles, traînée et éclatement au contact du terrain.
- **M5** : coût d'abattage (temps de cassage, durabilité, épuisement, mains nues).
- **M6** : config, tags, sons, pipeline de release.

Vérification à chaque jalon : build des deux loaders, puis test en jeu. Les jalons M2 à M4 ne sont validables qu'à l'œil, en jeu.

---

## Questions ouvertes

1. **Direction de chute** : quatre cardinales (recommandé, simple, aligné grille) ou yaw libre (plus naturel, complique rendu, collisions et balayage) ?
2. **Courbe de dégâts** : `v²` (recommandé, contraste fort entre souche et cime) ou `v` (plus plat, plus prévisible) ?
3. **Plafond de taille** : 256 bûches par défaut. Au-delà, faut-il prévenir le joueur ou échouer en silence ?

Tranché :
- Mode de jeu : survie et aventure uniquement, le créatif est intouché.
- Blocs accrochés : vignes, cacaoyers et ruches emportés. Casser un arbre avec une ruche revient à casser la ruche, effets appliqués au craquement.
