package com.sonny.parserag.service.fallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.model.domain.Chunk;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.observability.ParseRagMetrics;
import com.sonny.parserag.observability.ParseRagMetrics.TokenType;
import com.sonny.parserag.observability.TestMetrics;
import com.sonny.parserag.service.extraction.PageGeometryAnalyzer;
import com.sonny.parserag.service.extraction.PdfTextExtractorService;
import com.sonny.parserag.service.extraction.ScannedPageDetector;
import com.sonny.parserag.service.fallback.ScannedDocumentFallbackService.ScannedExtraction;
import com.sonny.parserag.service.processing.ChunkingService;
import com.sonny.parserag.service.processing.ConfidenceCalculatorService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Campagne de mesure du fallback vision par fournisseur (issue #28) — <strong>rejoue la Phase 4</strong>
 * de l'issue #11 sur les mêmes fixtures scannées, pour comparer Gemini 2.5 Flash-Lite au rapport
 * OpenAI {@code results/PHASE4-scanned-vision-2026-06-26.md}.
 *
 * <p>Ce que ça mesure, et pourquoi : sous OpenAI, le facteur limitant était le plafond RPM/TPM du
 * compte, pas notre code — d'où deux relevés qui comptent plus que le reste :
 * <ul>
 *   <li>le <strong>set exact</strong> des pages passées en vision (et non juste leur nombre) ;</li>
 *   <li>sa <strong>stabilité</strong> sur plusieurs runs du document long : c'est un set qui change
 *       d'un run à l'autre qui avait prouvé que la cause était le rate-limit et non un bug.</li>
 * </ul>
 *
 * <p><strong>Appels réseau réels et facturés.</strong> Désactivé en CI. Pour lancer :
 * <pre>
 *   GOOGLE_API_KEY dans .env (ou variable d'environnement), retirer @Disabled, puis
 *   ./mvnw test -Dtest=VisionProviderBenchmark -DfailIfNoTests=false
 * </pre>
 * Le provider se choisit par la variable {@code VISION_PROVIDER} ({@code gemini} par défaut,
 * {@code openai} pour rejouer l'ancien chemin avec {@code OPENAI_API_KEY}).
 *
 * <p>Écrit un rapport Markdown dans {@code src/test/resources/sample-pdfs/results/} (répertoire
 * gitignoré), au format de la scorecard existante pour lecture côte à côte.
 */
@Disabled("Campagne de mesure manuelle — appels API réels et facturés ; retirer @Disabled pour lancer")
class VisionProviderBenchmark {

    private static final Path FIXTURES = Path.of("src/test/resources/sample-pdfs/scanned");
    private static final Path RESULTS = Path.of("src/test/resources/sample-pdfs/results");

    /** Le document qui avait révélé le plafond OpenAI : rejoué N fois pour tester le déterminisme. */
    private static final String LONG_DOC = "scanned-long-25p.pdf";
    private static final int DETERMINISM_RUNS = 3;

    /** Fixtures dans l'ordre de la scorecard Phase 4. */
    private static final List<String> ORDER = List.of(
            "scanned-text-en.pdf", "scanned-text-fr.pdf", "scanned-with-table.pdf",
            "scanned-multicolumn.pdf", "scanned-mixed.pdf", "scanned-figure-only.pdf",
            "scanned-empty.pdf", LONG_DOC);

    /**
     * Tarifs au million de tokens, relevés le 20/08/2026 sur
     * <a href="https://ai.google.dev/gemini-api/docs/pricing">la page officielle</a> (palier
     * payant, inférence standard). À revérifier avant de conclure : un tarif qui a bougé invalide
     * la comparaison de coût, pas celle de qualité.
     */
    private static final Map<String, double[]> PRICE_PER_MTOK = Map.of(
            "gemini-3.5-flash-lite", new double[]{0.30, 2.50},
            "gemini-3.1-flash-lite", new double[]{0.25, 1.50},
            "gemini-2.5-flash-lite", new double[]{0.10, 0.40},
            "gpt-4o-mini", new double[]{0.15, 0.60});

    /** Relevé d'un passage sur une fixture. */
    private record Run(int scannedPages, Set<Integer> visionPages, Set<Integer> reviewPages,
                       int textChunks, int tables, long millis,
                       List<Chunk> chunks, List<TableResult> extracted,
                       long promptTokens, long completionTokens) {
    }

    private final String provider = env("VISION_PROVIDER", "gemini");

    /**
     * Registre en mémoire conservé au niveau du harnais : c'est lui qui porte les compteurs de
     * tokens, seule source du coût réel. Sans registre partagé entre les passes, chaque appel
     * repartirait de zéro et le rapport ne saurait rien dire de la facture.
     */
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void benchmark() throws Exception {
        AppProperties props = props();
        VisionFallback vision = vision(props);
        if (!vision.isAvailable()) {
            throw new IllegalStateException(
                    "Fallback vision indisponible : renseigner la clé du provider '" + provider + "'.");
        }

        ScannedDocumentFallbackService fallback = new ScannedDocumentFallbackService(
                vision, new ChunkingService(props, new ConfidenceCalculatorService(props)));
        PdfTextExtractorService extractor = new PdfTextExtractorService(props, new PageGeometryAnalyzer());
        ScannedPageDetector detector = new ScannedPageDetector();

        Map<String, List<Run>> runs = new LinkedHashMap<>();
        for (String fixture : ORDER) {
            byte[] pdf = Files.readAllBytes(FIXTURES.resolve(fixture));
            int passes = LONG_DOC.equals(fixture) ? DETERMINISM_RUNS : 1;

            List<Run> collected = new ArrayList<>(passes);
            for (int i = 0; i < passes; i++) {
                collected.add(runOnce(pdf, extractor, detector, fallback, props));
            }
            runs.put(fixture, collected);
        }

        // Le modèle fait partie du nom : deux campagnes le même jour (comparaison de modèles,
        // issue #51) s'écraseraient l'une l'autre sans lui.
        Path report = RESULTS.resolve(
                "PHASE4-%s-%s-%s.md".formatted(provider, model(props), LocalDate.now()));
        Files.createDirectories(RESULTS);
        Files.writeString(report, renderReport(runs, props));
        System.out.println("Rapport écrit : " + report.toAbsolutePath());
    }

    /** Un passage complet extraction → détection → fallback vision, chronométré. */
    private Run runOnce(byte[] pdf, PdfTextExtractorService extractor, ScannedPageDetector detector,
                        ScannedDocumentFallbackService fallback, AppProperties props) {
        ExtractedDocument doc = extractor.extract(pdf, Plan.SCALE);
        Set<Integer> scanned = detector.scannedPages(doc);

        // Les compteurs sont cumulatifs : on prend un delta autour de la passe pour attribuer les
        // tokens à *cette* fixture plutôt qu'à toute la campagne.
        long promptBefore = tokens(TokenType.PROMPT);
        long completionBefore = tokens(TokenType.COMPLETION);

        long start = System.currentTimeMillis();
        ScannedExtraction res = fallback.process(pdf, doc, scanned,
                new VisionBudget(props.getVision().getMaxPagesPerDocument()));
        long millis = System.currentTimeMillis() - start;

        // Une page est « vision » dès qu'elle produit au moins un chunk reconstruit ; « review »
        // quand elle n'a produit que le placeholder manual_review (échec, sur-budget, page vide).
        Set<Integer> visionPages = new TreeSet<>();
        Set<Integer> reviewPages = new TreeSet<>();
        for (Chunk c : res.textChunks()) {
            (c.manualReviewNeeded() ? reviewPages : visionPages).add(c.page());
        }
        for (TableResult t : res.tables()) visionPages.add(t.page());
        reviewPages.removeAll(visionPages);

        return new Run(scanned.size(), visionPages, reviewPages,
                res.textChunks().size(), res.tables().size(), millis,
                res.textChunks(), res.tables(),
                tokens(TokenType.PROMPT) - promptBefore,
                tokens(TokenType.COMPLETION) - completionBefore);
    }

    // ── Rapport ───────────────────────────────────────────────────────────────────────────

    private String renderReport(Map<String, List<Run>> runs, AppProperties props) {
        StringBuilder md = new StringBuilder();
        md.append("# Phase 4 — fallback vision `%s` — %s\n\n".formatted(provider, LocalDate.now()));
        md.append("Modèle : `%s` · budget vision : %d pages/doc · en-process (pas de serveur HTTP).\n"
                .formatted(model(props), props.getVision().getMaxPagesPerDocument()));
        md.append("À comparer à `PHASE4-scanned-vision-2026-06-26.md` (OpenAI gpt-4o-mini).\n\n");

        md.append("## Scorecard\n\n");
        md.append("| Fixture | Pages scannées | Vision | Manual review | Chunks | Tableaux | Durée |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        runs.forEach((fixture, list) -> {
            Run r = list.getFirst();
            md.append("| %s | %d | **%d** | %d | %d | %d | %.1f s |\n".formatted(
                    fixture.replace(".pdf", ""), r.scannedPages(), r.visionPages().size(),
                    r.reviewPages().size(), r.textChunks(), r.tables(), r.millis() / 1000.0));
        });

        md.append(renderCost(runs, model(props)));

        List<Run> longRuns = runs.get(LONG_DOC);
        md.append("\n## Déterminisme — `%s` (%d runs)\n\n".formatted(LONG_DOC, longRuns.size()));
        md.append("C'est le relevé décisif : sous OpenAI, le set de pages réussies changeait d'un run\n");
        md.append("à l'autre, signature d'un rate-limit côté compte.\n\n");
        md.append("| Run | Vision | Pages vision | Pages review | Durée |\n|---|---|---|---|---|\n");
        for (int i = 0; i < longRuns.size(); i++) {
            Run r = longRuns.get(i);
            md.append("| %d | %d/%d | %s | %s | %.1f s |\n".formatted(
                    i + 1, r.visionPages().size(), r.scannedPages(),
                    r.visionPages(), r.reviewPages(), r.millis() / 1000.0));
        }

        boolean stable = longRuns.stream().map(Run::visionPages).distinct().count() == 1;
        Run first = longRuns.getFirst();
        boolean complete = first.visionPages().size() == first.scannedPages();
        md.append("\n**Set identique sur les %d runs : %s** · **toutes les pages en vision : %s**\n"
                .formatted(longRuns.size(), stable ? "OUI" : "NON", complete ? "OUI" : "NON"));
        md.append("\n> Critère d'acceptation #28 : les deux doivent être OUI.\n");

        // Extraits : le nombre de chunks ne dit rien de la fidélité. Le rapport OpenAI jugeait sur le
        // contenu (texte verbatim, grille exacte, ordre de lecture) — on donne de quoi refaire ce jugement.
        md.append("\n## Extraits (1ᵉʳ run)\n");
        runs.forEach((fixture, list) -> {
            Run r = list.getFirst();
            md.append("\n### %s\n\n".formatted(fixture.replace(".pdf", "")));
            if (r.chunks().isEmpty() && r.extracted().isEmpty()) {
                md.append("_(aucune sortie — la page n'apparaît nulle part dans la réponse)_\n");
            }
            for (Chunk c : r.chunks()) {
                md.append("- p%d%s : `%s`\n".formatted(c.page(),
                        c.manualReviewNeeded() ? " **[manual review]**" : "", excerpt(c.text())));
            }
            for (TableResult t : r.extracted()) {
                md.append("- p%d table %dx%d — en-têtes `%s`, 1ʳᵉ ligne `%s`\n".formatted(
                        t.page(), t.rowCount(), t.colCount(), t.headers(),
                        t.rows().isEmpty() ? "—" : t.rows().getFirst()));
            }
        });
        return md.toString();
    }

    /**
     * Tokens et cout de la campagne — c'est ce qui permet de comparer deux modeles autrement qu'a
     * l'impression. Le cout est calcule sur les tokens <em>reellement factures</em> remontes par
     * l'API, pas sur une estimation a priori.
     */
    private String renderCost(Map<String, List<Run>> runs, String model) {
        long prompt = tokens(TokenType.PROMPT);
        long completion = tokens(TokenType.COMPLETION);
        long thoughts = tokens(TokenType.THOUGHTS);

        StringBuilder md = new StringBuilder("\n## Tokens et cout\n\n");
        md.append("| Fixture | Tokens entree | Tokens sortie |\n|---|---|---|\n");
        runs.forEach((fixture, list) -> {
            long in = list.stream().mapToLong(Run::promptTokens).sum();
            long out = list.stream().mapToLong(Run::completionTokens).sum();
            md.append("| %s%s | %d | %d |\n".formatted(fixture.replace(".pdf", ""),
                    list.size() > 1 ? " (%d runs)".formatted(list.size()) : "", in, out));
        });
        md.append("| **Total campagne** | **%d** | **%d** |\n".formatted(prompt, completion));

        if (thoughts > 0) {
            md.append("\n%d tokens de thinking factures en sus — le reglage cense les annuler ne mord pas.\n"
                    .formatted(thoughts));
        }

        double[] price = PRICE_PER_MTOK.get(model);
        if (price == null) {
            md.append("\n_Tarif inconnu pour `%s` : cout non calcule._\n".formatted(model));
            return md.toString();
        }
        double cost = (prompt / 1_000_000.0) * price[0] + (completion / 1_000_000.0) * price[1];
        md.append("\nTarif `%s` : $%.2f / $%.2f par million (entree / sortie) → **cout de la campagne : $%.4f**.\n"
                .formatted(model, price[0], price[1], cost));
        md.append("Ratio sortie/entree : %.2f — c'est lui qui decide quel ecart de tarif compte.\n"
                .formatted(prompt == 0 ? 0 : (double) completion / prompt));
        return md.toString();
    }

    /** Somme des compteurs de tokens d'un type donne, tous providers et modeles confondus. */
    private long tokens(TokenType type) {
        return (long) registry.find(ParseRagMetrics.VISION_TOKENS)
                .tag("type", type.name().toLowerCase())
                .counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    /** Première ligne, tronquée : de quoi juger la fidélité sans noyer le rapport. */
    private static String excerpt(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 140 ? flat : flat.substring(0, 140) + "…";
    }

    // ── Câblage ───────────────────────────────────────────────────────────────────────────

    private VisionFallback vision(AppProperties props) {
        VisionResponseParser parser = new VisionResponseParser(new ObjectMapper());
        ParseRagMetrics metrics = TestMetrics.metrics(registry);   // registre du harnais, aucun export
        return "openai".equals(provider)
                ? new OpenAiVisionFallbackService(props, parser, metrics)
                : new GeminiVisionFallbackService(props, parser, metrics);
    }

    private String model(AppProperties props) {
        return "openai".equals(provider) ? props.getOpenai().getModel() : props.getGemini().getModel();
    }

    /** Reprend les valeurs d'{@code application.yaml} (le harnais tourne sans contexte Spring). */
    private AppProperties props() throws IOException {
        AppProperties p = new AppProperties();
        p.getVision().setEnabled(true);
        p.getVision().setProvider(provider);
        // Cap volontairement au-dessus des 25 pages du doc long : on mesure l'API, pas le budget.
        p.getVision().setMaxPagesPerDocument(30);
        p.getGemini().setApiKey(env("GOOGLE_API_KEY", dotenv("GOOGLE_API_KEY")));
        p.getGemini().setModel(env("GEMINI_MODEL", p.getGemini().getModel()));
        p.getOpenai().setApiKey(env("OPENAI_API_KEY", dotenv("OPENAI_API_KEY")));
        p.getOpenai().setModel("gpt-4o-mini");

        p.getPageLimits().setMaxPagesScale(1000);
        p.getChunking().setMaxChunkSize(2000);
        p.getChunking().setOverlap(100);
        p.getChunking().setMinChunkSize(30);
        p.getConfidence().setMaxAnomalyRate(0.2);
        p.getConfidence().setPenaltyFloor(0.3);
        p.getConfidence().setMaxSuspectLineRate(0.3);
        p.getConfidence().setReadingOrderFloor(0.3);
        p.getConfidence().setManualReviewThreshold(0.4);
        return p;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v : fallback;
    }

    /** Lit une clé du {@code .env} du projet — le SDK ne le fait pas, seul Spring le charge. */
    private static String dotenv(String key) throws IOException {
        Path env = Path.of(".env");
        if (!Files.exists(env)) return "";
        for (String line : Files.readAllLines(env)) {
            String t = line.strip();
            if (t.startsWith(key + "=")) return t.substring(key.length() + 1).strip();
        }
        return "";
    }
}
