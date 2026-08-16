package com.sonny.parserag.service.fallback;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.model.domain.VisionPageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

/**
 * Fallback vision sur OpenAI (GPT-4o mini) — implémentation <strong>historique</strong> du port
 * {@link VisionFallback}, conservée derrière {@code parserag.vision.provider: openai} (issue #28).
 *
 * <p>Elle n'est plus le défaut : le plafond RPM/TPM du compte OpenAI, partagé par tout le compte,
 * s'est révélé être le facteur limitant du fallback (cf. Phase 4 de #11 — sur un scan de 25 pages,
 * 9 puis 12 pages seulement passaient en vision, avec un set non déterministe). Elle reste ici le
 * temps de comparer la <em>qualité</em> d'extraction avec {@link GeminiVisionFallbackService} sur le
 * même corpus, puis sera supprimée.
 *
 * <p>Dégradation gracieuse selon le contrat du port : vision désactivée ou clé absente (cas dev,
 * {@code OPENAI_API_KEY} vide) → {@link #isAvailable()} faux et aucun client n'est bâti ; appel en
 * échec → {@code null}.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "parserag.vision", name = "provider", havingValue = "openai")
public class OpenAiVisionFallbackService implements VisionFallback {

    private final AppProperties appProperties;
    private final VisionResponseParser parser;

    /**
     * Client OpenAI officiel. Construit <em>paresseusement</em> au premier appel ({@link #client()}) :
     * en dev la clé est vide et {@link #isAvailable()} reste faux, donc on ne tente jamais de bâtir un
     * client sans clé. {@code volatile} + double-check pour la sûreté en concurrence.
     */
    private volatile OpenAIClient openAiClient;

    @Autowired
    public OpenAiVisionFallbackService(AppProperties appProperties, VisionResponseParser parser) {
        this(appProperties, parser, null);
    }

    /** Constructeur testable (client OpenAI injectable / mockable). */
    OpenAiVisionFallbackService(AppProperties appProperties, VisionResponseParser parser,
                                OpenAIClient openAiClient) {
        this.appProperties = appProperties;
        this.parser = parser;
        this.openAiClient = openAiClient;
    }

    /** Construit (une fois) puis réutilise le client OpenAI à partir de la clé configurée. */
    private OpenAIClient client() {
        OpenAIClient local = openAiClient;
        if (local == null) {
            synchronized (this) {
                local = openAiClient;
                if (local == null) {
                    local = OpenAIOkHttpClient.builder()
                            .apiKey(appProperties.getOpenai().getApiKey())
                            .maxRetries(appProperties.getOpenai().getMaxRetries())
                            .build();
                    openAiClient = local;
                }
            }
        }
        return local;
    }

    @Override
    public boolean isAvailable() {
        String key = appProperties.getOpenai().getApiKey();
        return appProperties.getVision().isEnabled() && key != null && !key.isBlank();
    }

    @Override
    public TableResult extractTable(byte[] regionPng, int page, String caption) {
        if (!isAvailable() || regionPng == null || regionPng.length == 0) return null;

        try {
            String content = callOpenAi(VisionPrompts.TABLE_SYSTEM, VisionPrompts.TABLE_USER, regionPng);
            TableResult result = parser.parseTable(content, page, caption);
            if (result != null) {
                log.info("Vision fallback — page {}, {}x{} cells", page, result.rowCount(), result.colCount());
            }
            return result;
        } catch (Exception e) {
            log.warn("Vision fallback failed on page {}: {}", page, e.toString());
            return null;
        }
    }

    @Override
    public VisionPageResult extractPage(byte[] pagePng, int page) {
        if (!isAvailable() || pagePng == null || pagePng.length == 0) return null;

        try {
            String content = callOpenAi(VisionPrompts.PAGE_SYSTEM, VisionPrompts.PAGE_USER, pagePng);
            VisionPageResult result = parser.parsePage(content, page);
            if (result != null) {
                log.info("Vision page fallback — page {}, {} chars, {} table(s)",
                        page, result.text().length(), result.tables().size());
            }
            return result;
        } catch (Exception e) {
            log.warn("Vision page fallback failed on page {}: {}", page, e.toString());
            return null;
        }
    }

    /**
     * Appel OpenAI via le SDK officiel (chat completions, vision) : message system (consigne) puis
     * message user multimodal = consigne texte + image PNG (data URI base64). Renvoie le contenu texte
     * du 1ᵉʳ choix, ou {@code null}.
     */
    private String callOpenAi(String systemPrompt, String userPrompt, byte[] png) {
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);

        ChatCompletionContentPart textPart = ChatCompletionContentPart.ofText(
                ChatCompletionContentPartText.builder().text(userPrompt).build());
        ChatCompletionContentPart imagePart = ChatCompletionContentPart.ofImageUrl(
                ChatCompletionContentPartImage.builder()
                        .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(dataUri).build())
                        .build());

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(appProperties.getOpenai().getModel())
                .temperature(0d)
                .responseFormat(ResponseFormatJsonObject.builder().build())
                .addSystemMessage(systemPrompt)
                .addUserMessageOfArrayOfContentParts(List.of(textPart, imagePart))
                .build();

        ChatCompletion completion = client().chat().completions().create(params);
        return completion.choices().stream()
                .findFirst()
                .flatMap(choice -> choice.message().content())
                .orElse(null);
    }
}
