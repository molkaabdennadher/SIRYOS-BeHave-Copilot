# BeHave Copilot — Interface Angular SIRYOS

Interface de chat SIRYOS pour interroger le backend RAG BeHave (Spring Boot + Groq + pgvector).

## Prérequis

- Node.js 18+ installé (https://nodejs.org — prends la version LTS)
- Le backend Spring Boot doit déjà tourner (actuellement sur `http://localhost:8081`)

## Installation

Ouvre un terminal PowerShell dans ce dossier (`behave-chat-ui`) et lance :

```powershell
npm install
npm start
```

Cela installe Angular et démarre le serveur de développement. Ouvre ensuite ton navigateur sur :

```
http://localhost:4200
```

## Si ton backend tourne sur un autre port

L'origine du backend est centralisée dans **un seul fichier** :
`src/app/shared/api.ts`

```typescript
export const API_ORIGIN = 'http://localhost:8081';
```

Change `8081` par le port que ton backend utilise réellement — la page de
chat, le dashboard admin et les services d'historique le lisent tous
depuis cet unique endroit.

## Pages / routes

- `/` — page de chat (`src/app/chat/chat.component.ts`)
- `/admin` — espace administrateur (`src/app/admin/admin-dashboard.component.ts`)

Les deux composants sont chargés en lazy-loading (`app.routes.ts`) : le
code de l'un n'est téléchargé par le navigateur que si l'utilisateur
visite réellement la page correspondante.

## Ce que fait cette interface

### Chat
- Identité visuelle SIRYOS et interface responsive
- Logo BeHave Copilot intégré dans l’en-tête, les réponses et l’écran d’accueil
- Favicon BeHave Copilot affiché dans l’onglet du navigateur
- Copilote visuel « S » avec suivi fluide de la souris et réactions contextuelles
- Sélecteur animé Simple / Détaillé / Technique avec informations sur chaque offre
- Simple : réponse courte et directe (version gratuite)
- Détaillé : contexte, références et exemples (version semi-payante)
- Technique : analyse experte orientée développement et architecture (version payante)
- Zone de chat avec bulles utilisateur / bot
- Affichage des sources documentaires citées sous chaque réponse (Explainable AI)
- Affichage des illustrations pertinentes sélectionnées depuis le guide
- Suggestions de questions pré-remplies pour tester rapidement
- Barre d’actions : nouveau chat, copie de la dernière réponse et raccourci `Ctrl + K`
- Indicateur du mode actif et compteur de caractères dans la zone de saisie
- Gestion des erreurs si le backend n'est pas joignable

### Historique des conversations (façon ChatGPT)
- Sidebar rétractable (icône ☰ dans l'en-tête) listant toutes les
  conversations passées, triées par date de dernière activité
- Titre auto-généré à partir du premier message de chaque conversation
- Clic sur une conversation -> recharge tous ses messages
- Bouton « Nouvelle conversation » -> crée explicitement une nouvelle
  session côté backend
- Suppression d'une conversation (icône 🗑) avec confirmation
- La conversation en cours est mémorisée (`sessionStorage`) et rechargée
  automatiquement si la page est rafraîchie
- Implémentation : `src/app/services/chat-history.service.ts`
  (consomme `GET/POST/DELETE /api/chat/sessions`)

### Espace administrateur — gestion des guides (`/admin`)
- Dépôt de guides PDF/Word par glisser-déposer ou sélecteur de fichier
  (plusieurs fichiers à la fois)
- Table des guides indexés : statut (Indexé / En cours / Échec), nombre
  de chunks, hash de contenu (8 premiers caractères), date de dernière
  indexation
- **Règle obligatoire respectée côté backend** : si le contenu d'un
  guide déjà présent n'a pas changé (même hash SHA-256), l'upload ne
  déclenche **aucun** ré-embedding/ré-chunking — le composant affiche
  alors « Aucun changement — embedding non refait ». Si le contenu a
  changé, ou si c'est un nouveau guide, le chunking + l'embedding sont
  refaits automatiquement
- Bouton « ⟲ » pour forcer une ré-indexation manuelle (debug / config)
- Bouton « 🗑 » pour supprimer un guide (retire aussi ses chunks du
  vector store côté backend)
- Implémentation : `src/app/services/guide-admin.service.ts`
  (consomme `GET/POST/DELETE /api/admin/guides`)

## Note CORS

Le backend Spring Boot autorise déjà les requêtes depuis `http://localhost:4200`
(`@CrossOrigin(origins = "http://localhost:4200")` dans `ChatController.java`).
Si tu changes le port du frontend, il faudra aussi mettre à jour cette annotation côté backend.
