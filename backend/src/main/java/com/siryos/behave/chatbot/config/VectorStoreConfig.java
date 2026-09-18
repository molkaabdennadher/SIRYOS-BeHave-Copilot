package com.siryos.behave.chatbot.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {

    @Value("${behave.chunk.size}")
    private int chunkSize;

    @Value("${behave.chunk.overlap}")
    private int chunkOverlap;

    /**
     * Découpage "structure-aware" simplifié via TokenTextSplitter :
     * chunking par tokens avec overlap pour préserver le contexte
     * des guides longs (cf. slide "Benchmarking V0").
     *
     * CORRIGÉ : Spring AI 1.1.x a retiré le constructeur à 5 arguments
     * (chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks,
     * keepSeparator) au profit d'un Builder — d'où l'erreur de compilation
     * "no suitable constructor found for TokenTextSplitter(int,int,int,int,boolean)".
     * On repasse donc par TokenTextSplitter.builder(), en conservant
     * exactement les mêmes valeurs qu'avant pour ne rien changer au
     * comportement du chunking.
     *
     * NOTE : TokenTextSplitter n'a jamais eu de véritable notion
     * d'"overlap" entre chunks (aucun des deux, ni le constructeur retiré
     * ni le Builder actuel, ne prend un paramètre d'overlap — cf.
     * spring-projects/spring-ai#2123, toujours ouvert). La propriété
     * `behave.chunk.overlap` continue donc, comme avant cette correction,
     * à être utilisée comme `minChunkSizeChars` (taille minimale, en
     * caractères, en dessous de laquelle un chunk est fusionné avec le
     * suivant plutôt que conservé isolé) : ce n'est pas un vrai overlap,
     * mais c'est le comportement déjà en place, on ne le modifie pas ici.
     */
    @Bean
    public TokenTextSplitter textSplitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(chunkOverlap)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000) // sécurité : nombre max de chunks par document
                .withKeepSeparator(true)
                .build();
    }
}
