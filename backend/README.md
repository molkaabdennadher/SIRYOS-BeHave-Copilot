# Chatbot BeHave — V0 (RAG documentaire) avec Groq

## Où mettre ta clé Groq

Un seul endroit compte : la variable d'environnement **`GROQ_API_KEY`**,
lue par `src/main/resources/application.yml` via `${GROQ_API_KEY}`.

Ne jamais écrire la clé en dur dans `application.yml` (risque de fuite / commit Git).

### Option A — lancer depuis IntelliJ IDEA
1. Menu **Run > Edit Configurations...**
2. Sélectionner la configuration `ChatbotApplication`
3. Dans **Environment variables**, ajouter :
   ```
   GROQ_API_KEY=gsk_ta_vraie_cle_ici
   ```
4. Run.

### Option B — lancer en ligne de commande
```bash
export GROQ_API_KEY=gsk_ta_vraie_cle_ici
mvn spring-boot:run
```

### Option C — fichier .env
Un fichier `.env.example` est fourni à la racine. Copie-le en `.env`,
mets ta clé dedans, et configure ton plugin EnvFile (IntelliJ) ou ton
shell pour le charger avant de lancer l'appli.

Récupérer une clé Groq : https://console.groq.com/keys

## Démarrage

```bash
docker compose up -d          # lance PostgreSQL + pgvector
export GROQ_API_KEY=...       # cf. ci-dessus
mvn spring-boot:run
```

Puis :
1. Déposer les guides BeHave (PDF/Word) dans `src/main/resources/docs/`
2. Indexer : `POST http://localhost:8080/api/documents/ingest`
3. Interroger :
   ```json
   POST http://localhost:8080/api/chat
   {
     "question": "Comment interpréter un score de risque SoD dans BeHave Access ?",
     "level": "DETAILLE"
   }
   ```

## Nouveau : images

### 1. Le chatbot explique une image envoyée par l'utilisateur

```
POST http://localhost:8081/api/chat/image
Content-Type: multipart/form-data

image: <fichier>          (requis)
question: <texte>         (optionnel — sinon "Décris cette image...")
level: SIMPLE|DETAILLE|TECHNIQUE   (optionnel)
sessionId: <uuid>         (optionnel)
```

Utilise un modèle multimodal Groq (`qwen/qwen3.6-27b`, cf.
`ChatService.VISION_MODEL`) — différent du modèle texte configuré dans
`application.yml`, car ce dernier ne comprend pas les images.

### 2. Le chatbot renvoie les images du guide pertinentes pour une question

Le endpoint `POST /api/chat` existant renvoie désormais aussi un champ
`images` :

```json
{
  "answer": "...",
  "sources": [...],
  "language": "FR",
  "images": [
    { "documentId": "User Guide_PBI BeHave.pdf", "fileName": "page12_img1.png", "page": 12, "url": "/api/documents/image/User_Guide_PBI_BeHave/page12_img1.png" }
  ]
}
```

Le frontend n'a rien à construire : il suffit d'afficher `<img src="{url}">`
(URL relative au backend, à préfixer par l'origine si besoin). Ces images
sont extraites automatiquement à l'ingestion (`POST /api/documents/ingest`)
depuis les pages des guides PDF (natifs ou convertis depuis Word) — les
petites icônes/puces sont filtrées. Si le retrieval est de faible confiance,
`images` est vide, comme `sources`.

## Notes techniques

- **Chat** : Groq via `spring-ai-openai-spring-boot-starter`, en pointant
  `spring.ai.openai.base-url` vers `https://api.groq.com/openai`. Groq expose
  une API compatible OpenAI, donc aucun code Java spécifique n'est nécessaire.
- **Embeddings** : Groq ne fournit pas d'endpoint d'embeddings. Le projet
  utilise donc un modèle local gratuit et **multilingue**
  (`sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`, dimension 384)
  via `spring-ai-starter-model-transformers` — aucune clé requise. Ce modèle a
  remplacé `all-MiniLM-L6-v2` (anglophone) car les guides BeHave sont en
  français : avec le modèle anglais, la similarité entre une question/image
  et le bon passage du guide était peu fiable (mauvaise correspondance).
  Au premier démarrage après ce changement, le modèle ONNX (~470 Mo) est
  téléchargé une fois et mis en cache local.
- **Vector store** : pgvector (PostgreSQL), dimension alignée sur 384.
  ⚠️ Après un changement de modèle d'embedding, les vecteurs déjà indexés
  sont incompatibles : vider la table puis ré-indexer :
  ```bash
  docker exec -it behave-pgvector psql -U behave -d behave_chatbot -c "TRUNCATE vector_store;"
  # puis
  curl -X POST http://localhost:8081/api/documents/ingest
  ```
