package com.siryos.behave.chatbot.chat;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Détection heuristique de langue (FR / EN / AR), sans dépendance externe.
 *
 * PROBLÈME CORRIGÉ :
 * Le chatbot répondait toujours en français car (1) aucune détection de
 * langue n'existait, et (2) le system prompt était rédigé en dur en
 * français, ce qui "entraînait" le LLM à répondre en français quelle que
 * soit la langue de la question.
 *
 * On ne délègue pas la détection au LLM lui-même : un system prompt
 * majoritairement en français biaise trop souvent Llama, même si on lui
 * demande de répondre "dans la langue de l'utilisateur". On détecte donc
 * la langue AVANT l'appel, et on l'injecte explicitement comme contrainte
 * forte dans le prompt (voir ChatService).
 */
@Component
public class LanguageDetector {

    public enum Language { FR, EN, AR }

    // Plage Unicode de l'alphabet arabe
    private static final Pattern ARABIC_CHARS = Pattern.compile("[\\u0600-\\u06FF]");

    private static final List<String> FRENCH_STOPWORDS = List.of(
            " le ", " la ", " les ", " des ", " une ", " un ", " est ", " que ", " qui ",
            " pour ", " avec ", " dans ", " sur ", " comment ", " pourquoi ", " est-ce ",
            " je ", " tu ", " nous ", " vous ", " ça ", " ça ", " où ", " quel ", " quelle "
    );

    private static final List<String> ENGLISH_STOPWORDS = List.of(
            " the ", " is ", " are ", " what ", " how ", " why ", " for ", " with ",
            " in ", " on ", " to ", " of ", " does ", " do ", " can ", " you ", " i ",
            " we ", " which ", " where ", " please "
    );

    private static final List<String> FRENCH_DIACRITICS_HINT =
            List.of("é", "è", "à", "ç", "ù", "ê", "â", "î", "ô", "û", "ë", "ï");

    /**
     * Détecte la langue principale du texte fourni.
     */
    public Language detect(String text) {
        if (text == null || text.isBlank()) {
            return Language.FR;
        }
        String normalized = " " + text.toLowerCase() + " ";

        long arabicChars = ARABIC_CHARS.matcher(text).results().count();
        if (arabicChars > 0 && arabicChars >= text.length() * 0.2) {
            return Language.AR;
        }

        int frenchScore = 0;
        int englishScore = 0;
        for (String w : FRENCH_STOPWORDS) if (normalized.contains(w)) frenchScore++;
        for (String w : ENGLISH_STOPWORDS) if (normalized.contains(w)) englishScore++;
        for (String d : FRENCH_DIACRITICS_HINT) if (normalized.contains(d)) frenchScore++;

        if (frenchScore == 0 && englishScore == 0) {
            // Aucun signal fort -> on ne force plus le français par défaut.
            // On retombe sur l'anglais, langue "neutre" la plus courante,
            // plutôt que d'imposer le français comme avant.
            return Language.EN;
        }
        return frenchScore >= englishScore ? Language.FR : Language.EN;
    }

    /**
     * Un message très court ("ok", "merci", "yes"...) n'est pas fiable pour
     * détecter/changer la langue courante d'une conversation : on préfère
     * dans ce cas conserver la langue déjà utilisée dans la session.
     */
    public boolean isReliable(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        return trimmed.length() >= 8 && trimmed.split("\\s+").length >= 2;
    }
}
