# BeHave Copilot — Assistant documentaire intelligent

BeHave Copilot est un assistant documentaire intelligent développé dans le cadre d’un stage d’été chez **SIRYOS**.

La plateforme permet d’interroger les guides fonctionnels de la solution BeHave en langage naturel. Elle utilise une architecture **RAG — Retrieval-Augmented Generation** pour rechercher les passages pertinents dans les documents, puis générer une réponse contextualisée avec **Google Gemini**.

## Fonctionnalités principales

### Espace utilisateur

* Questions en français, en anglais ou en arabe.
* Trois niveaux de réponse : simple, détaillé et technique.
* Réponses basées sur les documents BeHave.
* Affichage des sources documentaires utilisées.
* Affichage des images pertinentes extraites des guides.
* Analyse d’images envoyées par l’utilisateur.
* Historique des conversations.
* Création et suppression de sessions.
* Interface responsive respectant l’identité visuelle SIRYOS.

### Espace administrateur

* Authentification administrateur par token.
* Importation de documents PDF et Word.
* Conversion automatique des fichiers Word en PDF.
* Extraction du texte et des images.
* Découpage des documents en chunks.
* Génération d’embeddings multilingues.
* Indexation dans PostgreSQL avec pgvector.
* Détection des modifications grâce au hash SHA-256.
* Réindexation manuelle d’un guide.
* Suppression d’un guide et de ses vecteurs.
* Consultation du statut et du nombre de chunks de chaque document.

## Architecture générale

```text
Utilisateur
    │
    ▼
Frontend Angular — port 4200
    │
    │ API REST
    ▼
Backend Spring Boot — port 8081
    │
    ├── Google Gemini
    │     ├── Génération de réponses
    │     └── Analyse d’images
    │
    ├── Modèle MiniLM multilingue
    │     └── Génération locale des embeddings
    │
    ├── PostgreSQL + pgvector
    │     ├── Guides
    │     ├── Conversations
    │     ├── Messages
    │     └── Vecteurs documentaires
    │
    └── Stockage des documents PDF et Word
```

## Technologies utilisées

### Frontend

* Angular 18.2
* TypeScript 5.5
* RxJS
* HTML5
* CSS3
* Angular Router
* Angular HttpClient

### Backend

* Java 21
* Spring Boot 3.5.15
* Spring AI 1.1.8
* Spring Web
* Spring Data JPA
* Hibernate
* Apache Tika
* Maven

### Intelligence artificielle

* Google Gemini Developer API
* Modèle Gemini Flash multimodal
* Sentence Transformers
* `paraphrase-multilingual-MiniLM-L12-v2`
* Embeddings locaux de 384 dimensions
* Architecture RAG

### Base de données

* PostgreSQL 16
* Extension pgvector
* Index vectoriel HNSW
* Distance cosinus
* Docker Compose

## Structure du projet

```text
behave-copilot/
├── backend/
│   ├── src/main/java/com/siryos/behave/chatbot/
│   │   ├── chat/
│   │   ├── config/
│   │   ├── guides/
│   │   ├── history/
│   │   ├── ingestion/
│   │   └── security/
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── docs/
│   ├── docker-compose.yml
│   ├── .env.example
│   └── pom.xml
│
├── frontend/
│   ├── src/app/
│   │   ├── admin/
│   │   ├── chat/
│   │   ├── services/
│   │   └── shared/
│   ├── src/assets/
│   ├── angular.json
│   └── package.json
│
└── README.md
```

> Adaptez les noms `backend` et `frontend` si les dossiers du dépôt portent des noms différents.

## Prérequis

Avant de lancer le projet, installez :

* Java 21
* Maven 3.9 ou une version supérieure
* Node.js 18 ou une version supérieure
* npm
* Docker Desktop
* LibreOffice pour la conversion des fichiers Word
* Une clé Google Gemini Developer API

La clé Gemini peut être obtenue depuis [Google AI Studio](https://aistudio.google.com/apikey).

## Installation

### 1. Cloner le dépôt

```bash
git clone https://github.com/VOTRE-UTILISATEUR/behave-copilot.git
cd behave-copilot
```

### 2. Démarrer PostgreSQL et pgvector

Placez-vous dans le dossier du backend :

```bash
cd backend
docker compose up -d
```

La base de données sera accessible avec les paramètres suivants :

```text
Base de données : behave_chatbot
Utilisateur     : behave
Mot de passe    : behave_pwd
Port            : 5432
```

Pour vérifier que le conteneur fonctionne :

```bash
docker ps
```

### 3. Configurer les variables d’environnement

Ne placez jamais une clé API directement dans le code ou dans un fichier envoyé sur GitHub.

#### Sous PowerShell

```powershell
$env:GEMINI_API_KEY="VOTRE_CLE_GEMINI"
$env:BEHAVE_ADMIN_USERNAME="admin"
$env:BEHAVE_ADMIN_PASSWORD="VOTRE_MOT_DE_PASSE"
```

#### Sous Linux ou macOS

```bash
export GEMINI_API_KEY="VOTRE_CLE_GEMINI"
export BEHAVE_ADMIN_USERNAME="admin"
export BEHAVE_ADMIN_PASSWORD="VOTRE_MOT_DE_PASSE"
```

Variables disponibles :

| Variable                         | Description                    | Valeur par défaut    |
| -------------------------------- | ------------------------------ | -------------------- |
| `GEMINI_API_KEY`                 | Clé de la Gemini Developer API | Obligatoire          |
| `BEHAVE_ADMIN_USERNAME`          | Identifiant administrateur     | `admin`              |
| `BEHAVE_ADMIN_PASSWORD`          | Mot de passe administrateur    | `changeme`           |
| `BEHAVE_ADMIN_TOKEN_TTL_MINUTES` | Durée du token en minutes      | `480`                |
| `BEHAVE_DOCS_STORAGE_DIR`        | Dossier des documents          | `./data/behave-docs` |

En production, remplacez obligatoirement les identifiants administrateur par défaut.

### 4. Lancer le backend

Depuis le dossier du backend :

```bash
mvn spring-boot:run
```

Le backend sera disponible sur :

```text
http://localhost:8081
```

Lors du premier démarrage, le modèle d’embedding multilingue peut être téléchargé automatiquement. Cette opération peut prendre quelques minutes.

### 5. Lancer le frontend

Ouvrez un deuxième terminal :

```bash
cd frontend
npm install
npm start
```

L’interface sera disponible sur :

```text
http://localhost:4200
```

## Accès à l’application

| Espace                | Adresse                       |
| --------------------- | ----------------------------- |
| Interface de chat     | `http://localhost:4200`       |
| Espace administrateur | `http://localhost:4200/admin` |
| Backend REST          | `http://localhost:8081`       |

## Configuration de l’adresse du backend

L’adresse du backend est centralisée dans :

```text
frontend/src/app/shared/api.ts
```

Configuration locale :

```typescript
export const API_ORIGIN = 'http://localhost:8081';
```

Si le backend est déployé sur un autre serveur, modifiez cette valeur.

## Principales API

### Conversation

| Méthode  | Endpoint                           | Description                         |
| -------- | ---------------------------------- | ----------------------------------- |
| `POST`   | `/api/chat`                        | Envoyer une question textuelle      |
| `POST`   | `/api/chat/image`                  | Envoyer une image avec une question |
| `GET`    | `/api/chat/sessions`               | Récupérer les conversations         |
| `POST`   | `/api/chat/sessions`               | Créer une conversation              |
| `GET`    | `/api/chat/sessions/{id}/messages` | Récupérer les messages              |
| `DELETE` | `/api/chat/sessions/{id}`          | Supprimer une conversation          |

### Documents

| Méthode | Endpoint                                   | Description                      |
| ------- | ------------------------------------------ | -------------------------------- |
| `POST`  | `/api/documents/ingest`                    | Réindexer le corpus documentaire |
| `GET`   | `/api/documents/file/{filename}`           | Consulter un document            |
| `GET`   | `/api/documents/image/{folder}/{filename}` | Consulter une image extraite     |

### Administration

| Méthode  | Endpoint                           | Description                     |
| -------- | ---------------------------------- | ------------------------------- |
| `POST`   | `/api/admin/auth/login`            | Authentification administrateur |
| `POST`   | `/api/admin/auth/logout`           | Déconnexion                     |
| `GET`    | `/api/admin/guides`                | Liste des guides                |
| `POST`   | `/api/admin/guides`                | Ajouter un guide                |
| `POST`   | `/api/admin/guides/{id}/reprocess` | Réindexer un guide              |
| `DELETE` | `/api/admin/guides/{id}`           | Supprimer un guide              |

## Exemple d’utilisation de l’API

### Envoyer une question

```http
POST http://localhost:8081/api/chat
Content-Type: application/json
```

```json
{
  "question": "Comment interpréter un indicateur dans BeHave ?",
  "level": "DETAILLE",
  "history": []
}
```

Exemple de réponse :

```json
{
  "answer": "Réponse générée à partir des guides BeHave.",
  "sources": [
    {
      "fileName": "Guide_BeHave.pdf",
      "page": 12,
      "score": 0.71
    }
  ],
  "images": [],
  "language": "FR"
}
```

Les niveaux disponibles sont :

```text
SIMPLE
DETAILLE
TECHNIQUE
```

## Authentification et réindexation avec PowerShell

### Se connecter comme administrateur

```powershell
$body = @{
    username = "admin"
    password = "VOTRE_MOT_DE_PASSE"
} | ConvertTo-Json

$response = Invoke-RestMethod `
    -Uri "http://localhost:8081/api/admin/auth/login" `
    -Method Post `
    -ContentType "application/json" `
    -Body $body

$token = $response.token
```

### Relancer l’indexation documentaire

```powershell
Invoke-RestMethod `
    -Uri "http://localhost:8081/api/documents/ingest" `
    -Method Post `
    -Headers @{
        Authorization = "Bearer $token"
    }
```

### Consulter les guides

```powershell
Invoke-RestMethod `
    -Uri "http://localhost:8081/api/admin/guides" `
    -Method Get `
    -Headers @{
        Authorization = "Bearer $token"
    }
```

## Fonctionnement du pipeline RAG

Le traitement d’une question suit les étapes suivantes :

1. Réception de la question.
2. Détection de la langue.
3. Transformation de la question en vecteur.
4. Recherche des passages similaires dans pgvector.
5. Sélection des chunks les plus pertinents.
6. Construction du contexte documentaire.
7. Envoi du contexte et de la question à Google Gemini.
8. Génération de la réponse.
9. Ajout des sources et des images pertinentes.
10. Enregistrement de la conversation.

## Ingestion documentaire

Le pipeline d’ingestion comprend :

1. Réception du fichier PDF ou Word.
2. Conversion du document Word en PDF avec LibreOffice.
3. Extraction du texte.
4. Extraction des images.
5. Découpage du texte en chunks.
6. Génération des embeddings multilingues.
7. Indexation dans PostgreSQL avec pgvector.
8. Enregistrement des métadonnées du guide.

Un hash SHA-256 permet de vérifier si le contenu a changé. Si le document est identique, le chunking et les embeddings ne sont pas recalculés.

## Configuration vectorielle

```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 384
        initialize-schema: true
```

Le modèle d’embedding utilisé est :

```text
sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2
```

Ce modèle a été choisi pour sa prise en charge du français, de l’anglais et de l’arabe, ainsi que pour son exécution locale sans clé API supplémentaire.

## Pourquoi Gemini à la place de Groq ?

La première version utilisait Groq avec une configuration compatible avec l’API OpenAI. Plusieurs difficultés ont été rencontrées :

* erreurs de quota `429` ;
* dépendance à des modèles différents pour le texte et les images ;
* traitement manuel des délais de retry ;
* configuration multimodale plus complexe ;
* changements ou indisponibilités de certains modèles.

Google Gemini a été retenu pour la version finale grâce à :

* une prise en charge multimodale native ;
* un même modèle pour le texte et les images ;
* une intégration dédiée avec Spring AI ;
* une configuration plus simple ;
* une meilleure cohérence entre le chat et l’analyse d’images.

Les embeddings restent générés localement afin de réduire les coûts et de conserver une meilleure maîtrise du traitement documentaire.

## Sécurité

Les mesures suivantes sont intégrées ou recommandées :

* Clé Gemini stockée dans une variable d’environnement.
* Authentification de l’espace administrateur.
* Token administrateur avec durée d’expiration.
* Validation des fichiers envoyés.
* Limite d’upload fixée à 16 Mo.
* Contrôle des formats PDF et Word.
* Protection des routes administratives.
* Séparation entre l’interface utilisateur et l’administration.

Pour un déploiement en production, il est recommandé d’ajouter :

* HTTPS ;
* authentification utilisateur complète ;
* gestion des rôles ;
* stockage des sessions dans Redis ;
* limitation du nombre de requêtes ;
* journalisation sécurisée ;
* politique de conservation des documents.

## Commandes utiles

### Arrêter PostgreSQL

```bash
docker compose down
```

### Redémarrer PostgreSQL

```bash
docker compose restart
```

### Reconstruire le frontend

```bash
npm run build
```

### Lancer les tests du backend

```bash
mvn test
```

### Vider les anciens vecteurs

Cette opération supprime l’index vectoriel existant. Elle doit uniquement être utilisée lorsqu’une réindexation complète est nécessaire.

```bash
docker exec -it behave-pgvector psql \
  -U behave \
  -d behave_chatbot \
  -c "TRUNCATE vector_store;"
```

Après cette commande, relancez l’ingestion documentaire depuis l’espace administrateur ou via l’API protégée.

## Auteur

Projet réalisé dans le cadre d’un stage d’été chez **SIRYOS**.

* Étudiant(e) : **Nom et prénom**
* Entreprise d’accueil : **SIRYOS**
* Encadrant : **Nom de l’encadrant**
* Année universitaire : **2025–2026**

## Remarque

Ce projet contient des éléments développés pour la solution BeHave de SIRYOS. Avant de publier le dépôt, vérifiez qu’aucune clé API, donnée confidentielle ou documentation interne n’est incluse.
