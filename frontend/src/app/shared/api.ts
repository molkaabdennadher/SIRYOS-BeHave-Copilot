// Origine unique du backend Spring Boot. Centralisée ici pour que la page
// Chat, le Dashboard Admin et les services d'historique restent cohérents
// si l'URL change un jour (env, proxy, déploiement...).
export const API_ORIGIN = 'http://localhost:8081';
