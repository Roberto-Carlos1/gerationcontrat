# Pacte — Registre des contrats

Frontend **HTML/CSS/JavaScript**, backend **Java 21+ / Spring Boot 4.0.6**,
base relationnelle **PostgreSQL 16**.

L’application conserve les contrats fournisseurs, le tableau de bord, les échéances,
les litiges avec historique, l’analyse IA, l’import PDF, l’impression, les sauvegardes
JSON et l’export CSV.

## Démarrage avec Docker

Prérequis : Docker et Docker Compose.
Depuis la racine du projet :

```bash
cp .env.example .env
# Choisir DATABASE_PASSWORD dans .env avant le premier lancement.
docker compose up --build -d
```

Ouvrir **http://127.0.0.1:8080**. La première construction télécharge les images
et les dépendances Maven. PostgreSQL est initialisé automatiquement ; cinq contrats
fictifs sont ajoutés uniquement lors de l’initialisation d’un registre neuf.
Pour démarrer sans exemples, mettre `PACTE_DEMO_ENABLED=false` dans `.env`.

```bash
docker compose logs -f app
docker compose down
```

`down` conserve les données dans le volume `pacte_postgres`.
Ne pas ajouter `-v` si vous souhaitez conserver la base.
Les ports 8080 et 5432 sont accessibles uniquement depuis la machine locale.
Si un port est occupé, modifier `SERVER_PORT` ou `DATABASE_PORT` dans `.env`.
Changer le mot de passe dans `.env` après l’initialisation ne change pas celui du
rôle PostgreSQL existant : modifier également le rôle dans PostgreSQL.

## Démarrage Java sans conteneur applicatif

Prérequis : JDK 21 ou supérieur, Maven 3.6.3+ et PostgreSQL 16+.
Le projet est compilé avec une cible Java 21 ; il a été vérifié ici avec Java 25.

Pour utiliser uniquement PostgreSQL via Docker :

```bash
cp .env.example .env
# Choisir DATABASE_PASSWORD dans .env.
docker compose up -d db
set -a
. ./.env
set +a
mvn -f backend/pom.xml spring-boot:run
```

Pour une installation PostgreSQL existante, créer une base et un rôle dédiés,
puis définir `DATABASE_URL`, `DATABASE_USER` et `DATABASE_PASSWORD` dans `.env`.
`DATABASE_URL` doit être une URL JDBC, par exemple
`jdbc:postgresql://127.0.0.1:5432/pacte`.
Adapter aussi cette URL si `DATABASE_PORT` change pour le lancement Java local.
Le schéma est créé au démarrage sans supprimer les données existantes.

Pour produire puis lancer le JAR autonome :

```bash
mvn -f backend/pom.xml package
java -jar backend/target/pacte-2.0.0.jar
```

Les variables d’environnement doivent être définies dans le terminal qui lance Java.
Spring Boot ne charge pas automatiquement le fichier `.env` ; Docker Compose, lui,
le lit automatiquement. L’interface est incluse dans le JAR, sur la même origine que l’API.
Après une modification du frontend, recompiler ou relancer `spring-boot:run`.

## Organisation

```text
frontend/
  index.html                 Page principale
  styles.css                 Apparence responsive et impression
  app.js                     Navigation, formulaires et appels HTTP
backend/
  pom.xml                    Dépendances et compilation Maven
  src/main/java/mg/pacte/
    api/                     Contrôleur REST et réponses d’erreur
    model/                   DTO typés et contraintes Jakarta Validation
    service/                 Validation métier, dates et transactions
    repository/              Accès PostgreSQL par requêtes paramétrées
    config/                  Configuration, initialisation et filtre HTTP
  src/main/resources/
    application.properties   Connexion et options par variables d’environnement
    schema.sql               Schéma relationnel et contraintes PostgreSQL
    seed.json                Exemples de démonstration
  src/test/java/mg/pacte/     Tests unitaires et d’intégration HTTP
scripts/test-postgres.sh     Tests sur un cluster PostgreSQL temporaire
compose.yaml                 Application et PostgreSQL avec volume persistant
Dockerfile                   Construction Maven et exécution Java
```

## API

| Méthode | Route | Fonction |
| --- | --- | --- |
| GET | `/api/state` | Lire `{revision, data}` |
| PUT | `/api/state` | Enregistrer `{revision, data}` dans une transaction |
| GET | `/api/demo` | Lire les exemples pour la réinitialisation |
| GET | `/api/ai/status` | Vérifier si une clé IA est configurée (aucun secret renvoyé) |
| POST | `/api/ai/analyze` | Analyser `{contractId, text}` avec OpenAI |
| POST | `/api/contracts/{id}/documents?filename=contrat.pdf` | Importer le fichier PDF brut (`application/pdf`) |
| GET | `/api/contracts/{id}/documents` | Lister les PDF associés au contrat |
| GET | `/api/documents/{id}` | Télécharger le fichier original |
| GET | `/api/documents/{id}/text` | Lire le texte extrait du PDF |

Les formats JSON restent compatibles avec le frontend et les sauvegardes précédentes.
Le frontend continue à envoyer un instantané complet du petit registre. Le backend
valide l’ensemble, verrouille la révision, puis met à jour les données dans une transaction.
Les contrats sont insérés ou mis à jour pour préserver leurs documents associés.
Deux écritures issues de la même révision ne peuvent pas s’écraser : l’une reçoit
HTTP 409, et l’interface recharge les données pour permettre une nouvelle saisie.
Les lectures utilisent le même verrou pour restituer un instantané cohérent.

## Base de données

| Table | Contenu |
| --- | --- |
| `registry_metadata` | Révision et état d’initialisation |
| `contracts` | Champs typés des contrats, montants décimaux, dates et statuts |
| `disputes` | Litiges reliés aux contrats par clé étrangère |
| `dispute_history` | Historique ordonné de chaque litige |
| `analyses` | Nom du contrat analysé, date affichée, validation et détails IA en JSONB |
| `analysis_groups` | Catégories de clauses d’une analyse |
| `analysis_excerpts` | Extraits ordonnés des clauses |
| `acknowledgements` | Accusés de lecture reliés aux contrats |
| `documents` | PDF original en BYTEA, texte extrait, taille, pages et contrat associé |
| `reminder_deliveries` | Réservations et journal des rappels SMTP, pour limiter les doublons |

Les analyses conservent le nom du contrat au moment de l’analyse, comme les sauvegardes
précédentes. Les dates des contrats sont des `DATE`, les montants des `NUMERIC(15,2)`.
Les identifiants uniques, références, dates et montants ont des contraintes en base.
Les migrations idempotentes V2 (détails IA) et V3 (PDF) sont intégrées au schéma.
Le schéma initial est idempotent ; de futures évolutions de tables devront faire
l’objet de migrations SQL explicites, car `CREATE TABLE IF NOT EXISTS` ne modifie
pas une table existante.

## Règles et corrections conservées

- Dates calendaires sans conversion de minuit en UTC ; serveur en `Indian/Antananarivo`.
- Les contrats actifs dont la fin est passée sont affichés comme terminés.
  Pour une reconduction, vérifier puis modifier explicitement dates et statut :
  aucune nouvelle durée n’est inventée par le logiciel.
- Les alertes précèdent la limite de préavis en cas de reconduction ; sinon, la fin.
  Elles restent visibles lorsqu’elles sont dépassées. « Lu » ne signifie pas « résolu ».
- Le tableau de bord et les alertes se rafraîchissent toutes les minutes sans fermer les formulaires.
- Validation serveur des champs obligatoires, dates, montants, statuts, identifiants,
  références et structures imbriquées. Un import invalide ne modifie aucune table.
- Limite de 2 Mo par écriture JSON et 10 Mo par PDF, y compris sans `Content-Length` ; origine étrangère refusée.
- Identifiants UUID côté frontend et neutralisation des formules dans les exports CSV.

## Migration des anciennes données

L’ancienne base `data/pacte.sqlite3` n’a pas été supprimée.
Un export de précaution est disponible dans `data/legacy-sqlite-export.json`.
Dans la nouvelle application, ouvrir **Données & aide → Restaurer une sauvegarde**
et sélectionner cet export ou une autre sauvegarde JSON valide.
La restauration demande confirmation et remplace le registre dans une transaction.
Aucun import automatique n’écrase la base PostgreSQL.

## Activer l’analyse IA, sans connexion utilisateur

L’intégration utilise l’API Responses d’OpenAI avec une sortie JSON structurée.
Dans `.env`, renseigner une clé API de votre compte, sans l’inscrire dans le frontend :

```dotenv
OPENAI_API_KEY=votre_cle_api
OPENAI_MODEL=gpt-4.1-mini
```

Le modèle est configurable ; choisir un modèle accessible à votre compte et compatible
avec Responses et les sorties structurées. La présence d’une clé ne garantit pas sa validité
ni un quota disponible : les erreurs du fournisseur sont signalées dans l’interface.
Les appels utilisent votre compte API et peuvent être facturés.
Ne pas envoyer la clé dans le chat ni l’ajouter au dépôt Git.

Avec Docker, après la modification de la configuration ou du code :

```bash
docker compose up --build -d app
```

Pour un lancement Maven local, recharger les variables de `.env` puis redémarrer Java.
Dans « Analyse IA du contrat », sélectionner un contrat, importer son PDF ou coller
son texte, puis cliquer sur « Analyser avec l’IA ». Relire la synthèse, les risques,
recommandations et citations avant de valider et enregistrer la fiche.
Les anciennes fiches sans métadonnées IA sont identifiées comme repérage local archivé.

Le texte est transmis à OpenAI uniquement lors du clic d’analyse, via le backend.
La requête utilise `store: false` ; ce paramètre ne constitue pas une garantie d’absence
de toute conservation technique par le fournisseur. Aucun outil ni recherche externe
n’est activé et le modèle n’a aucun accès à la base. La réponse ne modifie pas les contrats.
La clé reste exclusivement dans les variables serveur. Les citations sont vérifiées
par comparaison avec le texte fourni ; cette vérification ne garantit pas que les
interprétations du modèle sont correctes. Une relecture reste nécessaire.
Limites locales : 40 000 caractères par analyse, deux analyses simultanées,
six demandes par minute et délai fournisseur de 60 secondes, sans nouvelle tentative automatique.

Documentation officielle : [Responses et sorties structurées](https://developers.openai.com/api/docs/guides/structured-outputs).

## Importer et conserver les PDF

L’import est disponible dans la fiche d’un contrat et dans l’écran d’analyse IA.
Un fichier fictif est fourni dans `examples/contrat-demo.pdf` pour tester le parcours.
Le fichier original et le texte extrait sont enregistrés dans PostgreSQL.
Les documents sont téléchargeables depuis ces deux écrans ; un document enregistré
peut être réutilisé pour une nouvelle analyse sans nouvel import.
L’import et l’extraction fonctionnent même sans clé API et ne transmettent pas le PDF à OpenAI.

Limites : 10 Mo et 100 pages par fichier, PDF non chiffré et au plus 200 000 caractères
extraits. Un document de plus de 40 000 caractères doit être réduit à un passage
avant l’analyse IA ; aucun passage n’est tronqué silencieusement.
Les PDF scannés sans couche texte peuvent être traités avec le bouton « Reconnaître le texte (OCR) ». L’extraction utilise [Apache PDFBox](https://pdfbox.apache.org/3.0/migration.html).

**Les sauvegardes JSON n’incluent pas les fichiers PDF.** Elles préservent les documents
des contrats dont l’identifiant reste présent. Les PDF des contrats retirés par une
restauration ou une réinitialisation sont supprimés avec ces contrats (clé étrangère).
La sauvegarde ZIP complète est disponible dans « Données & aide ».
Pour une sauvegarde native de PostgreSQL, incluant aussi le journal SMTP :

```bash
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > pacte-complet.dump
```

## Tests

Tests unitaires, sans base :

```bash
mvn -f backend/pom.xml test
node --check frontend/app.js
```

Sans `PACTE_TEST_DATABASE_URL`, les seize tests d’intégration PostgreSQL sont explicitement ignorés.
Les tests du client IA utilisent un serveur HTTP local simulé et ne nécessitent aucune clé API.
Pour exécuter **les 35 tests** sur une vraie base temporaire, avec les exécutables
PostgreSQL locaux (`initdb`, `pg_ctl`, `createdb`, `pg_config`) :

```bash
scripts/test-postgres.sh
```

Ce script crée et détruit uniquement son cluster dans `/tmp`, sur le port 55432,
sans toucher aux bases existantes. Il ne doit pas être lancé en root.
Pour choisir d’autres exécutables ou un port libre :

```bash
PG_BIN=/usr/lib/postgresql/16/bin PACTE_TEST_PORT=55433 scripts/test-postgres.sh
```

Autre possibilité : définir `PACTE_TEST_DATABASE_URL`, `PACTE_TEST_DATABASE_USER`
et `PACTE_TEST_DATABASE_PASSWORD`, puis lancer `mvn -f backend/pom.xml verify`.
Utiliser impérativement une base dédiée aux tests : les tests remplacent son registre.
Ils vérifient la persistance réelle, les validations sans perte de données, la
concurrence, les dates, les analyses et historiques, la taille des requêtes et
l’inaccessibilité des fichiers de configuration. Les tests IA couvrent le format Responses, les citations
absentes du texte, les refus, quotas et réponses invalides. Les tests PDF vérifient l’extraction,
le téléchargement identique à l’original, la conservation après modification, les fichiers protégés
et les documents sans texte. Aucun appel OpenAI réel ni payant n’est effectué par ces tests.
Les rappels SMTP sont testés sur un serveur local, sans destinataire externe.
Le test de reconnaissance OCR réelle est ignoré si `PACTE_TEST_TESSERACT` n’est pas défini ;
pour exécuter tous les tests, installer Tesseract et lancer :

```bash
PACTE_TEST_TESSERACT=/usr/bin/tesseract scripts/test-postgres.sh
```

## Périmètre

Cette version est prévue pour un usage local, sans comptes utilisateurs ni gestion
des droits. L’écoute et les ports Docker sont limités à la machine locale.
L’analyse IA appelle OpenAI lorsque la clé serveur est renseignée. Sans clé, l’interface
affiche que le service n’est pas configuré ; aucun résultat de simulation n’est présenté comme une IA.
Les rappels restent visibles dans l’interface. L’envoi par e-mail fonctionne en arrière-plan lorsque SMTP et les destinataires sont configurés et que le serveur reste démarré.

Documentation du framework : [Spring Boot 4.0](https://docs.spring.io/spring-boot/4.0/index.html).

## OCR local pour les contrats scannés

L’image du PDF reste sur le serveur. Le bouton « Reconnaître le texte (OCR) »
utilise Tesseract puis enregistre le texte reconnu dans PostgreSQL. L’analyse IA
reste une action distincte. Un document de test image est disponible dans
`examples/contrat-scan-demo.pdf`. La reconnaissance peut comporter des erreurs : relire le texte.

L’image Docker installe Tesseract et les langues française/anglaise. Pour un lancement
Java local sous Debian/Ubuntu, installer les paquets suivants avec l’administrateur :

```bash
sudo apt install tesseract-ocr tesseract-ocr-fra tesseract-ocr-eng
```

Options : `TESSERACT_COMMAND` (chemin de l’exécutable, lancement local) et
`OCR_LANGUAGES=fra+eng`. Limites : un traitement simultané, 20 pages scannées,
90 secondes par document et 30 secondes par page. Les pages avec une couche texte
sont conservées ; l’OCR s’applique aux pages sans texte. Il ne récupère pas nécessairement
une image intégrée dans une page qui possède déjà du texte. Aucun texte n’est tronqué
silencieusement et le fichier PDF original reste intact.
Documentation : [Tesseract](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html).

## Rappels automatiques par e-mail

Dans `.env`, renseigner les paramètres du compte SMTP et les adresses :

```dotenv
MAIL_ENABLED=true
MAIL_FROM=pacte@exemple.com
MAIL_TO=responsable@exemple.com
SMTP_HOST=smtp.exemple.com
SMTP_PORT=587
SMTP_USERNAME=pacte@exemple.com
SMTP_PASSWORD=mot_de_passe_application
SMTP_AUTH=true
SMTP_STARTTLS=true
```

Ces adresses sont des exemples à remplacer. Plusieurs destinataires peuvent être séparés
par des virgules. Le mot de passe SMTP reste côté serveur. STARTTLS est requis par défaut.
Pour un relais local de test uniquement, l’authentification et STARTTLS peuvent être désactivés.
La configuration actuelle cible SMTP avec STARTTLS, pas le port SSL implicite 465.
Après modification, relancer l’application. Dans « Données & aide », vérifier l’état SMTP
et l’aperçu des notifications, qui ne déclenche aucun envoi.

Le serveur vérifie les échéances toutes les heures, avec une première vérification
une minute après démarrage. Il envoie un rappel dans la fenêtre J−30 puis J−10 avant
la limite de préavis ou la fin, aux destinataires configurés. Pas de rattrapage des
échéances déjà dépassées. Une table persistante évite les renvois habituels après un
redémarrage. Après une interruption exactement entre l’envoi et sa confirmation en
base, un doublon reste possible ; SMTP ne garantit pas un envoi exactement une fois.
Les rappels nécessitent que l’application reste en fonctionnement.
Documentation : [Spring Mail](https://docs.spring.io/spring-boot/reference/io/email.html).

## Sauvegarde complète depuis l’interface

Dans « Données & aide », utiliser « Télécharger la sauvegarde complète ».
L’archive ZIP inclut contrats, litiges, analyses, accusés de lecture et fichiers PDF
originaux avec leur texte extrait. « Restaurer une sauvegarde complète » vérifie
l’archive puis remplace ces données et les documents dans une seule transaction.
Une erreur de validation ne remplace aucune donnée. La révision protège aussi contre
une restauration issue d’une fenêtre devenue obsolète.
Limites : 50 Mo décompressés et 1 000 documents. Utiliser `pg_dump` pour les bases plus grandes.
Les secrets du fichier `.env` et le journal technique des envois SMTP ne sont pas
inclus. Conserver séparément la configuration du serveur. La sauvegarde JSON simple
reste disponible, mais elle n’inclut pas les fichiers PDF.

API ajoutées : `GET /api/ocr/status`, `POST /api/documents/{id}/ocr`,
`GET /api/reminders/status`, `GET /api/reminders/preview`,
`GET /api/backups/full` et `POST /api/backups/full?revision=N` (corps `application/zip`).

## Vérifier l’IA réelle

Après configuration de `OPENAI_API_KEY` et démarrage de l’application :

```bash
node scripts/test-ai-live.mjs http://127.0.0.1:8080
```

Ce test appelle réellement OpenAI avec un texte fictif et peut être facturé par
le fournisseur. Il n’enregistre aucune fiche et ne transmet aucune donnée du contrat
existant : son identifiant sert uniquement d’association dans l’application.
Sans clé configurée, le test s’arrête avant tout appel au fournisseur.

## Formulaire fournisseur en six étapes (V4)

Le type est choisi dès l’identification ; les obligations changent selon les six
familles de contrats. La navigation conserve la saisie et le récapitulatif précède
l’écriture. Le montant estimé et la fin sont facultatifs ; sans fin, il n’y a pas
de rappel automatique. Le tableau de bord annualise uniquement les montants MGA
renseignés, sans conversion implicite des autres devises.

L’architecture JDBC et l’API `/api/state` sont conservées. `Registry.Contract`
accepte un objet `details` typé pour les informations communes et un dictionnaire
`specific` dont les clés sont contrôlées par type côté serveur. La première
obligation de chaque type est requise, ainsi que référence, intitulé, fournisseur,
responsable, service, objet et début. Les autres modalités sont facultatives pour
permettre les contrats sans engagements financiers ou sans durée déterminée.
Les références détaillées sont uniques sans distinction de casse.

La migration V4 dans `backend/src/main/resources/schema.sql` ajoute `details JSONB`
et son index unique, puis autorise `NULL` pour `amount` et `end_date`. Elle ne
supprime aucune table ou ligne et peut être rejouée. Les anciens contrats gardent
leurs identifiants, catégories et valeurs ; leur `details` reste nul jusqu’à leur
modification, où l’utilisateur choisit explicitement le type. Les sauvegardes JSON
et ZIP incluent les nouveaux champs. Les PDF sont ajoutés dans la fiche ouverte
après sauvegarde et restent associés lors des modifications.

### Démarrage entièrement sans Docker

Avec Java 21+, Maven et PostgreSQL installés et le service PostgreSQL démarré,
créer une fois un rôle et une base dédiés si nécessaire :

```bash
sudo -u postgres createuser --pwprompt pacte
sudo -u postgres createdb --owner=pacte pacte
```

Pour une base déjà existante, conserver son rôle et ses paramètres. Renseigner
`DATABASE_URL`, `DATABASE_USER` et `DATABASE_PASSWORD` dans `.env`, puis :

```bash
set -a
. ./.env
set +a
mvn -f backend/pom.xml spring-boot:run
```

Ouvrir http://127.0.0.1:8080. Ne pas enregistrer `.env` dans Git. Aucun appel IA
n’est nécessaire pour créer ou modifier un contrat.

```bash
node --check frontend/app.js
scripts/test-postgres.sh
# Facultatif, pour inclure le test OCR réel :
PACTE_TEST_TESSERACT=/usr/bin/tesseract scripts/test-postgres.sh
```

Les tests utilisent exclusivement leur cluster temporaire dans `/tmp` et vérifient
les six types, création, relecture, modification, montants et fins absents, rejet
des champs incompatibles, réexécution du schéma et conservation des PDF.
